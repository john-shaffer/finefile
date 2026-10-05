(ns finefile.http.bench-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finefile.http.bench :as bench]
   [finefile.test-server :as server :refer [with-raw-server with-server]])
  (:import
   (java.net InetAddress ServerSocket SocketException)
   (java.util.concurrent CancellationException ConcurrentHashMap)))

(defn- command [http]
  {"alpha" {"http" (merge {"concurrency" 4 "requests" 8} http)}
   "runs" 2
   "warmup-runs" 1})

(deftest http-command?-test
  (testing "detects commands that define urls or urls-command"
    (is (bench/http-command? (command {"urls" ["http://127.0.0.1"]})))
    (is (bench/http-command? (command {"urls-command" "echo http://127.0.0.1"})))
    (is (not (bench/http-command? (command {}))))
    (is (not (bench/http-command? {"command" "true"})))))

(deftest successful-requests
  (testing "records one time per run and sends every request"
    (with-server (fn [_ _] 200)
      (fn [url counter]
        (let [result (bench/bench "." "ok" (command {"urls" [url]}))]
          (is (= 2 (alength ^doubles (get result "times"))))
          (is (every? pos? (get result "times")))
          (is (= [0 0] (vec (get result "exit_codes"))))
          (is (= "ok" (get result "command")))
          ; 1 warmup run + 2 timed runs, 8 requests each
          (is (= 24 (.get counter))))))))

(deftest urls-are-cycled
  (testing "requests cycle through every url"
    (with-server (fn [_ _] 200)
      (fn [url counter]
        (bench/bench "." "cycle"
          (assoc (command {"urls" [(str url "/a") (str url "/b") (str url "/c")]
                           "concurrency" 1
                           "requests" 3})
            "runs" 1
            "warmup-runs" 0))
        (is (= 3 (.get counter)))))))

; Low concurrency runs a blocking worker per connection and higher
; concurrency spreads connections over event loops, one per two cores, so
; these run at both to cover each.
(def ^:private concurrencies [2 64])

(defn- scaled-command
  "A command at the given concurrency, sending two requests per connection in
   each of its 1 warmup and 2 timed runs."
  [concurrency http]
  (command (merge {"concurrency" concurrency "requests" (* 2 concurrency)} http)))

(defn- total-requests [concurrency]
  (* 3 2 concurrency))

(deftest response-bodies-are-drained
  (testing "bodies sent with a Content-Length or chunked are read in full, so the connection can be reused"
    (doseq [c concurrencies
            chunked [false true]]
      (with-server (fn [_ _] {:body (apply str (repeat 50000 "x")) :chunked chunked :status 200})
        (fn [url counter]
          (let [result (bench/bench "." "body" (scaled-command c {"urls" [url]}))]
            (is (= [0 0] (vec (get result "exit_codes"))))
            (is (= (total-requests c) (.get counter)))))))))

(deftest connections-are-kept-alive
  (testing "connections are reused across requests and runs"
    (doseq [c concurrencies]
      (with-raw-server (constantly "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        (fn [url accepts]
          (let [result (bench/bench "." "keep-alive" (scaled-command c {"urls" [url]}))]
            (is (= [0 0] (vec (get result "exit_codes"))))
            ; Not exactly c: against a fast server, the first connections can
            ; take every request before the rest have opened.
            (is (<= 1 (.get accepts) c))))))))

(deftest close-delimited-bodies
  (testing "a body without a length runs until the server closes the connection"
    (doseq [c concurrencies]
      (with-raw-server (constantly {:close true :response "HTTP/1.1 200 OK\r\n\r\nhello"})
        (fn [url accepts]
          (let [result (bench/bench "." "close-delimited" (scaled-command c {"urls" [url]}))]
            (is (= [0 0] (vec (get result "exit_codes"))))
            (is (= (total-requests c) (.get accepts)))))))))

(deftest connection-close-is-honored
  (testing "Connection: close and HTTP/1.0 responses get a new connection for the next request"
    (doseq [c concurrencies
            response ["HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                      "HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n"]]
      (with-raw-server (constantly {:close true :response response})
        (fn [url accepts]
          (let [result (bench/bench "." "close" (scaled-command c {"urls" [url]}))]
            (is (= [0 0] (vec (get result "exit_codes"))))
            (is (= (total-requests c) (.get accepts)))))))))

(deftest stale-connections-are-retried
  (testing "a keep-alive connection the server closed while idle is replaced without counting a failure"
    ; The server answers one request per connection as if it would keep it
    ; open, then closes it, so every reuse finds a dead connection.
    (doseq [c concurrencies]
      (with-raw-server (constantly {:close true
                                    :response "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"})
        (fn [url accepts]
          (let [result (bench/bench "." "stale" (scaled-command c {"urls" [url]}))]
            (is (= [0 0] (vec (get result "exit_codes"))))
            (is (= (total-requests c) (.get accepts)))))))))

(deftest connections-closed-on-use-are-retried
  (testing "a reused connection the server closes on receiving the request is retried on a fresh one"
    ; A server can close a keep-alive connection just as a request is written
    ; to it, which no check before sending can rule out. This server answers
    ; the first request on each connection and closes on the second without a
    ; response, so every reuse hits that race.
    (doseq [c concurrencies]
      (with-raw-server (fn [n]
                         (if (= 1 n)
                           "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                           {:close true :response ""}))
        (fn [url accepts]
          (let [result (bench/bench "." "closed-on-use" (scaled-command c {"urls" [url]}))]
            (is (= [0 0] (vec (get result "exit_codes"))))
            ; Each connection answers exactly one request: its first, or the
            ; retry of a request that found the previous connection closed.
            (is (= (total-requests c) (.get accepts)))))))))

(deftest fresh-connections-are-not-retried
  (testing "a request that fails on a fresh connection is a failure, so a broken server is not retried forever"
    (doseq [c concurrencies]
      (with-raw-server (constantly {:close true :response ""})
        (fn [url accepts]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
                (bench/bench "." "always-closes" (scaled-command c {"urls" [url]}))))
          ; The warmup run fails, with exactly one connection per request.
          (is (= (* 2 c) (.get accepts))))))))

(deftest truncated-responses-fail
  (testing "a response cut off mid-body is a failure, not a retry"
    (doseq [c concurrencies]
      (with-raw-server (constantly {:close true
                                    :response "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort"})
        (fn [url _]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
                (bench/bench "." "truncated" (scaled-command c {"urls" [url]})))))))))

(deftest urls-are-cycled-at-any-concurrency
  (testing "requests are spread evenly over the urls"
    (doseq [c concurrencies]
      (let [paths (ConcurrentHashMap.)]
        (with-server (fn [exchange _]
                       (.merge paths (server/path exchange) 1 +)
                       200)
          (fn [url _]
            (bench/bench "." "cycle"
              (scaled-command c {"urls" [(str url "/a") (str url "/b")]}))
            (is (= {"/a" (/ (total-requests c) 2) "/b" (/ (total-requests c) 2)}
                  (into {} paths)))))))))

(deftest https-failures-are-reported
  (testing "https requests that fail the TLS handshake are counted as failures"
    ; Answers in plaintext as soon as a connection opens, which the TLS
    ; handshake rejects.
    (with-open [server (ServerSocket. 0 1024 (InetAddress/getLoopbackAddress))]
      (future
        (try
          (while true
            (with-open [socket (.accept server)]
              (.write (.getOutputStream socket) (.getBytes "HTTP/1.1 200 OK\r\n\r\n"))))
          (catch SocketException _)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
            (bench/bench "." "tls"
              (command {"urls" [(str "https://127.0.0.1:" (.getLocalPort server))]})))))))

(deftest cancelling-stops-requests
  (testing "interrupting a run closes its connections instead of waiting out the read timeout"
    (doseq [c concurrencies]
      (with-raw-server (fn [_] (Thread/sleep 60000) "")
        (fn [url _]
          (let [fut (future (bench/bench "." "hang" (scaled-command c {"urls" [url]})))
                started (System/nanoTime)]
            (Thread/sleep 200)
            (future-cancel fut)
            (is (thrown? CancellationException @fut))
            (is (< (- (System/nanoTime) started) 5000000000))))))))

(deftest unexpected-status-fails
  (testing "a non-2xx status fails the benchmark"
    (with-server (fn [_ _] 500)
      (fn [url _]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
              (bench/bench "." "server-error" (command {"urls" [url]}))))))))

(deftest redirects-are-not-followed
  (testing "redirects are reported as failures rather than followed"
    (with-server (fn [_ _] 302)
      (fn [url _]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
              (bench/bench "." "redirect" (command {"urls" [url]}))))))))

(deftest expected-status-is-honored
  (testing "a status listed in expected-status is a success"
    (with-server (fn [_ _] 404)
      (fn [url _]
        (let [result (bench/bench "." "not-found"
                       (command {"expected-status" [404] "urls" [url]}))]
          (is (= [0 0] (vec (get result "exit_codes")))))))
    (testing "and any other status is a failure"
      (with-server (fn [_ _] 200)
        (fn [url _]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
                (bench/bench "." "not-found"
                  (command {"expected-status" 404 "urls" [url]})))))))))

(deftest ignore-failure-records-exit-codes
  (testing "failures are recorded but do not abort the benchmark"
    (with-server (fn [_ n] (if (odd? n) 200 503))
      (fn [url _]
        (let [result (bench/bench "." "flaky"
                       (command {"ignore-failure" true "urls" [url]}))]
          (is (= [1 1] (vec (get result "exit_codes")))))))))

(deftest connection-errors-fail
  (testing "requests that never get a response fail the benchmark"
    (let [port (server/free-port)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
            (bench/bench "." "refused"
              (command {"urls" [(str "http://127.0.0.1:" port)]})))))))

(deftest urls-command-and-prefix
  (testing "urls are read from urls-command and prefixed with url-prefix"
    (with-server (fn [_ _] 200)
      (fn [url counter]
        (let [port (last (re-find #":(\d+)$" url))]
          (bench/bench "." "from-command"
            (assoc (command {"url-prefix" "http://127.0.0.1"
                             "urls-command" (str "echo :" port)})
              "runs" 1
              "shell" "bash"
              "warmup-runs" 0))
          (is (= 8 (.get counter))))))))

(deftest missing-urls-throws
  (testing "an empty url list is an error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No urls"
          (bench/bench "." "empty"
            (assoc (command {"urls-command" "true"}) "shell" "bash"))))))

(deftest invalid-config-throws
  (testing "requests must be set to a positive integer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alpha.http.requests"
          (bench/bench "." "no-requests"
            {"alpha" {"http" {"urls" ["http://127.0.0.1"]}} "runs" 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alpha.http.concurrency"
          (bench/bench "." "bad-concurrency"
            {"alpha" {"http" {"concurrency" 0
                              "requests" 1
                              "urls" ["http://127.0.0.1"]}}
             "runs" 1}))))
  (testing "urls must be valid http urls"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid url"
          (bench/bench "." "bad-url"
            {"alpha" {"http" {"requests" 1 "urls" ["127.0.0.1:1234"]}}
             "runs" 1})))))
