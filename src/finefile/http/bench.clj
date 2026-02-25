(ns finefile.http.bench
  (:require
   [finefile.stats :as stats]
   [hato.client :as hc])
  (:import
   (java.util.concurrent Executors Semaphore)))

(defn http-command? [command]
  (boolean (seq (get-in command ["alpha" "http" "urls"]))))

(defn- format-time [seconds]
  (cond
    (>= seconds 1.0) (format "%.3f s" seconds)
    (>= seconds 0.001) (format "%.1f ms" (* 1000.0 seconds))
    :else (format "%.1f µs" (* 1000000.0 seconds))))

(defn- print-stats [{:strs [mean stddev min max]} runs]
  (if stddev
    (println (format "  Time (mean ± σ):     %s ± %s"
               (format-time mean) (format-time stddev)))
    (println (format "  Time (mean):         %s" (format-time mean))))
  (println (format "  Range (min … max):   %s … %s    %d runs"
             (format-time min) (format-time max) runs)))

(defn bench
  [command-name
   {:strs [alpha runs warmup-runs]}]
  (let [{:strs [concurrency requests url-prefix urls]} (get alpha "http")
        semaphore (Semaphore. concurrency)
        exit-codes (int-array runs)
        times (double-array runs)
        http-client (hc/build-http-client
                      {:connect-timeout 30000
                       :redirect-policy :never
                       :version :http-2})
        request-map {:as :stream
                     :http-client http-client
                     :method :get
                     :throw-exceptions? false
                     :timeout 30000}
        urls (if (seq url-prefix)
               (mapv (partial str url-prefix) urls)
               urls)
        urls-atom (atom [nil (cycle urls)])
        pop-url! (fn []
                   (first
                     (swap! urls-atom
                       (fn [[_ url-seq]]
                         [(first url-seq) (rest url-seq)]))))
        run-f (fn [executor]
                (with-open [executor executor]
                  (dotimes [_ requests]
                    (.execute executor
                      (fn []
                        (.acquire semaphore)
                        (try
                          (let [request-map (assoc request-map :url (pop-url!))
                                {:keys [body]} (hc/request request-map)]
                            (slurp body))
                          (finally
                            (.release semaphore))))))))]
    (println (str "Benchmark: " command-name))
    (dotimes [_ warmup-runs]
      (run-f (Executors/newVirtualThreadPerTaskExecutor)))
    (dotimes [i runs]
      (let [executor (Executors/newVirtualThreadPerTaskExecutor)
            start (System/nanoTime)]
        (run-f executor)
        (aset times i (* 0.000000001 (- (System/nanoTime) start)))))
    (let [result (merge (stats/time-stats times)
                   {"command" command-name
                    "exit_codes" exit-codes
                    "times" times})]
      (print-stats result runs)
      (println)
      result)))
