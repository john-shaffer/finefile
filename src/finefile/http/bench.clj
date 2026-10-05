(ns finefile.http.bench
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [finefile.stats :as stats]
   [finefile.util :as u])
  (:import
   (finefile.http LoadGenerator LoadGenerator$Result)
   (java.util List)))

(set! *warn-on-reflection* true)

(def ^:private ^:const default-concurrency 1)
(def ^:private ^:const default-runs 10)
(def ^:private ^:const max-reported-errors 5)

(def ^:private ^:const connect-timeout-ms 30000)
(def ^:private ^:const read-timeout-ms 30000)

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

(defn- run-requests!
  "Sends `requests` requests through load-generator.

   Returns a map of {:errors {label count}, :statuses {status-code count}}."
  [^LoadGenerator load-generator requests]
  (let [^LoadGenerator$Result result (.run load-generator (long requests))]
    {:errors (into {} (.errors result))
     :statuses (into {} (.statuses result))}))

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

(defn- load-generator [command-name urls concurrency]
  (try
    (LoadGenerator. ^List urls (int concurrency)
      (int connect-timeout-ms) (int read-timeout-ms))
    (catch IllegalArgumentException e
      (throw (ex-info (str (ex-message e) " for " (pr-str command-name))
               {:command-name command-name :urls urls}
               e)))))

(defn open-session
  "Validates command and prepares everything needed to run timed batches of its
   requests. Returns a session to pass to run-once!, which must be handed to
   close-session! when finished.

   Splitting this out lets a caller drive several commands at once, which is
   what interleaving a comparison requires."
  [base-dir command-name {:as command :strs [alpha]}]
  (let [{:strs [concurrency expected-status ignore-failure requests]} (get alpha "http")
        concurrency (or concurrency default-concurrency)
        _ (u/check-positive! command-name "alpha.http.concurrency" concurrency)
        _ (u/check-positive! command-name "alpha.http.requests" requests)
        status-ok? (status-ok-fn expected-status)
        ; Built last so that nothing between here and the return can throw and
        ; leak its connections.
        load-generator (load-generator command-name
                         (resolve-urls base-dir command-name command)
                         concurrency)]
    {:command-name command-name
     :ignore-failure ignore-failure
     :load-generator load-generator
     :requests requests
     :run-requests! #(run-requests! load-generator requests)
     :status-ok? status-ok?}))

(defn close-session! [{:keys [^LoadGenerator load-generator]}]
  (.close load-generator))

(defn run-once!
  "Runs and times one batch of requests. Returns {:failures n, :seconds t}.

   Throws when any request failed, unless alpha.http.ignore-failure is set, so
   that a failing status code or a connection error stops the run instead of
   being timed as if it were a success."
  [{:keys [command-name ignore-failure requests run-requests! status-ok?]}]
  (let [start (System/nanoTime)
        result (run-requests!)
        elapsed (- (System/nanoTime) start)
        failures (failure-count result status-ok?)]
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
    {:failures failures
     :seconds (* 0.000000001 elapsed)}))

(defn bench
  [base-dir
   command-name
   {:as command :strs [runs warmup-runs]}]
  (let [runs (or runs default-runs)
        warmup-runs (or warmup-runs 0)
        _ (u/check-positive! command-name "runs" runs)
        exit-codes (int-array runs)
        times (double-array runs)
        session (open-session base-dir command-name command)]
    (println (str "Benchmark: " command-name))
    (try
      (dotimes [_ warmup-runs]
        (run-once! session))
      (dotimes [i runs]
        (let [{:keys [failures seconds]} (run-once! session)]
          (aset times i (double seconds))
          (aset-int exit-codes i (if (pos? failures) 1 0))))
      (finally
        (close-session! session)))
    (let [result (merge (stats/time-stats times)
                   {"command" command-name
                    "exit_codes" exit-codes
                    "times" times})]
      (print-stats result runs)
      (println)
      result)))
