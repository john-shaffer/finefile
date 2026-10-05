package finefile.http;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Finds where an HTTP/1.1 response ends, given its bytes in whatever pieces
 * the network delivers them.
 *
 * <p>Only what that takes is parsed: the status code and the Content-Length,
 * Transfer-Encoding and Connection headers. Bodies are skipped without being
 * copied, so a response costs a scan of its headers and nothing per body byte.
 */
final class ResponseParser {
  private static final int MAX_LINE = 8 * 1024;
  private static final int MAX_HEADER_LINES = 256;

  private static final int STATUS_LINE = 0;
  private static final int HEADERS = 1;
  private static final int BODY = 2;
  private static final int CHUNK_SIZE = 3;
  private static final int CHUNK_DATA = 4;
  private static final int CHUNK_END = 5;
  private static final int TRAILERS = 6;
  private static final int UNTIL_CLOSE = 7;
  private static final int DONE = 8;

  private final byte[] line = new byte[MAX_LINE];
  private int lineLen;
  private int headerLines;
  private int state;
  private int status;
  private boolean keepAlive;
  private boolean chunked;
  private long contentLength;
  private long remaining;
  private boolean started;

  /** Prepares to parse a new response. */
  void reset() {
    state = STATUS_LINE;
    lineLen = 0;
    started = false;
  }

  boolean done() {
    return state == DONE;
  }

  /** True once any byte of the response has arrived. */
  boolean started() {
    return started;
  }

  int status() {
    return status;
  }

  /** Whether the connection can carry another request after this response. */
  boolean keepAlive() {
    return keepAlive;
  }

  /**
   * Consumes bytes from buf[off, off + len) up to the end of the response and
   * returns how many it used.
   */
  int feed(byte[] buf, int off, int len) throws IOException {
    if (len > 0) {
      started = true;
    }
    int p = off;
    int end = off + len;
    while (p < end && state != DONE) {
      if (state == BODY || state == CHUNK_DATA) {
        int k = (int) Math.min(remaining, end - p);
        p += k;
        remaining -= k;
        if (remaining == 0) {
          state = state == BODY ? DONE : CHUNK_END;
        }
      } else if (state == UNTIL_CLOSE) {
        p = end;
      } else {
        int nl = p;
        while (nl < end && buf[nl] != '\n') {
          nl++;
        }
        int n = nl - p;
        if (lineLen + n > MAX_LINE) {
          throw new IOException("Response line longer than " + MAX_LINE + " bytes");
        }
        System.arraycopy(buf, p, line, lineLen, n);
        lineLen += n;
        p = nl;
        if (nl < end) {
          p++;
          int l = lineLen > 0 && line[lineLen - 1] == '\r' ? lineLen - 1 : lineLen;
          lineLen = 0;
          onLine(l);
        }
      }
    }
    return p - off;
  }

  /** Called when the server closes the connection. */
  void onEof() throws IOException {
    if (state == UNTIL_CLOSE) {
      state = DONE;
    } else if (state != DONE) {
      throw new EOFException(state == STATUS_LINE || state == HEADERS
          ? "Connection closed before the response was complete"
          : "Connection closed before the response body was complete");
    }
  }

  private void onLine(int len) throws IOException {
    switch (state) {
      case STATUS_LINE -> statusLine(len);
      case HEADERS -> {
        if (len == 0) {
          headersDone();
        } else if (++headerLines > MAX_HEADER_LINES) {
          throw new IOException("Too many response headers");
        } else {
          header(len);
        }
      }
      case CHUNK_SIZE -> {
        long size = parseChunkSize(len);
        if (size == 0) {
          state = TRAILERS;
        } else {
          remaining = size;
          state = CHUNK_DATA;
        }
      }
      case CHUNK_END -> {
        if (len != 0) {
          throw new IOException("Malformed chunk");
        }
        state = CHUNK_SIZE;
      }
      case TRAILERS -> {
        if (len == 0) {
          state = DONE;
        }
      }
      default -> throw new IllegalStateException("Unexpected parser state " + state);
    }
  }

  private void statusLine(int len) throws IOException {
    if (len < 12 || line[0] != 'H' || line[1] != 'T' || line[2] != 'T' || line[3] != 'P'
        || line[4] != '/' || line[5] != '1' || line[6] != '.' || line[8] != ' ') {
      throw new IOException("Malformed status line: "
          + new String(line, 0, Math.min(len, 80), StandardCharsets.ISO_8859_1));
    }
    status = digit(line[9]) * 100 + digit(line[10]) * 10 + digit(line[11]);
    if (status < 100) {
      throw new IOException("Malformed status code");
    }
    keepAlive = line[7] != '0';
    chunked = false;
    contentLength = -1;
    headerLines = 0;
    state = HEADERS;
  }

  private void header(int len) throws IOException {
    if (headerIs(len, "content-length")) {
      contentLength = parseLong(len, "content-length".length() + 1);
    } else if (headerIs(len, "transfer-encoding")) {
      chunked = valueContains(len, "transfer-encoding".length() + 1, "chunked");
    } else if (headerIs(len, "connection")) {
      int from = "connection".length() + 1;
      if (valueContains(len, from, "close")) {
        keepAlive = false;
      } else if (valueContains(len, from, "keep-alive")) {
        keepAlive = true;
      }
    }
  }

  private void headersDone() {
    if (status < 200 && status != 101) {
      // An interim response such as 100 Continue is followed by the real one.
      state = STATUS_LINE;
    } else if (status == 101) {
      // The connection now speaks some other protocol.
      keepAlive = false;
      state = DONE;
    } else if (status == 204 || status == 304) {
      state = DONE;
    } else if (chunked) {
      state = CHUNK_SIZE;
    } else if (contentLength >= 0) {
      remaining = contentLength;
      state = contentLength == 0 ? DONE : BODY;
    } else {
      // The body runs until the server closes the connection.
      keepAlive = false;
      state = UNTIL_CLOSE;
    }
  }

  private boolean headerIs(int len, String name) {
    int n = name.length();
    if (len <= n || line[n] != ':') {
      return false;
    }
    for (int i = 0; i < n; i++) {
      if (Character.toLowerCase(line[i]) != name.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private boolean valueContains(int len, int from, String token) {
    int n = token.length();
    outer:
    for (int i = from; i + n <= len; i++) {
      for (int j = 0; j < n; j++) {
        if (Character.toLowerCase(line[i + j]) != token.charAt(j)) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private long parseLong(int len, int from) throws IOException {
    int i = from;
    while (i < len && (line[i] == ' ' || line[i] == '\t')) {
      i++;
    }
    long v = 0;
    int start = i;
    for (; i < len && line[i] >= '0' && line[i] <= '9'; i++) {
      if (v > (Long.MAX_VALUE - 9) / 10) {
        throw new IOException("Content-Length too large");
      }
      v = v * 10 + (line[i] - '0');
    }
    if (i == start) {
      throw new IOException("Malformed Content-Length");
    }
    return v;
  }

  private long parseChunkSize(int len) throws IOException {
    long size = 0;
    int i = 0;
    for (; i < len; i++) {
      int d = Character.digit(line[i], 16);
      if (d < 0) {
        break;
      }
      if (size > (Long.MAX_VALUE >> 4)) {
        throw new IOException("Chunk size too large");
      }
      size = size * 16 + d;
    }
    if (i == 0) {
      throw new IOException("Malformed chunk size");
    }
    return size;
  }

  private static int digit(byte b) throws IOException {
    if (b < '0' || b > '9') {
      throw new IOException("Malformed status code");
    }
    return b - '0';
  }
}
