(ns finefile.http.bench
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [finefile.stats :as stats]
   [finefile.util :as u])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest HttpResponse HttpResponse$BodyHandlers)
   (java.time Duration)
   (java.util.concurrent ConcurrentHashMap ExecutorService Executors Semaphore)
   (java.util.concurrent.atomic AtomicLong LongAdder)
   (java.util.function Function)))

(set! *warn-on-reflection* true)

(def ^:private ^:const default-concurrency 1)
(def ^:private ^:const default-runs 10)
(def ^:private ^:const max-reported-errors 5)

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

(defn- status-ok-fn
  "Returns a predicate that is true for response status codes that count as a
   success. Any 2xx status is a success unless expected-status is given."
  [expected-status]
  (let [expected (cond
                   (integer? expected-status) #{(long expected-status)}
                   (seq expected-status) (into #{} (map long) expected-status))]
    (if expected
      (fn [status] (contains? expected (long status)))
      (fn [status] (<= 200 (long status) 299)))))

(defn- inc-count! [^ConcurrentHashMap counts k]
  (.increment
    ^LongAdder (.computeIfAbsent counts k
                 (reify Function
                   (apply [_ _] (LongAdder.))))))

(defn- counts->map [^ConcurrentHashMap counts]
  (persistent!
    (reduce (fn [m e] (assoc! m (key e) (.sum ^LongAdder (val e))))
      (transient {}) counts)))

(defn- error-label
  "Returns a short description of t, used to group errors in the failure report."
  [^Throwable t]
  ; Exceptions from HttpClient are often empty wrappers, e.g. a ConnectException
  ; whose cause holds the "Connection refused" message.
  (let [causes (take 10 (take-while some? (iterate ex-cause t)))
        msg (some #(let [m (ex-message %)] (when-not (str/blank? m) m)) causes)
        class-name (.getSimpleName (class t))]
    (cond
      msg (str class-name ": " msg)
      (next causes) (str class-name " (" (.getSimpleName (class (last causes))) ")")
      :else class-name)))

(defn- run-requests!
  "Sends `requests` requests, at most `concurrency` of them in flight at a time.

   Returns a map of {:errors {label count}, :statuses {status-code count}}."
  [{:keys [^HttpClient http-client next-request requests ^Semaphore semaphore]}]
  (let [errors (ConcurrentHashMap.)
        statuses (ConcurrentHashMap.)]
    (with-open [^ExecutorService executor (Executors/newVirtualThreadPerTaskExecutor)]
      (dotimes [_ requests]
        (.execute executor
          (fn []
            (try
              (.acquire semaphore)
              (try
                (let [^HttpRequest request (next-request)
                      ^HttpResponse response (.send http-client request
                                               (HttpResponse$BodyHandlers/discarding))]
                  (inc-count! statuses (.statusCode response)))
                (catch Throwable t
                  (inc-count! errors (error-label t)))
                (finally
                  (.release semaphore)))
              (catch InterruptedException _
                ; The benchmark is being cancelled, e.g. because it timed out.
                (.interrupt (Thread/currentThread))))))))
    {:errors (counts->map errors)
     :statuses (counts->map statuses)}))

(defn- failure-count [{:keys [errors statuses]} status-ok?]
  (reduce +
    (concat
      (vals errors)
      (keep (fn [[status ct]] (when-not (status-ok? status) ct)) statuses))))

(defn- print-failures [{:keys [errors statuses]} status-ok?]
  (doseq [[status ct] (sort-by key statuses)
          :when (not (status-ok? status))]
    (println (format "    HTTP %s: %d" status ct)))
  (let [errors (sort-by (comp - val) errors)]
    (doseq [[label ct] (take max-reported-errors errors)]
      (println (format "    %s: %d" label ct)))
    (when-let [more (seq (drop max-reported-errors errors))]
      (println (format "    … and %d more error kinds" (count more))))))

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
  (let [{:strs [concurrency expected-status ignore-failure requests]} (get alpha "http")
        concurrency (or concurrency default-concurrency)
        runs (or runs default-runs)
        warmup-runs (or warmup-runs 0)
        _ (check-positive! command-name "alpha.http.concurrency" concurrency)
        _ (check-positive! command-name "alpha.http.requests" requests)
        _ (check-positive! command-name "runs" runs)
        status-ok? (status-ok-fn expected-status)
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
      (let [run! #(run-requests!
                    {:http-client http-client
                     :next-request next-request
                     :requests requests
                     :semaphore semaphore})
            check-run! (fn [result]
                         (let [failures (failure-count result status-ok?)]
                           (when (pos? failures)
                             (println (format "  %d of %d requests failed" failures requests))
                             (print-failures result status-ok?)
                             (when-not ignore-failure
                               (throw (ex-info
                                        (format "%d of %d requests failed for %s"
                                          failures requests (pr-str command-name))
                                        {:command-name command-name
                                         :errors (:errors result)
                                         :statuses (:statuses result)}))))
                           failures))]
        (dotimes [_ warmup-runs]
          (check-run! (run!)))
        (dotimes [i runs]
          (let [start (System/nanoTime)
                result (run!)
                elapsed (- (System/nanoTime) start)]
            (aset times i (* 0.000000001 elapsed))
            (aset-int exit-codes i (if (pos? (check-run! result)) 1 0))))))
    (let [result (merge (stats/time-stats times)
                   {"command" command-name
                    "exit_codes" exit-codes
                    "times" times})]
      (print-stats result runs)
      (println)
      result)))
