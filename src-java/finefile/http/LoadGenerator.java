package finefile.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends GET requests over keep-alive HTTP/1.1 connections, as fast as the
 * server will answer them.
 *
 * <p>Each of {@code concurrency} connections carries one request at a time:
 * write a prebuilt request, parse just enough of the response to find where
 * it ends, repeat.
 *
 * <p>Each unit of concurrency is a {@link BlockingWorker} on its own platform
 * thread, holding one connection per origin.
 *
 * <p>Connections persist across runs, so that only warmup runs pay for
 * connecting. Responses are drained and discarded. Redirects are not
 * followed. A request that fails is counted and its connection replaced.
 */
public final class LoadGenerator implements AutoCloseable {
  /** The outcome of one run: how many responses had each status, and how many
   *  requests failed with each kind of error. */
  public static final class Result {
    public final Map<Long, Long> statuses;
    public final Map<String, Long> errors;

    Result(Map<Long, Long> statuses, Map<String, Long> errors) {
      this.statuses = statuses;
      this.errors = errors;
    }
  }

  static final class Target {
    final boolean https;
    final String host;
    final int port;
    final int origin;
    final byte[] request;

    Target(boolean https, String host, int port, int origin, byte[] request) {
      this.https = https;
      this.host = host;
      this.port = port;
      this.origin = origin;
      this.request = request;
    }
  }

  /** The requests of one run, which every worker claims from until none are left. */
  static final class Run {
    final Target[] targets;
    final long requests;
    final long first;
    final AtomicLong claimed = new AtomicLong();
    final CountDownLatch finished;
    volatile boolean cancelled;

    Run(Target[] targets, long requests, long first, int workers) {
      this.targets = targets;
      this.requests = requests;
      this.first = first;
      this.finished = new CountDownLatch(workers);
    }

    /** Returns the target of the next request, or null when there are none left. */
    Target next() {
      long i = claimed.getAndIncrement();
      if (i >= requests || cancelled) {
        return null;
      }
      return targets[(int) ((first + i) % targets.length)];
    }
  }

  /** Counts outcomes on one thread, to be summed once the run is over. */
  static final class Tally {
    private final long[] statuses = new long[1000];
    private final Map<String, long[]> errors = new HashMap<>();

    void status(int status) {
      statuses[status]++;
    }

    void error(Throwable t) {
      errors.computeIfAbsent(errorLabel(t), k -> new long[1])[0]++;
    }

    void drainInto(Map<Long, Long> statusTotals, Map<String, Long> errorTotals) {
      for (int s = 0; s < statuses.length; s++) {
        if (statuses[s] != 0) {
          statusTotals.merge((long) s, statuses[s], Long::sum);
          statuses[s] = 0;
        }
      }
      for (Map.Entry<String, long[]> e : errors.entrySet()) {
        errorTotals.merge(e.getKey(), e.getValue()[0], Long::sum);
      }
      errors.clear();
    }
  }

  private final Target[] targets;
  private final BlockingWorker[] workers;
  // Platform threads rather than virtual ones: a virtual thread that blocks on
  // a socket parks and is woken through the poller, which roughly doubles the
  // client's cost per request when concurrency is low. The pool keeps its
  // threads between runs, so they are only created during warmup.
  private final ExecutorService executor = Executors.newCachedThreadPool(
      Thread.ofPlatform().daemon().name("finefile-http-", 0).factory());
  private volatile boolean closed;
  // Where the next run starts in targets, so that urls keep cycling evenly
  // across runs whose request count is not a multiple of the url count.
  private long nextTarget;

  /**
   * @throws IllegalArgumentException naming the first url that is not an
   *     absolute http or https url
   */
  public LoadGenerator(List<String> urls, int concurrency, int connectTimeoutMillis, int readTimeoutMillis) {
    if (urls.isEmpty()) {
      throw new IllegalArgumentException("No urls");
    }
    if (concurrency < 1) {
      throw new IllegalArgumentException("concurrency must be positive");
    }
    Map<String, Integer> origins = new HashMap<>();
    targets = new Target[urls.size()];
    for (int i = 0; i < targets.length; i++) {
      targets[i] = parse(urls.get(i), origins);
    }
    int originCount = origins.size();
    workers = new BlockingWorker[concurrency];
    for (int i = 0; i < concurrency; i++) {
      workers[i] = new BlockingWorker(originCount, connectTimeoutMillis, readTimeoutMillis);
    }
  }

  private static Target parse(String url, Map<String, Integer> origins) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid url \"" + url + "\": " + e.getMessage(), e);
    }
    String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase();
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("Invalid url \"" + url + "\": the scheme must be http or https");
    }
    String host = uri.getHost();
    if (host == null) {
      throw new IllegalArgumentException("Invalid url \"" + url + "\": missing host");
    }
    boolean https = "https".equals(scheme);
    int defaultPort = https ? 443 : 80;
    int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    if (uri.getRawQuery() != null) {
      path = path + "?" + uri.getRawQuery();
    }
    String hostHeader = port == defaultPort ? host : host + ":" + port;
    byte[] request = ("GET " + path + " HTTP/1.1\r\n"
        + "Host: " + hostHeader + "\r\n"
        + "User-Agent: finefile\r\n"
        + "Accept: */*\r\n"
        + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    // URI keeps the brackets around an IPv6 literal, which the Host header
    // needs and a socket address does not.
    String socketHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    int origin = origins.computeIfAbsent(scheme + "://" + hostHeader, k -> origins.size());
    return new Target(https, socketHost, port, origin, request);
  }

  /**
   * Sends {@code requests} requests and waits for every response, cycling
   * through the urls. Must not be called concurrently with itself.
   *
   * @throws InterruptedException after closing every connection, so that the
   *     workers stop promptly instead of finishing the run
   */
  public Result run(long requests) throws InterruptedException {
    if (closed) {
      throw new IllegalStateException("LoadGenerator is closed");
    }
    Run run = new Run(targets, requests, nextTarget, workers.length);
    nextTarget = (nextTarget + requests) % targets.length;
    List<Future<?>> futures = new ArrayList<>(workers.length);
    try {
      for (BlockingWorker w : workers) {
        futures.add(executor.submit(() -> {
          try {
            w.run(run);
          } finally {
            run.finished.countDown();
          }
        }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    } catch (InterruptedException e) {
      cancel(run, futures);
      throw e;
    } catch (ExecutionException e) {
      // Workers catch everything a request can throw, so this is a bug or an
      // Error such as running out of memory.
      cancel(run, futures);
      throw new RuntimeException(e.getCause());
    }
    Map<Long, Long> statuses = new HashMap<>();
    Map<String, Long> errors = new HashMap<>();
    for (BlockingWorker w : workers) {
      w.tally().drainInto(statuses, errors);
    }
    return new Result(statuses, errors);
  }

  private void cancel(Run run, List<Future<?>> futures) {
    run.cancelled = true;
    for (Future<?> f : futures) {
      f.cancel(true);
    }
    // A blocking read only gives up when its socket closes, so close them all
    // rather than wait out the read timeout.
    closeConnections();
    try {
      run.finished.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void closeConnections() {
    for (BlockingWorker w : workers) {
      w.closeConnections();
    }
  }

  @Override
  public void close() {
    closed = true;
    executor.shutdownNow();
    closeConnections();
  }

  /** A short description of t, used to group errors in the failure report. */
  static String errorLabel(Throwable t) {
    // Exceptions are often empty wrappers, e.g. an SSLException whose cause
    // holds the useful message.
    String className = t.getClass().getSimpleName();
    Throwable last = t;
    int depth = 0;
    for (Throwable c = t; c != null && depth < 10; c = c.getCause(), depth++) {
      String m = c.getMessage();
      if (m != null && !m.isBlank()) {
        return className + ": " + m;
      }
      last = c;
    }
    return last == t ? className : className + " (" + last.getClass().getSimpleName() + ")";
  }
}
