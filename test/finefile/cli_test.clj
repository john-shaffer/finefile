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
