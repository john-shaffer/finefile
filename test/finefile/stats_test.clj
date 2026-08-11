(ns finefile.stats-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [finefile.stats :as stats]))

(defn- close?
  ([a b] (close? a b 1.0e-9))
  ([a b tol] (< (Math/abs (- (double a) (double b))) (double tol))))

(deftest t-cdf-matches-closed-forms
  (testing "nu = 1 is the Cauchy distribution"
    (doseq [t [-4.0 -1.3 -0.2 0.0 0.7 2.5 9.0]]
      (is (close? (stats/t-cdf t 1.0)
            (+ 0.5 (/ (Math/atan t) Math/PI)))
        (str "t = " t))))
  (testing "nu = 2 has a closed form too"
    (doseq [t [-4.0 -1.3 -0.2 0.0 0.7 2.5 9.0]]
      (is (close? (stats/t-cdf t 2.0)
            (+ 0.5 (/ t (* 2.0 (Math/sqrt (+ 2.0 (* t t)))))))
        (str "t = " t)))))

(deftest t-cdf-known-values
  ; Cross-checked against direct Simpson quadrature of the t density.
  (testing "nu = 30"
    (is (close? (stats/t-cdf 1.0 30.0) 0.837345692 1.0e-8))
    (is (close? (stats/t-cdf 2.0 30.0) 0.972687478 1.0e-8))
    (is (close? (stats/t-cdf 3.0 30.0) 0.997305018 1.0e-8)))
  (testing "the distribution is symmetric about zero"
    (doseq [[t nu] [[1.5 5.0] [2.0 100.0] [0.3 7.0]]]
      (is (close? (+ (stats/t-cdf t nu) (stats/t-cdf (- t) nu)) 1.0)))))

(deftest t-quantile-inverts-t-cdf
  (testing "round trips through the cdf"
    (doseq [p [0.001 0.025 0.5 0.975 0.999]
            nu [1.0 4.0 12.0 200.0]]
      (is (close? (stats/t-cdf (stats/t-quantile p nu) nu) p 1.0e-9)
        (str "p = " p ", nu = " nu))))
  (testing "matches a published quantile"
    (is (close? (stats/t-quantile 0.975 12.0) 2.178813 1.0e-6))))

(deftest jzs-bayes-factor
  ; These agree to eight digits with direct numerical integration of the two
  ; marginal likelihoods, and match the BayesFactor package's defaults.
  (testing "known values at the default prior scale"
    (doseq [[t n expected] [[2.5 20 2.7023]
                            [0.0 20 0.23236]
                            [1.0 20 0.36131]
                            [3.5 20 17.185]
                            [2.0 10 1.2824]
                            [5.0 50 2429.8]]]
      (is (close? (Math/exp (stats/jzs-log-bf10 t n (dec n) 0.707)) expected
            (* 1.0e-4 expected))
        (str "t = " t ", n = " n))))
  (testing "no evidence for a difference when the effect is zero"
    (doseq [n [3 10 100]]
      (is (neg? (stats/jzs-log-bf10 0.0 n (dec n) 0.707)))))
  (testing "evidence grows with the effect and ignores its sign"
    (let [bfs (map #(stats/jzs-log-bf10 % 20 19 0.707) [0.0 0.5 1.0 2.0 3.0 5.0])]
      (is (apply < bfs)))
    (is (close? (stats/jzs-log-bf10 2.7 20 19 0.707)
          (stats/jzs-log-bf10 -2.7 20 19 0.707))))
  (testing "an extreme t stays finite, so the result can be serialized"
    (let [log-bf10 (stats/jzs-log-bf10 1.0e8 20 19 0.707)]
      (is (Double/isFinite log-bf10))
      (is (pos? log-bf10)))))

(deftest paired-comparison-needs-two-pairs
  (is (nil? (stats/paired-comparison [] {})))
  (is (nil? (stats/paired-comparison [0.1] {})))
  (is (some? (stats/paired-comparison [0.1 0.2] {}))))

(deftest paired-comparison-detects-a-clear-difference
  (let [{:keys [ci mean n p-different]}
        (stats/paired-comparison [0.10 0.11 0.09 0.12 0.10] {})]
    (is (= 5 n))
    (is (close? mean 0.104 1.0e-12))
    (is (< 0.99 p-different))
    (testing "and brackets the mean with a credible interval"
      (is (< (first ci) mean (second ci))))))

(deftest paired-comparison-stays-unconvinced-by-noise
  (let [{:keys [p-different]}
        (stats/paired-comparison [0.01 -0.02 0.005 -0.011 0.0] {})]
    (is (< p-different 0.5))))

(deftest paired-comparison-practical-significance
  (testing "a 10% difference clears a 1% threshold"
    (let [{:keys [p-practical]}
          (stats/paired-comparison [0.10 0.11 0.09 0.12 0.10] {:min-effect 0.01})]
      (is (< 0.99 p-practical))))
  (testing "a consistent but tiny difference does not clear a 5% threshold"
    (let [{:keys [p-different p-practical]}
          (stats/paired-comparison (repeat 8 0.002) {:min-effect 0.05})]
      ; Zero spread means the rates certainly differ, but not by enough to care.
      (is (< 0.99 p-different))
      (is (close? p-practical 0.0 1.0e-6))))
  (testing "min-effect is only reported when it is set"
    (is (nil? (:p-practical (stats/paired-comparison [0.1 0.2] {}))))
    (is (nil? (:p-practical (stats/paired-comparison [0.1 0.2] {:min-effect 0}))))))

(deftest paired-comparison-handles-zero-spread
  (testing "identical differences do not produce infinities"
    (let [{:keys [bf10 ci log-bf10 p-different t]}
          (stats/paired-comparison (repeat 5 0.25) {})]
      (is (Double/isFinite log-bf10))
      (is (Double/isFinite bf10))
      (is (every? #(Double/isFinite (double %)) ci))
      (is (pos? t))
      (is (close? p-different 1.0 1.0e-12))))
  (testing "identical zeroes are no evidence of a difference"
    (let [{:keys [p-different t]} (stats/paired-comparison (repeat 5 0.0) {})]
      (is (zero? t))
      (is (< p-different 0.5)))))

(deftest time-stats-summarizes-runs
  (let [m (stats/time-stats (double-array [3.0 1.0 2.0 4.0]))]
    (is (close? (get m "min") 1.0))
    (is (close? (get m "max") 4.0))
    (is (close? (get m "mean") 2.5))
    (is (close? (get m "median") 2.5))
    (is (close? (get m "stddev") (Math/sqrt (/ 5.0 3.0))))))
