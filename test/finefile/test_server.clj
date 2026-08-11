(ns finefile.test-server
  "An in-process HTTP server, so that tests run in milliseconds instead of
   needing something listening on a well-known port."
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.net InetSocketAddress)
   (java.util.concurrent Executors)
   (java.util.concurrent.atomic AtomicLong)))

(defn with-server
  "Starts an HTTP server on a random loopback port and calls (f base-url counter),
   where counter is an AtomicLong of the requests received. handler is called
   with the exchange and the request count (starting at 1) and must return a
   status code."
  [handler f]
  (let [counter (AtomicLong. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [^HttpExchange exchange exchange
                status (handler exchange (.incrementAndGet counter))]
            (.sendResponseHeaders exchange (int status) -1)
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
