package finefile.http;

import finefile.http.LoadGenerator.Run;
import finefile.http.LoadGenerator.Tally;
import finefile.http.LoadGenerator.Target;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Drives one connection per origin with blocking socket calls on its own
 * thread. Used for https, where an SSLSocket does the TLS, and whenever an
 * event loop would only have one connection to drive.
 */
final class BlockingWorker implements LoadGenerator.Driver {
  private final Connection[] connections;
  private final Tally tally = new Tally();
  private final int connectTimeoutMillis;
  private final int readTimeoutMillis;

  BlockingWorker(int originCount, int connectTimeoutMillis, int readTimeoutMillis) {
    this.connections = new Connection[originCount];
    this.connectTimeoutMillis = connectTimeoutMillis;
    this.readTimeoutMillis = readTimeoutMillis;
  }

  @Override
  public Tally tally() {
    return tally;
  }

  @Override
  public void wake() {
    // Closing the connections is what unblocks a read.
  }

  @Override
  public void run(Run run) {
    Target target;
    while ((target = run.next()) != null) {
      Connection c = connections[target.origin];
      if (c == null) {
        c = new Connection();
        connections[target.origin] = c;
      }
      try {
        tally.status(c.exchange(target, run));
      } catch (Throwable t) {
        c.close();
        if (run.cancelled) {
          return;
        }
        tally.error(t);
      }
    }
  }

  @Override
  public void closeConnections() {
    for (Connection c : connections) {
      if (c != null) {
        c.close();
      }
    }
  }

  private final class Connection {
    private final byte[] buf = new byte[16 * 1024];
    private final ResponseParser parser = new ResponseParser();
    private int pos;
    private int lim;
    private volatile Socket socket;
    private InputStream in;
    private OutputStream out;

    /** Sends target's request and reads the whole response, returning its status. */
    int exchange(Target target, Run run) throws IOException {
      boolean reused = socket != null;
      if (!reused) {
        open(target);
      }
      try {
        return send(target);
      } catch (IOException e) {
        close();
        // A server may close an idle keep-alive connection at any time, and
        // we only find out when the next request fails. If nothing of the
        // response arrived it is safe to retry a GET on a fresh connection.
        if (reused && !parser.started() && !run.cancelled) {
          open(target);
          return send(target);
        }
        throw e;
      }
    }

    private void open(Target target) throws IOException {
      Socket s = new Socket();
      try {
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(target.host, target.port), connectTimeoutMillis);
        s.setSoTimeout(readTimeoutMillis);
        if (target.https) {
          SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
              .createSocket(s, target.host, target.port, true);
          SSLParameters params = ssl.getSSLParameters();
          params.setEndpointIdentificationAlgorithm("HTTPS");
          params.setApplicationProtocols(new String[] {"http/1.1"});
          ssl.setSSLParameters(params);
          ssl.startHandshake();
          s = ssl;
        }
      } catch (IOException | RuntimeException e) {
        s.close();
        throw e;
      }
      socket = s;
      in = s.getInputStream();
      out = s.getOutputStream();
      pos = 0;
      lim = 0;
    }

    void close() {
      Socket s = socket;
      socket = null;
      if (s != null) {
        try {
          s.close();
        } catch (IOException ignored) {
          // Nothing left to clean up.
        }
      }
    }

    private int send(Target target) throws IOException {
      parser.reset();
      out.write(target.request);
      out.flush();
      while (true) {
        if (pos == lim) {
          pos = 0;
          lim = Math.max(0, in.read(buf, 0, buf.length));
          if (lim == 0) {
            parser.onEof();
            close();
            return parser.status();
          }
        }
        pos += parser.feed(buf, pos, lim - pos);
        if (parser.done()) {
          if (!parser.keepAlive()) {
            close();
          }
          return parser.status();
        }
      }
    }
  }
}
