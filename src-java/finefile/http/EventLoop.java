package finefile.http;

import finefile.http.LoadGenerator.Run;
import finefile.http.LoadGenerator.Tally;
import finefile.http.LoadGenerator.Target;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * Drives several plain http connections from one thread with a selector, so
 * that waiting on many responses costs one epoll call instead of a thread
 * switch per response.
 *
 * <p>Each slot is one unit of concurrency: it has at most one request in
 * flight, on its connection to that request's origin.
 */
final class EventLoop implements LoadGenerator.Driver {
  private final Selector selector;
  private final Slot[] slots;
  private final Tally tally = new Tally();
  private final ByteBuffer readBuffer = ByteBuffer.allocate(16 * 1024);
  private final int connectTimeoutMillis;
  private final long readTimeoutNanos;
  private Run run;
  private int active;

  EventLoop(int slotCount, int originCount, int connectTimeoutMillis, int readTimeoutMillis) {
    try {
      selector = Selector.open();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    slots = new Slot[slotCount];
    for (int i = 0; i < slotCount; i++) {
      slots[i] = new Slot(originCount);
    }
    this.connectTimeoutMillis = connectTimeoutMillis;
    this.readTimeoutNanos = readTimeoutMillis * 1_000_000L;
  }

  @Override
  public Tally tally() {
    return tally;
  }

  @Override
  public void wake() {
    selector.wakeup();
  }

  @Override
  public void closeConnections() {
    for (Slot slot : slots) {
      for (Connection c : slot.connections) {
        if (c != null) {
          c.closeChannel();
        }
      }
    }
  }

  @Override
  public void run(Run run) {
    this.run = run;
    active = 0;
    for (Slot slot : slots) {
      if (startNext(slot)) {
        active++;
      }
    }
    long nextTimeoutCheck = System.nanoTime() + 1_000_000_000L;
    while (active > 0 && !run.cancelled) {
      try {
        selector.select(1000);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (run.cancelled || Thread.currentThread().isInterrupted()) {
        return;
      }
      Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
      while (keys.hasNext()) {
        SelectionKey key = keys.next();
        keys.remove();
        if (key.isValid()) {
          handle((Connection) key.attachment(), key);
        }
      }
      long now = System.nanoTime();
      if (now - nextTimeoutCheck >= 0) {
        nextTimeoutCheck = now + 1_000_000_000L;
        expire(now);
      }
    }
  }

  /** Starts the slot's next request. Returns false when the run has none left for it. */
  private boolean startNext(Slot slot) {
    Target target;
    while ((target = run.next()) != null) {
      Connection c = slot.connections[target.origin];
      if (c == null) {
        c = new Connection(slot);
        slot.connections[target.origin] = c;
      }
      try {
        c.begin(target, false);
        return true;
      } catch (IOException e) {
        IOException error = retry(c, e);
        if (error == null) {
          return true;
        }
        if (run.cancelled) {
          return false;
        }
        tally.error(error);
      }
    }
    return false;
  }

  private void handle(Connection c, SelectionKey key) {
    try {
      if (key.isWritable()) {
        c.write();
        return;
      }
      readBuffer.clear();
      int n = c.channel.read(readBuffer);
      if (!c.inFlight) {
        // An idle connection only becomes readable when the server closes it
        // or sends something unasked, and either way it is done for.
        c.closeChannel();
        return;
      }
      if (n < 0) {
        c.parser.onEof();
      } else {
        c.parser.feed(readBuffer.array(), 0, n);
        c.deadline = System.nanoTime() + readTimeoutNanos;
      }
      if (c.parser.done()) {
        tally.status(c.parser.status());
        c.inFlight = false;
        if (n < 0 || !c.parser.keepAlive()) {
          c.closeChannel();
        }
        finish(c.slot);
      }
    } catch (IOException e) {
      fail(c, e);
    }
  }

  private void fail(Connection c, IOException e) {
    IOException error = retry(c, e);
    if (error == null || run.cancelled) {
      return;
    }
    tally.error(error);
    finish(c.slot);
  }

  /**
   * Closes c after its request failed with e, and resends the request on a
   * fresh connection if that is safe. Returns null if it did, or else the
   * error to record.
   */
  private IOException retry(Connection c, IOException e) {
    c.closeChannel();
    c.inFlight = false;
    // A server may close an idle keep-alive connection at any time, and we
    // only find out when the next request fails. If nothing of the response
    // arrived it is safe to retry a GET once on a fresh connection.
    if (c.reused && !c.retried && !c.parser.started() && !run.cancelled) {
      try {
        c.begin(c.target, true);
        return null;
      } catch (IOException retryError) {
        c.closeChannel();
        return retryError;
      }
    }
    return e;
  }

  private void finish(Slot slot) {
    if (!startNext(slot)) {
      active--;
    }
  }

  private void expire(long now) {
    for (Slot slot : slots) {
      for (Connection c : slot.connections) {
        if (c != null && c.inFlight && now - c.deadline > 0) {
          // A timeout means the server is stuck, not that the connection went
          // stale, so it is never retried.
          c.retried = true;
          fail(c, new SocketTimeoutException("Read timed out"));
        }
      }
    }
  }

  private static final class Slot {
    final Connection[] connections;

    Slot(int originCount) {
      connections = new Connection[originCount];
    }
  }

  private final class Connection {
    final Slot slot;
    final ResponseParser parser = new ResponseParser();
    SocketChannel channel;
    SelectionKey key;
    Target target;
    ByteBuffer request;
    boolean reused;
    boolean retried;
    boolean inFlight;
    long deadline;

    Connection(Slot slot) {
      this.slot = slot;
    }

    /** Sends target's request, opening a connection first if there is none. */
    void begin(Target target, boolean retry) throws IOException {
      this.target = target;
      retried = retry;
      reused = channel != null && channel.isOpen();
      if (!reused) {
        open(target);
      }
      parser.reset();
      if (request == null || request.array() != target.request) {
        request = ByteBuffer.wrap(target.request);
      } else {
        request.clear();
      }
      deadline = System.nanoTime() + readTimeoutNanos;
      inFlight = true;
      write();
    }

    private void open(Target target) throws IOException {
      SocketChannel ch = SocketChannel.open();
      try {
        ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
        // Connecting blocks this loop, but only while a connection is being
        // set up, which a benchmark does during warmup.
        ch.socket().connect(new InetSocketAddress(target.host, target.port), connectTimeoutMillis);
        ch.configureBlocking(false);
        // Read interest stays on for as long as the connection is open, since
        // toggling it would cost two epoll_ctl calls per request.
        key = ch.register(selector, SelectionKey.OP_READ, this);
      } catch (IOException | RuntimeException e) {
        ch.close();
        throw e;
      }
      channel = ch;
    }

    void write() throws IOException {
      channel.write(request);
      // A request almost always fits in the socket buffer at once.
      int ops = request.hasRemaining() ? SelectionKey.OP_WRITE : SelectionKey.OP_READ;
      if (key.interestOps() != ops) {
        key.interestOps(ops);
      }
    }

    void closeChannel() {
      SocketChannel ch = channel;
      if (ch != null) {
        try {
          ch.close();
        } catch (IOException ignored) {
          // Nothing left to clean up.
        }
      }
    }
  }
}
