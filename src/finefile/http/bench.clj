(ns finefile.http.bench
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [finefile.stats :as stats]
   [finefile.util :as u]
   [hato.client :as hc])
  (:import
   (java.util.concurrent ExecutorService Executors Semaphore)))

(set! *warn-on-reflection* true)

(defn http-command? [command]
  (let [{:strs [urls urls-command]} (get-in command ["alpha" "http"])]
    (boolean (or (seq urls) (seq urls-command)))))

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
  [base-dir
   command-name
   {:as command :strs [alpha dir runs warmup-runs]}]
  (let [{:strs [concurrency requests url-prefix urls urls-command]} (get alpha "http")
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
        urls (cond
               (seq urls)
               urls

               (seq urls-command)
               (let [cmd-dir (str (fs/path base-dir (or dir ".")))
                     env (u/command-env command)
                     shell (u/command-shell command)
                     p (apply u/interruptible-exec
                         {:dir cmd-dir
                          :env env
                          :err :inherit
                          :out :pipe}
                         (concat
                           (when shell
                             [shell "-c"])
                           [urls-command]))]
                 (with-open [rdr (-> p p/stdout io/reader)]
                   (->> rdr
                     line-seq
                     (keep
                       (fn [s]
                         (when-not (str/blank? s)
                           (str/trim s))))
                     ; Realize all values
                     vec)))

               :else (throw (ex-info (str "No urls or urls-command found for " (pr-str command-name))
                              {:command command})))
        urls (if (seq url-prefix)
               (mapv (partial str url-prefix) urls)
               urls)
        urls-atom (atom [nil (cycle urls)])
        pop-url! (fn []
                   (first
                     (swap! urls-atom
                       (fn [[_ url-seq]]
                         [(first url-seq) (rest url-seq)]))))
        run-f (fn [^ExecutorService executor]
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
