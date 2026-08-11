(ns finefile.cli-test
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [finefile.cli :as cli]
   [finefile.test-server :as server]))

(defn- bench-cmds! [steps cmds]
  (let [results (volatile! nil)
        out (with-out-str
              (vreset! results
                (doall (cli/bench-cmds {:base-dir "." :cmds cmds :steps steps}))))]
    [@results out]))

(deftest failed-step-is-reported
  (testing "an exception from a step fails only that command"
    (let [[results out] (bench-cmds! #{"setup"}
                          [{:command {"setup" "false" "shell" "none"} :k "failing"}
                           {:command {"setup" "true" "shell" "none"} :k "working"}])]
      (is (= ["failed" "succeeded"] (map :status results)))
      (is (str/includes? out "failing benchmark failed:")))))

(deftest http-bench-runs-only-for-the-command-step
  (testing "the http benchmark is skipped when the command step is not selected"
    ; Nothing is listening on the url, so the benchmark would fail if it ran.
    (let [cmd {:command {"alpha" {"http" {"requests" 1
                                          "urls" ["http://127.0.0.1:1"]}}}
               :k "http"}
          [results out] (bench-cmds! #{"setup"} [cmd])]
      (is (= ["succeeded"] (map :status results)))
      (is (= "" out)))))

(defn- compare-comparisons! [commands comparisons]
  (let [results (volatile! nil)
        out (with-out-str
              (vreset! results
                (cli/compare-comparisons
                  {:base-dir "." :commands commands :comparisons comparisons})))]
    [@results out]))

(defn- http-command [url]
  {"alpha" {"http" {"concurrency" 1 "requests" 2 "urls" [url]}}})

(def ^:private quick-comparison
  {"a" "x" "b" "y" "max-rounds" 2 "min-rounds" 2 "warmup-runs" 0})

(deftest failed-comparison-is-reported
  (testing "a comparison that throws fails without stopping the others"
    (server/with-server (fn [_ _] 200)
      (fn [url _]
        (let [commands {"x" (http-command url) "y" (http-command url)}
              [results out] (compare-comparisons! commands
                              [["broken" (assoc quick-comparison "b" "missing")]
                               ["working" quick-comparison]])]
          (is (= ["failed" "succeeded"] (map :status results)))
          (is (str/includes? out "broken comparison failed:"))
          (is (str/includes? out "undefined command")))))))

(deftest setup-and-cleanup-run-around-a-comparison
  (testing "each compared command's setup and cleanup run exactly once"
    (fs/with-temp-dir [tmpdir {:prefix "finefile-test"}]
      (server/with-server (fn [_ _] 200)
        (fn [url _]
          (let [marker (fn [n] (str "echo . >> " (fs/path tmpdir n)))
                commands {"x" (assoc (http-command url)
                                "setup" (marker "x-setup")
                                "cleanup" (marker "x-cleanup")
                                "shell" "bash")
                          "y" (assoc (http-command url)
                                "setup" (marker "y-setup")
                                "shell" "bash")}
                [results _] (compare-comparisons! commands [["c" quick-comparison]])]
            (is (= ["succeeded"] (map :status results)))
            (doseq [n ["x-setup" "x-cleanup" "y-setup"]]
              (is (= 1 (count (str/split-lines (slurp (str (fs/path tmpdir n))))))
                (str n " ran once")))))))))

(deftest cleanup-runs-even-when-a-comparison-fails
  (fs/with-temp-dir [tmpdir {:prefix "finefile-test"}]
    (let [cleanup-file (str (fs/path tmpdir "cleaned"))
          commands {"x" (assoc (http-command "http://127.0.0.1:1")
                          "cleanup" (str "touch " cleanup-file)
                          "shell" "bash")}
          [results _] (compare-comparisons! commands
                        [["c" (assoc quick-comparison "b" "x")]])]
      (is (= ["failed"] (map :status results)))
      (is (fs/exists? cleanup-file)))))

(deftest merge-compare-maps-concatenates
  (is (= {"comparisons" [1 2 3] "results" [:a :b]}
        (cli/merge-compare-maps
          [{"comparisons" [1] "results" [:a]}
           {"comparisons" [2 3] "results" [:b]}]))))

(deftest compare-exports-are-grouped-by-file
  (fs/with-temp-dir [tmpdir {:prefix "finefile-test"}]
    (let [cmp (fn [n file]
                {:comparison {"export-json" file}
                 :k n
                 :result-map {"comparisons" [{"name" n "p_different" 0.5}]
                              "results" [{"command" n "times" (double-array [0.5])}]}})]
      (cli/write-compare-exports! (str tmpdir)
        [(cmp "one" "a.json")
         (cmp "two" "a.json")
         (cmp "three" "b.json")
         ; A comparison with no export-json, and one that failed outright.
         (cmp "four" nil)
         {:comparison {"export-json" "b.json"} :k "failed"}])
      (testing "comparisons sharing a file land in it together"
        (let [m (json/read-str (slurp (str (fs/path tmpdir "a.json"))))]
          (is (= ["one" "two"] (map #(get % "name") (get m "comparisons"))))
          (is (= ["one" "two"] (map #(get % "command") (get m "results"))))))
      (testing "and a failed comparison contributes nothing"
        (let [m (json/read-str (slurp (str (fs/path tmpdir "b.json"))))]
          (is (= ["three"] (map #(get % "name") (get m "comparisons"))))))
      (testing "no file is written for a comparison without export-json"
        (is (= #{"a.json" "b.json"}
              (set (map fs/file-name (fs/list-dir tmpdir)))))))))
