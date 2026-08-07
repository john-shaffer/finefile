(ns finefile.util-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [finefile.util :as u]))

(deftest exec-lines-returns-stdout-lines
  (testing "returns each line of stdout"
    (let [lines (u/exec-lines {} "printf" "foo\nbar\nbaz\n")]
      (is (= ["foo" "bar" "baz"] lines)))))

(deftest exec-lines-large-output
  (testing "does not deadlock when stdout exceeds the OS pipe buffer"
    ;; Linux pipe buffer is ~64KB. We emit 200KB to guarantee a deadlock
    ;; with the old code (which waited for exit before draining stdout).
    (let [line (str/join (repeat 100 "x"))        ; 100-char line
          n    2000                                 ; 2000 lines ≈ 200KB
          lines (u/exec-lines {} "bash" "-c"
                  (str "yes " line " | head -n " n))]
      (is (= n (count lines)))
      (is (every? #(= (str line) %) lines)))))

(deftest exec-lines-throws-on-nonzero-exit
  (testing "throws RuntimeException when process exits non-zero"
    (is (thrown? RuntimeException
          (u/exec-lines {} "bash" "-c" "exit 42")))))

(deftest interruptible-exec-succeeds
  (testing "returns the process on success"
    (let [p (u/interruptible-exec {:out :discard :err :discard} "true")]
      (is (some? p)))))

(deftest interruptible-exec-throws-on-failure
  (testing "throws RuntimeException when process exits non-zero"
    (is (thrown? RuntimeException
          (u/interruptible-exec {:out :discard :err :discard} "false")))))
