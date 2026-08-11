(ns finefile.compare
  "Sequential Bayesian comparison of two http benchmark commands.

   A and B are run interleaved in rounds. After every round we compute the
   posterior probability that their underlying throughputs differ and stop as
   soon as that probability crosses the configured certainty in either
   direction, so an obvious difference costs only a handful of rounds while an
   ambiguous one keeps collecting evidence until it runs out of time.

   See finefile.stats/jzs-log-bf10 for why stopping on a threshold that is
   checked after every round does not inflate the error rate."
  (:require
   [finefile.http.bench :as http-bench]
   [finefile.stats :as stats]
   [finefile.util :as u]))

(set! *warn-on-reflection* true)

(def ^:private ^:const default-certainty 0.99)
(def ^:private ^:const default-credible-mass 0.95)
(def ^:private ^:const default-interleave "abba")
(def ^:private ^:const default-max-rounds 200)

; A point null is a measure-zero hypothesis, so evidence for it accumulates
; only as the square root of the round count: it takes on the order of ten
; thousand rounds for the Bayes factor alone to call two identical commands
; indistinguishable. Asking instead whether they differ by more than some
; margin settles in a handful of rounds, so a margin is on by default and the
; point null is available by setting min-effect to zero.
(def ^:private ^:const default-min-effect 0.01)
(def ^:private ^:const default-min-rounds 5)
(def ^:private ^:const default-prior-scale 0.707)
(def ^:private ^:const default-warmup-runs 3)

(def interleave-orders
  "The order A and B run in within a round, indexed by round parity.

   Each round yields one paired observation, so anything the two commands
   share within a round - a thermal ramp, a noisy neighbour, a cache filling
   up - cancels out of the difference instead of inflating the variance.
   ABBA cancels a drift that is linear across the round, and swapping to BAAB
   on odd rounds cancels the residual curvature across pairs of rounds."
  {"abba" [[:a :b :b :a] [:b :a :a :b]]
   "alternate" [[:a :b] [:b :a]]})

(defn- check-fraction! [cmp-name k v]
  (when-not (and (number? v) (< 0.5 (double v) 1.0))
    (throw (ex-info (str k " must be between 0.5 and 1 for " (pr-str cmp-name)
                      ", got " (pr-str v))
             {:comparison cmp-name :key k :value v}))))

(defn- check-at-least-two! [cmp-name k v]
  ; A single round has no spread to estimate, so two is the floor.
  (when-not (and (integer? v) (<= 2 (long v)))
    (throw (ex-info (str k " must be an integer of at least 2 for "
                      (pr-str cmp-name) ", got " (pr-str v))
             {:comparison cmp-name :key k :value v}))))

(defn- resolve-side! [cmp-name side command-name commands]
  (let [command (get commands command-name)]
    (when-not command
      (throw (ex-info (str side " of comparison " (pr-str cmp-name) " names an "
                        "undefined command: " (pr-str command-name))
               {:comparison cmp-name :command-name command-name})))
    (when-not (http-bench/http-command? command)
      (throw (ex-info (str side " of comparison " (pr-str cmp-name) ", "
                        (pr-str command-name) ", is not an alpha.http command. "
                        "Comparisons only support http benchmarks.")
               {:comparison cmp-name :command-name command-name})))
    command))

(defn- format-percent [x]
  (format "%.1f%%" (* 100.0 (double x))))

(defn describe-ratio
  "Describes a throughput ratio of a to b as a percentage speed difference,
   naming whichever side is faster."
  [a b ratio]
  (if (<= 1.0 (double ratio))
    (format "%s is %s faster than %s" a (format-percent (- (double ratio) 1.0)) b)
    (format "%s is %s faster than %s" b (format-percent (- (/ 1.0 (double ratio)) 1.0)) a)))

(defn verdict
  "Decides whether to stop after a round.

   Returns :different, :indistinguishable, or nil to keep going. With a
   positive min-effect the decision uses the posterior probability that the
   two commands differ by more than that ratio, which answers \"do these
   differ enough to care\". With min-effect at zero it uses the point-null
   Bayes factor, which answers \"do these differ at all\" - a question that can
   be answered quickly in the affirmative but only very slowly in the
   negative, since no finite number of rounds rules a point out."
  [{:keys [p-different p-practical]} {:keys [certainty min-effect]}]
  (let [certainty (double (or certainty default-certainty))
        p (double (if (and min-effect (pos? (double min-effect)))
                    p-practical
                    p-different))]
    (cond
      (<= certainty p) :different
      (<= p (- 1.0 certainty)) :indistinguishable)))

(defn- run-round!
  "Runs one round and returns the accumulated per-side seconds and failures
   with this round's runs appended."
  [sessions order acc]
  (reduce
    (fn [acc k]
      (let [{:keys [failures seconds]} (http-bench/run-once! (get sessions k))]
        (-> acc
          (update-in [k :failures] conj failures)
          (update-in [k :seconds] conj seconds))))
    acc order))

(defn- round-difference
  "Returns the difference in mean log throughput between A and B over the runs
   that the given round contributed.

   Throughput rather than elapsed time, so that A and B can send different
   numbers of requests per run. Logs because benchmark noise is multiplicative
   and because a ratio is what we want to report."
  [sessions acc runs-per-side]
  (let [side (fn [k]
               (let [reqs (double (:requests (get sessions k)))
                     ts (take-last runs-per-side (get-in acc [k :seconds]))]
                 (/ (reduce + (map #(Math/log (/ reqs (double %))) ts))
                   (count ts))))]
    (- (side :a) (side :b))))

; A comparison of two commands that really are alike can run for hundreds of
; rounds, so only the early ones and then every tenth get a line.
(def ^:private ^:const round-log-interval 10)

(defn- log-round? [round]
  (or (<= (long round) round-log-interval)
    (zero? (mod (long round) round-log-interval))))

(defn- print-round [round runs summary opts]
  (let [{:keys [mean p-different p-practical]} summary]
    (println
      (format "  round %3d (%3d runs)   ratio %.4f   P(different) %s%s"
        round runs (Math/exp (double mean)) (format-percent p-different)
        (if p-practical
          (format "   P(> %s) %s"
            (format-percent (:min-effect opts)) (format-percent p-practical))
          "")))))

(defn- print-summary [a b outcome summary opts]
  (let [{:keys [bf10 ci log-bf10 mean n p-different p-practical]} summary
        [lo hi] (mapv #(Math/exp (double %)) ci)
        ratio (Math/exp (double mean))]
    (println (format "  %s: %s"
               (case outcome
                 :different "Different"
                 :indistinguishable "Indistinguishable"
                 :max-rounds "Inconclusive (hit max-rounds)"
                 :timeout "Inconclusive (timed out)")
               (describe-ratio a b ratio)))
    (println (format "  Throughput ratio %s/%s:  %.4f  [%.4f, %.4f] (%s credible)"
               a b ratio lo hi (format-percent (:credible-mass summary))))
    (println (format "  P(different) %s   BF10 %s   after %d rounds"
               (format-percent p-different)
               (if (Double/isFinite (double bf10))
                 (format "%.3g" bf10)
                 (format "e^%.0f" log-bf10))
               n))
    (when p-practical
      (println (format "  P(difference exceeds %s) %s"
                 (format-percent (:min-effect opts)) (format-percent p-practical))))))

(defn- side-result [command-name {:keys [failures seconds]}]
  (let [times (double-array seconds)]
    (merge (stats/time-stats times)
      {"command" command-name
       "exit_codes" (int-array (map #(if (pos? (long %)) 1 0) failures))
       "times" times})))

(defn- comparison-result [cmp-name a b outcome summary ds ps opts]
  (let [{:keys [bf10 ci credible-mass log-bf10 mean n p-different p-practical
                prior-scale stddev]} summary
        [lo hi] ci]
    (cond->
      {"a" a
       "b" b
       "certainty" (:certainty opts)
       "credible_mass" credible-mass
       "interleave" (:interleave opts)
       ; bf10 itself overflows to infinity, which is not valid JSON, so the
       ; log is what gets exported.
       "log_bf10" log-bf10
       "log_throughput_ratio" mean
       "log_throughput_ratio_ci" [lo hi]
       "log_throughput_ratio_stddev" stddev
       "log_throughput_ratio_differences" (double-array ds)
       "name" cmp-name
       "p_different" p-different
       "p_different_by_round" (double-array ps)
       "prior_scale" prior-scale
       "rounds" n
       "throughput_ratio" (Math/exp (double mean))
       "throughput_ratio_ci" [(Math/exp (double lo)) (Math/exp (double hi))]
       "verdict" (name outcome)}
      (Double/isFinite (double bf10)) (assoc "bf10" bf10)
      p-practical (assoc "min_effect" (:min-effect opts)
                    "p_practical" p-practical))))

(defn compare!
  "Runs comparison cmp between two of commands and returns a result map.

   commands must already have defaults merged in. Any failed request aborts
   the comparison unless the command sets alpha.http.ignore-failure."
  [base-dir cmp-name {:as cmp :strs [a b]} commands]
  (let [opts {:certainty (or (get cmp "certainty") default-certainty)
              :credible-mass (or (get cmp "credible-mass") default-credible-mass)
              :interleave (or (get cmp "interleave") default-interleave)
              :min-effect (or (get cmp "min-effect") default-min-effect)
              :prior-scale (or (get cmp "prior-scale") default-prior-scale)}
        {:keys [certainty interleave min-effect prior-scale]} opts
        max-rounds (or (get cmp "max-rounds") default-max-rounds)
        min-rounds (or (get cmp "min-rounds") default-min-rounds)
        timeout-seconds (get cmp "timeout-seconds")
        warmup-runs (or (get cmp "warmup-runs") default-warmup-runs)
        orders (interleave-orders interleave)
        _ (when-not orders
            (throw (ex-info (str "interleave must be one of abba, alternate for "
                              (pr-str cmp-name) ", got " (pr-str interleave))
                     {:comparison cmp-name :value interleave})))
        _ (check-fraction! cmp-name "certainty" certainty)
        _ (check-fraction! cmp-name "credible-mass" (:credible-mass opts))
        _ (check-at-least-two! cmp-name "min-rounds" min-rounds)
        _ (check-at-least-two! cmp-name "max-rounds" max-rounds)
        _ (when (< max-rounds min-rounds)
            (throw (ex-info (str "max-rounds must be at least min-rounds for "
                              (pr-str cmp-name))
                     {:comparison cmp-name})))
        _ (when-not (and (number? prior-scale) (pos? (double prior-scale)))
            (throw (ex-info (str "prior-scale must be positive for " (pr-str cmp-name)
                              ", got " (pr-str prior-scale))
                     {:comparison cmp-name :value prior-scale})))
        _ (when (and min-effect (neg? (double min-effect)))
            (throw (ex-info (str "min-effect must not be negative for "
                              (pr-str cmp-name) ", got " (pr-str min-effect))
                     {:comparison cmp-name :value min-effect})))
        _ (when (and warmup-runs (neg? (long warmup-runs)))
            (throw (ex-info (str "warmup-runs must not be negative for "
                              (pr-str cmp-name) ", got " (pr-str warmup-runs))
                     {:comparison cmp-name :value warmup-runs})))
        command-a (resolve-side! cmp-name "a" a commands)
        command-b (resolve-side! cmp-name "b" b commands)
        runs-per-side (/ (count (first orders)) 2)
        started (System/nanoTime)
        deadline (when timeout-seconds
                   (+ started (* 1000000000 (long timeout-seconds))))
        session-a (http-bench/open-session base-dir a command-a)
        sessions (try
                   {:a session-a
                    :b (http-bench/open-session base-dir b command-b)}
                   (catch Throwable t
                     ; A is already holding a client that nothing else will
                     ; close if B fails to open.
                     (http-bench/close-session! session-a)
                     (throw t)))]
    (println (format "Compare: %s (a = %s, b = %s)" cmp-name a b))
    (try
      ; Warm up both sides before any of it counts, so that JIT compilation,
      ; connection setup and cold caches land outside the measurement.
      (dotimes [_ warmup-runs]
        (http-bench/run-once! (:a sessions))
        (http-bench/run-once! (:b sessions)))
      (loop [round 1
             acc {:a {:failures [] :seconds []} :b {:failures [] :seconds []}}
             ds []
             ps []]
        (let [acc (run-round! sessions (nth orders (mod round 2)) acc)
              ds (conj ds (round-difference sessions acc runs-per-side))
              summary (stats/paired-comparison ds opts)
              ps (conj ps (:p-different summary 0.5))
              runs (+ (count (get-in acc [:a :seconds]))
                     (count (get-in acc [:b :seconds])))
              ; Round one has no spread yet, so it can never be the last one.
              ; min-rounds and max-rounds are both at least 2.
              outcome (when summary
                        (or (when (<= min-rounds round) (verdict summary opts))
                          (when (<= max-rounds round) :max-rounds)
                          (when (and deadline (<= deadline (System/nanoTime)))
                            :timeout)))]
          (when (and summary (log-round? round))
            (print-round round runs summary opts))
          (if outcome
            (do
              (print-summary a b outcome summary opts)
              (println)
              {"comparisons" [(comparison-result cmp-name a b outcome summary ds ps opts)]
               "results" [(side-result a (:a acc)) (side-result b (:b acc))]})
            (recur (inc round) acc ds ps))))
      (finally
        (http-bench/close-session! (:a sessions))
        (http-bench/close-session! (:b sessions))))))
