(ns finefile.http.bench-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finefile.http.bench :as bench])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.net InetSocketAddress)
   (java.util.concurrent Executors)
   (java.util.concurrent.atomic AtomicLong)))

(defn- with-server
  "Starts an HTTP server on a random loopback port and calls (f base-url counter),
   where counter is an AtomicLong of the requests received. The handler is called
   with the request count (starting at 1) and must return a status code."
  [handler f]
  (let [counter (AtomicLong. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [^HttpExchange exchange exchange
                status (handler (.incrementAndGet counter))]
            (.sendResponseHeaders exchange status -1)
            (.close exchange)))))
    (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) counter)
      (finally
        (.stop server 0)))))

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
    (with-server (constantly 200)
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
    (with-server (constantly 200)
      (fn [url counter]
        (bench/bench "." "cycle"
          (assoc (command {"urls" [(str url "/a") (str url "/b") (str url "/c")]
                           "concurrency" 1
                           "requests" 3})
            "runs" 1
            "warmup-runs" 0))
        (is (= 3 (.get counter)))))))

(deftest urls-command-and-prefix
  (testing "urls are read from urls-command and prefixed with url-prefix"
    (with-server (constantly 200)
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
