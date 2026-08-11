(ns finefile.cli-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [finefile.cli :as cli]))

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
