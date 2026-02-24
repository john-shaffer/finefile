(ns finefile.http.bench
  (:require
   [finefile.stats :as stats]
   [hato.client :as hc])
  (:import
   (java.util.concurrent Executors Semaphore)))

(defn bench
  [command-name
   {:strs [runs warmup-runs]}
   {:keys [concurrency requests url]}]
  (let [semaphore (Semaphore. concurrency)
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
                     :timeout 30000
                     :url url}
        run-f (fn [executor]
                (with-open [executor executor]
                  (dotimes [_ requests]
                    (.execute executor
                      (fn []
                        (.acquire semaphore)
                        (try
                          (let [{:keys [body]} (hc/request request-map)]
                            (slurp body))
                          (finally
                            (.release semaphore))))))))]
    (dotimes [_ warmup-runs]
      (run-f (Executors/newVirtualThreadPerTaskExecutor)))
    (dotimes [i runs]
      (let [executor (Executors/newVirtualThreadPerTaskExecutor)
            start (System/nanoTime)]
        (run-f executor)
        (aset times i (* 0.000000001 (- (System/nanoTime) start)))))
    (merge (stats/time-stats times)
      {"command" command-name
       "exit_codes" exit-codes
       "times" times})))
