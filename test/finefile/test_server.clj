(ns finefile.test-server
  "An in-process HTTP server, so that tests run in milliseconds instead of
   needing something listening on a well-known port."
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.io BufferedReader InputStreamReader)
   (java.net InetAddress InetSocketAddress ServerSocket Socket SocketException)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent Executors)
   (java.util.concurrent.atomic AtomicLong)))

(defn with-server
  "Starts an HTTP server on a random loopback port and calls (f base-url counter),
   where counter is an AtomicLong of the requests received. handler is called
   with the exchange and the request count (starting at 1) and must return a
   status code, or a map of :status and :body, which is sent with a
   Content-Length unless :chunked is true."
  [handler f]
  (let [counter (AtomicLong. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [^HttpExchange exchange exchange
                response (handler exchange (.incrementAndGet counter))
                {:keys [body chunked status]} (if (map? response)
                                                response
                                                {:status response})
                ^bytes body (some-> ^String body (.getBytes StandardCharsets/UTF_8))]
            (.sendResponseHeaders exchange (int status)
              (cond chunked 0 body (alength body) :else -1))
            (when body
              (with-open [out (.getResponseBody exchange)]
                (.write out body)))
            (.close exchange)))))
    (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) counter)
      (finally
        (.stop server 0)))))

(defn path [^HttpExchange exchange]
  (.getPath (.getRequestURI exchange)))

(defn free-port
  "Returns a port with nothing listening on it, for testing connection errors.
   Binds a port and immediately releases it."
  []
  (with-server (fn [_ _] 200)
    (fn [url _] (last (re-find #":(\d+)$" url)))))

(defn- read-request-head
  "Reads one request's line and headers from in, returning false at end of
   stream."
  [^BufferedReader in]
  (loop [first? true]
    (let [line (.readLine in)]
      (cond
        (nil? line) false
        (and (= "" line) (not first?)) true
        :else (recur false)))))

(defn with-raw-server
  "Starts a bare TCP server on a random loopback port, for responses that
   HttpServer cannot be made to send. Calls (f base-url accepts), where accepts
   is an AtomicLong of the connections accepted.

   For each connection, (respond n) is called once per request with the
   connection's request count (starting at 1) and returns the raw response
   string, or a map of :response and :close, which closes the connection after
   writing the response."
  [respond f]
  (let [accepts (AtomicLong. 0)
        server (ServerSocket. 0 1024 (InetAddress/getLoopbackAddress))
        executor (Executors/newVirtualThreadPerTaskExecutor)
        serve (fn [^Socket socket]
                (with-open [socket socket]
                  (let [in (BufferedReader.
                             (InputStreamReader. (.getInputStream socket)
                               StandardCharsets/ISO_8859_1))
                        out (.getOutputStream socket)]
                    (loop [n 1]
                      (when (read-request-head in)
                        (let [r (respond n)
                              {:keys [close response]} (if (map? r) r {:response r})]
                          (.write out (.getBytes ^String response StandardCharsets/ISO_8859_1))
                          (.flush out)
                          (when-not close
                            (recur (inc n)))))))))]
    (.execute executor
      (fn []
        (try
          (loop []
            (let [socket (.accept server)]
              (.incrementAndGet accepts)
              (.execute executor #(try (serve socket) (catch SocketException _) (catch InterruptedException _)))
              (recur)))
          (catch SocketException _))))
    (try
      (f (str "http://127.0.0.1:" (.getLocalPort server)) accepts)
      (finally
        (.close server)
        (.shutdownNow executor)))))
