(ns finefile.http.bench
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [finefile.stats :as stats]
   [finefile.util :as u])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest HttpResponse$BodyHandlers)
   (java.time Duration)
   (java.util.concurrent ExecutorService Executors Semaphore)
   (java.util.concurrent.atomic AtomicLong)))

(set! *warn-on-reflection* true)

(def ^:private ^:const default-concurrency 1)
(def ^:private ^:const default-runs 10)

(def ^:private connect-timeout (Duration/ofMillis 30000))
(def ^:private request-timeout (Duration/ofMillis 30000))

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

(defn- check-positive! [command-name k v]
  (when-not (and (integer? v) (pos? v))
    (throw (ex-info (str k " must be a positive integer for " (pr-str command-name)
                      ", got " (pr-str v))
             {:command-name command-name :key k :value v}))))

(defn- resolve-urls [base-dir command-name {:as command :strs [alpha dir]}]
  (let [{:strs [url-prefix urls urls-command]} (get alpha "http")
        urls (cond
               (seq urls)
               urls

               (seq urls-command)
               (let [cmd-dir (str (fs/path base-dir (or dir ".")))
                     env (u/command-env command)
                     shell (u/command-shell command)
                     lines (apply u/exec-lines
                             {:dir cmd-dir
                              :env env
                              :err :inherit}
                             (concat
                               (when shell
                                 [shell "-c"])
                               [urls-command]))]
                 (into [] (keep #(when-not (str/blank? %) (str/trim %))) lines))

               :else (throw (ex-info (str "No urls or urls-command found for " (pr-str command-name))
                              {:command command})))
        urls (if (seq url-prefix)
               (mapv (partial str url-prefix) urls)
               urls)]
    (when (empty? urls)
      (throw (ex-info (str "No urls to benchmark for " (pr-str command-name))
               {:command command})))
    urls))

(defn- url->request [command-name url]
  (try
    (-> (HttpRequest/newBuilder)
        (.uri (URI/create url))
        (.timeout request-timeout)
        (.GET)
        (.build))
    (catch Exception e
      (throw (ex-info (str "Invalid url " (pr-str url) " for " (pr-str command-name)
                        ": " (ex-message e))
               {:command-name command-name :url url}
               e)))))

(defn bench
  [base-dir
   command-name
   {:as command :strs [alpha runs warmup-runs]}]
  (let [{:strs [concurrency requests]} (get alpha "http")
        concurrency (or concurrency default-concurrency)
        runs (or runs default-runs)
        warmup-runs (or warmup-runs 0)
        _ (check-positive! command-name "alpha.http.concurrency" concurrency)
        _ (check-positive! command-name "alpha.http.requests" requests)
        _ (check-positive! command-name "runs" runs)
        semaphore (Semaphore. concurrency)
        exit-codes (int-array runs)
        times (double-array runs)
        ; Requests are immutable and reusable, so we build them all up front
        ; instead of parsing urls inside the timed loop.
        requests-arr (object-array (map (partial url->request command-name)
                                     (resolve-urls base-dir command-name command)))
        request-ct (alength requests-arr)
        request-idx (AtomicLong. 0)
        next-request (fn []
                       (aget requests-arr
                         (rem (.getAndIncrement request-idx) request-ct)))]
    (println (str "Benchmark: " command-name))
    (with-open [^HttpClient http-client (-> (HttpClient/newBuilder)
                                            (.connectTimeout connect-timeout)
                                            (.followRedirects HttpClient$Redirect/NEVER)
                                            (.version HttpClient$Version/HTTP_2)
                                            (.build))]
      (let [run-f (fn [^ExecutorService executor]
                    (with-open [executor executor]
                      (dotimes [_ requests]
                        (.execute executor
                          (fn []
                            (.acquire semaphore)
                            (try
                              (.send http-client ^HttpRequest (next-request)
                                (HttpResponse$BodyHandlers/discarding))
                              (finally
                                (.release semaphore))))))))]
        (dotimes [_ warmup-runs]
          (run-f (Executors/newVirtualThreadPerTaskExecutor)))
        (dotimes [i runs]
          (let [executor (Executors/newVirtualThreadPerTaskExecutor)
                start (System/nanoTime)]
            (run-f executor)
            (aset times i (* 0.000000001 (- (System/nanoTime) start)))))))
    (let [result (merge (stats/time-stats times)
                   {"command" command-name
                    "exit_codes" exit-codes
                    "times" times})]
      (print-stats result runs)
      (println)
      result)))
