(ns finefile.stats
  (:import
   (java.util Arrays)))

(set! *warn-on-reflection* true)

(defn- kahan-sum
  "Computes the sum of an array of doubles using the Kahan summation algorithm.

   Naively adding numbers can accumulate errors in the floating point operations.
   Kahan summation compensates for the numerical errors to return a more
   stable result."
  ^double [^doubles A]
  (loop [i 0
         sum 0.0
         c 0.0]
    (if (= i (alength A))
      sum
      (let [y (- (aget A i) c)
            t (+ sum y)]
        (recur (inc i) t (- t sum y))))))

(defn- M2-welford
  "Computes the second moment using Welford's algorithm.

   Must be called with an array of 2 or more items."
  ^double [^doubles A]
  (let [ct (alength A)]
    (loop [i 1
           ; Rolling mean
           mean (aget A 0)
           ; The second moment
           M2 0.0]
      (if (= i ct)
        M2
        (let [k (inc i)
              a (aget A i)
              d (- a mean)
              mean' (+ mean (/ d k))
              M2' (+ M2 (* d (- a mean')))]
          (recur k mean' M2'))))))

(defn- sample-variance
  "Computes the sample variance using Welford's algorithm.

   Must be called with an array of 2 or more items."
  ^double [^doubles A]
  (/ (M2-welford A) (dec (alength A))))

(defn time-stats [^doubles times]
  (let [A (aclone times)]
    (Arrays/sort A)
    (let [ct (alength A)
          min (aget A 0)
          max (aget A (dec ct))
          mean (/ (kahan-sum A) ct)
          median (if (zero? (rem ct 2))
                   (/ (+ (aget A (dec (quot ct 2)))
                        (aget A (quot ct 2)))
                     2)
                   (aget A (quot ct 2)))]
      (cond->
        {"max" max
         "mean" mean
         "median" median
         "min" min}
        (< 1 ct) (assoc "stddev" (Math/sqrt (sample-variance A)))))))
