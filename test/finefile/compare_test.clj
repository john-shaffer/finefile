(ns finefile.compare-test
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [finefile.compare :as cmp]
   [finefile.stats :as stats]
   [finefile.test-server :as server]))

(defn- summary [ds]
  (stats/paired-comparison ds {:min-batch 1}))

(deftest verdict-without-a-margin-uses-the-bayes-factor
  (testing "stops once the posterior clears the certainty threshold"
    (is (= :different (cmp/verdict {:p-different 0.995} {:certainty 0.99})))
    (is (= :different (cmp/verdict {:p-different 0.99} {:certainty 0.99}))))
  (testing "stops just as readily on the evidence that they are the same"
    (is (= :indistinguishable (cmp/verdict {:p-different 0.004} {:certainty 0.99})))
    (is (= :indistinguishable (cmp/verdict {:p-different 0.01} {:certainty 0.99}))))
  (testing "keeps going in between"
    (is (nil? (cmp/verdict {:p-different 0.5} {:certainty 0.99})))
    (is (nil? (cmp/verdict {:p-different 0.98} {:certainty 0.99})))
    (is (nil? (cmp/verdict {:p-different 0.02} {:certainty 0.99}))))
  (testing "a lower certainty stops sooner"
    (is (= :different (cmp/verdict {:p-different 0.96} {:certainty 0.95})))
    (is (nil? (cmp/verdict {:p-different 0.96} {:certainty 0.99})))))

(deftest verdict-uses-practical-significance-when-asked
  (let [opts {:certainty 0.99 :min-effect 0.01}]
    (testing "a difference too small to care about is called indistinguishable"
      (is (= :indistinguishable
            (cmp/verdict {:p-different 1.0 :p-practical 0.001} opts))))
    (testing "and a large one is still called different"
      (is (= :different
            (cmp/verdict {:p-different 1.0 :p-practical 0.999} opts))))
    (testing "min-effect of zero falls back to the point null"
      (is (= :different
            (cmp/verdict {:p-different 0.999 :p-practical 0.001}
              {:certainty 0.99 :min-effect 0}))))))

(deftest verdict-on-real-summaries
  (testing "two rounds are never enough to be certain, however large the effect"
    ; With one degree of freedom the posterior is heavy tailed, so min-rounds
    ; of 2 cannot conclude by accident.
    (is (nil? (cmp/verdict (summary [1.79 1.81]) {:certainty 0.99}))))
  (testing "a few more rounds of the same effect are"
    (is (= :different
          (cmp/verdict (summary [1.79 1.81 1.80 1.78]) {:certainty 0.99}))))
  (testing "noise well inside the margin settles the other way"
    (let [ds (map #(* 2.0e-3 (Math/cos (* 1.7 (double %)))) (range 8))]
      (is (= :indistinguishable
            (cmp/verdict (stats/paired-comparison ds {:min-batch 1 :min-effect 0.01})
              {:certainty 0.99 :min-effect 0.01})))))
  (testing "but the point null alone cannot reach that conclusion"
    ; Evidence for a measure-zero hypothesis only grows as the square root of
    ; the round count, so this needs thousands of rounds rather than eight.
    (let [ds (map #(* 2.0e-3 (Math/cos (* 1.7 (double %)))) (range 8))]
      (is (nil? (cmp/verdict (summary ds) {:certainty 0.99}))))))

(deftest describe-ratio-names-the-faster-side
  (is (= "a is 25.0% faster than b" (cmp/describe-ratio "a" "b" 1.25)))
  (is (= "b is 25.0% faster than a" (cmp/describe-ratio "a" "b" 0.8)))
  (is (= "a is 0.0% faster than b" (cmp/describe-ratio "a" "b" 1.0))))

(deftest interleave-orders-are-balanced
  (testing "each side runs the same number of times per round"
    (doseq [[_mode orders] cmp/interleave-orders
            order orders]
      (is (= (count (filter #{:a} order)) (count (filter #{:b} order))))))
  (testing "the two rounds swap which side leads, so drift cancels"
    (doseq [[_mode [even odd]] cmp/interleave-orders]
      (is (= even (mapv {:a :b :b :a} odd))))))

;; ---------------------------------------------------------------------------
;; Integration. The server answers /slow after a short sleep, so the two sides
;; differ by a wide, reliable margin without any test taking long to run.

(def ^:private ^:const slow-millis 3)

(defn- slow-handler [exchange _]
  (when (= "/slow" (server/path exchange))
    (Thread/sleep slow-millis))
  200)

(defn- command [url]
  {"alpha" {"http" {"concurrency" 1 "requests" 2 "urls" [url]}}})

(defn- comparison [m]
  ; batch-rounds 1 keeps these tests to a handful of rounds. The batch floor
  ; that a real comparison uses is covered by correlated-rounds-are-batched.
  (merge {"a" "fast" "b" "slow" "batch-rounds" 1
          "max-rounds" 8 "min-rounds" 2 "warmup-runs" 0}
    m))

(defn- run-compare! [cmp commands]
  (let [result (volatile! nil)
        out (with-out-str (vreset! result (cmp/compare! "." "c" cmp commands)))]
    [@result out]))

(deftest stops-early-on-an-obvious-difference
  (server/with-server slow-handler
    (fn [url _]
      (let [[result out] (run-compare! (comparison {})
                           {"fast" (command (str url "/fast"))
                            "slow" (command (str url "/slow"))})
            c (first (get result "comparisons"))]
        (is (= "different" (get c "verdict")))
        (testing "without needing anywhere near max-rounds"
          (is (< (get c "rounds") 8)))
        (testing "and reports the fast side as faster"
          (is (< 1.0 (get c "throughput_ratio")))
          (is (str/includes? out "fast is ")))
        (testing "having cleared the certainty it stopped on"
          ; Not the credible interval: the rule stops on a directional tail
          ; probability, which an interval at these round counts need not
          ; reflect, since two rounds leave only one degree of freedom.
          (is (<= (get c "certainty") (get c "p_practical"))))
        (testing "and brackets the estimate with a credible interval"
          (let [[lo hi] (get c "throughput_ratio_ci")]
            (is (< lo (get c "throughput_ratio") hi))))))))

(deftest reports-a-round-at-a-time
  (server/with-server slow-handler
    (fn [url _]
      (let [[result out] (run-compare! (comparison {})
                           {"fast" (command (str url "/fast"))
                            "slow" (command (str url "/slow"))})
            c (first (get result "comparisons"))
            rounds (get c "rounds")]
        (testing "one progress line per round, from the second on"
          ; Round one has no spread to summarize, and long comparisons throttle
          ; their output, but this one is well under that threshold.
          (is (< rounds 10))
          (is (= (dec rounds) (count (re-seq #"(?m)^  round " out)))))
        (testing "and one running probability per round"
          (is (= rounds (alength ^doubles (get c "p_different_by_round"))))
          (is (every? #(<= 0.0 (double %) 1.0)
                (get c "p_different_by_round"))))))))

(deftest runs-both-sides-equally-often
  (server/with-server (fn [_ _] 200)
    (fn [url counter]
      (let [[result _] (run-compare!
                         (comparison {"max-rounds" 2 "warmup-runs" 1})
                         {"fast" (command url) "slow" (command url)})
            [a b] (get result "results")]
        (is (= (alength ^doubles (get a "times")) (alength ^doubles (get b "times"))))
        (testing "abba gives two runs per side per round"
          (is (= 4 (alength ^doubles (get a "times")))))
        (testing "warmup runs happen but are not recorded"
          ; (1 warmup + 4 timed) runs per side, 2 requests each.
          (is (= 20 (.get counter))))))))

(deftest alternate-halves-the-runs-per-round
  (server/with-server (fn [_ _] 200)
    (fn [url _]
      (let [[result _] (run-compare!
                         (comparison {"interleave" "alternate" "max-rounds" 2})
                         {"fast" (command url) "slow" (command url)})
            [a _] (get result "results")]
        (is (= 2 (alength ^doubles (get a "times"))))))))

(deftest gives-up-at-max-rounds
  (server/with-server (fn [_ _] 200)
    (fn [url _]
      (let [[result out] (run-compare!
                           ; Two runs of the same command, judged against the
                           ; point null, which cannot be accepted in three
                           ; rounds no matter how alike they turn out to be.
                           (comparison {"max-rounds" 3 "min-effect" 0})
                           {"fast" (command url) "slow" (command url)})
            c (first (get result "comparisons"))]
        (is (= "max-rounds" (get c "verdict")))
        (is (= 3 (get c "rounds")))
        (is (str/includes? out "Inconclusive"))))))

(deftest timeout-ends-the-comparison
  (server/with-server slow-handler
    (fn [url _]
      (let [[result out] (run-compare!
                           ; A zero deadline has already passed, so this stops
                           ; at the first round that can be summarized.
                           (comparison {"max-rounds" 200
                                        "min-effect" 0
                                        "timeout-seconds" 0})
                           {"fast" (command (str url "/slow"))
                            "slow" (command (str url "/slow"))})
            c (first (get result "comparisons"))]
        (is (= "timeout" (get c "verdict")))
        (is (str/includes? out "timed out"))))))

;; ---------------------------------------------------------------------------
;; Failures must stop a comparison rather than be timed as if they succeeded.

(deftest a-failing-status-stops-the-comparison
  (server/with-server (fn [exchange _]
                        (if (= "/bad" (server/path exchange)) 500 200))
    (fn [url _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
            (run-compare! (comparison {})
              {"fast" (command (str url "/ok"))
               "slow" (command (str url "/bad"))}))))))

(deftest a-failure-during-warmup-stops-the-comparison
  (server/with-server (fn [_ _] 503)
    (fn [url _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
            (run-compare! (comparison {"warmup-runs" 1})
              {"fast" (command url) "slow" (command url)}))))))

(deftest a-connection-error-stops-the-comparison
  (server/with-server (fn [_ _] 200)
    (fn [url _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requests failed"
            (run-compare! (comparison {})
              {"fast" (command url)
               "slow" (command (str "http://127.0.0.1:" (server/free-port)))}))))))

(deftest ignore-failure-lets-a-comparison-continue
  (server/with-server (fn [_ n] (if (odd? n) 200 503))
    (fn [url _]
      (let [flaky (assoc-in (command url) ["alpha" "http" "ignore-failure"] true)
            [result _] (run-compare! (comparison {"max-rounds" 2})
                         {"fast" flaky "slow" flaky})
            c (first (get result "comparisons"))]
        (is (= 2 (get c "rounds")))
        (testing "and the failures are recorded as non-zero exit codes"
          (is (some pos? (mapcat #(get % "exit_codes") (get result "results")))))))))

;; ---------------------------------------------------------------------------
;; Configuration errors.

(deftest rejects-invalid-configuration
  (let [commands {"fast" (command "http://127.0.0.1:1")
                  "plain" {"command" "true"}}
        fails (fn [re cmp]
                (is (thrown-with-msg? clojure.lang.ExceptionInfo re
                      (cmp/compare! "." "c" (comparison cmp) commands))))]
    (fails #"undefined command" {"b" "nope"})
    (fails #"not an alpha.http command" {"b" "plain"})
    (fails #"certainty must be between" {"b" "fast" "certainty" 1.5})
    (fails #"credible-mass must be between" {"b" "fast" "credible-mass" 0.4})
    (fails #"batch-rounds must be an integer of at least 1" {"b" "fast" "batch-rounds" 0})
    (fails #"min-rounds must be an integer of at least 2" {"b" "fast" "min-rounds" 1})
    (fails #"max-rounds must be an integer of at least 2" {"b" "fast" "max-rounds" 1})
    (testing "and the round floor follows the batch size"
      (fails #"min-rounds must be an integer of at least 8"
        {"b" "fast" "batch-rounds" 4 "min-rounds" 6}))
    (fails #"max-rounds must be at least min-rounds"
      {"b" "fast" "max-rounds" 2 "min-rounds" 5})
    (fails #"interleave must be one of" {"b" "fast" "interleave" "shuffle"})
    (fails #"prior-scale must be positive" {"b" "fast" "prior-scale" 0})
    (fails #"min-effect must not be negative" {"b" "fast" "min-effect" -0.1})
    (fails #"warmup-runs must not be negative" {"b" "fast" "warmup-runs" -1})))

;; ---------------------------------------------------------------------------
;; JSON output.

(deftest results-serialize-to-json
  (server/with-server slow-handler
    (fn [url _]
      (let [[result _] (run-compare! (comparison {"min-effect" 0.01})
                         {"fast" (command (str url "/fast"))
                          "slow" (command (str url "/slow"))})
            round-tripped (-> result (json/write-str) (json/read-str))
            c (first (get round-tripped "comparisons"))]
        (testing "the comparison carries everything needed to reproduce it"
          (is (= "fast" (get c "a")))
          (is (= "slow" (get c "b")))
          (is (= "c" (get c "name")))
          (is (= "different" (get c "verdict")))
          (is (= "abba" (get c "interleave")))
          (is (= 0.99 (get c "certainty")))
          (is (= 0.01 (get c "min_effect")))
          (is (= 0.707 (get c "prior_scale"))))
        (testing "and the evidence, as a log so that it cannot overflow"
          (is (Double/isFinite (double (get c "log_bf10"))))
          ; The stopping rule runs on p_practical. The point-null p_different
          ; is the more conservative of the two and can still be undecided
          ; here, so only the one that was stopped on is pinned down.
          (is (<= 0.0 (get c "p_different") 1.0))
          (is (< 0.99 (get c "p_practical")))
          (is (= (get c "rounds") (count (get c "log_throughput_ratio_differences")))))
        (testing "every number survives the round trip as a finite number"
          (let [numbers (->> (tree-seq coll? seq c)
                          (filter number?)
                          (map double))]
            (is (seq numbers))
            (is (every? #(Double/isFinite (double %)) numbers))))
        (testing "the per-side timings stay in hyperfine's result format"
          (let [[a b] (get round-tripped "results")]
            (is (= "fast" (get a "command")))
            (is (= "slow" (get b "command")))
            (is (every? #(contains? a %) ["mean" "median" "min" "max" "stddev"
                                          "times" "exit_codes"]))
            (is (every? pos? (get a "times")))
            (is (= (count (get a "times")) (count (get a "exit_codes"))))))))))

(deftest an-overwhelming-bayes-factor-still-serializes
  ; bf10 itself overflows to infinity, which json/write-str would happily
  ; emit as an unparseable literal.
  (let [{:keys [bf10 log-bf10]} (stats/paired-comparison (repeat 60 0.5) {:min-batch 1})]
    (is (Double/isInfinite bf10))
    (is (Double/isFinite log-bf10))
    (is (= {"log_bf10" log-bf10}
          (json/read-str (json/write-str {"log_bf10" log-bf10}))))))
