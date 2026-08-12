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

(defn- log-sum-exp
  "Computes (log (apply + (map exp A))) without overflowing."
  ^double [^doubles A]
  (let [ct (alength A)
        mx (areduce A i mx Double/NEGATIVE_INFINITY
             (Math/max (double mx) (aget A i)))]
    (if (Double/isInfinite mx)
      mx
      (loop [i 0
             sum 0.0]
        (if (= i ct)
          (+ mx (Math/log sum))
          (recur (inc i) (+ sum (Math/exp (- (aget A i) mx)))))))))

(def ^:private lanczos-coefficients
  (double-array
    [0.99999999999980993 676.5203681218851 -1259.1392167224028
     771.32342877765313 -176.61502916214059 12.507343278686905
     -0.13857109526572012 9.9843695780195716e-6 1.5056327351493116e-7]))

(defn- log-gamma
  "Computes the natural log of the gamma function via the Lanczos
   approximation, with reflection for arguments below 1/2."
  ^double [^double x]
  (if (< x 0.5)
    (- (Math/log (/ Math/PI (Math/sin (* Math/PI x))))
      (log-gamma (- 1.0 x)))
    (let [^doubles c lanczos-coefficients
          x (dec x)
          t (+ x 7.5)
          a (loop [i 1
                   a (aget c 0)]
              (if (= i (alength c))
                a
                (recur (inc i) (+ a (/ (aget c i) (+ x i))))))]
      (+ (* 0.5 (Math/log (* 2.0 Math/PI)))
        (* (+ x 0.5) (Math/log t))
        (- t)
        (Math/log a)))))

(def ^:private ^:const cf-tiny 1.0e-300)
(def ^:private ^:const cf-epsilon 3.0e-16)
(def ^:private ^:const cf-max-iterations 300)

(defn- guard-zero
  "Keeps a continued fraction denominator away from zero."
  ^double [^double v]
  (if (< (Math/abs v) cf-tiny) cf-tiny v))

(defn- beta-cf
  "Evaluates the continued fraction for the incomplete beta function using
   the modified Lentz algorithm."
  ^double [^double a ^double b ^double x]
  (let [qab (+ a b)
        qap (inc a)
        qam (dec a)
        d0 (/ 1.0 (guard-zero (- 1.0 (/ (* qab x) qap))))]
    (loop [m 1
           c 1.0
           d d0
           h d0]
      (if (< cf-max-iterations m)
        h
        (let [m2 (* 2 m)
              ; Even step of the recurrence.
              aa (/ (* m (- b m) x) (* (+ qam m2) (+ a m2)))
              d (/ 1.0 (guard-zero (+ 1.0 (* aa d))))
              c (guard-zero (+ 1.0 (/ aa c)))
              h (* h d c)
              ; Odd step.
              aa (/ (* (- (+ a m)) (+ qab m) x) (* (+ a m2) (+ qap m2)))
              d (/ 1.0 (guard-zero (+ 1.0 (* aa d))))
              c (guard-zero (+ 1.0 (/ aa c)))
              del (* d c)
              h (* h del)]
          (if (< (Math/abs (- del 1.0)) cf-epsilon)
            h
            (recur (inc m) c d h)))))))

(defn- regularized-incomplete-beta
  "Computes I_x(a, b)."
  ^double [^double a ^double b ^double x]
  (cond
    (<= x 0.0) 0.0
    (<= 1.0 x) 1.0
    :else
    (let [bt (Math/exp (+ (log-gamma (+ a b)) (- (log-gamma a)) (- (log-gamma b))
                         (* a (Math/log x))
                         (* b (Math/log1p (- x)))))]
      ; The continued fraction only converges quickly on one side of this
      ; point, so we flip to the complement on the other side.
      (if (< x (/ (inc a) (+ a b 2.0)))
        (/ (* bt (beta-cf a b x)) a)
        (- 1.0 (/ (* bt (beta-cf b a (- 1.0 x))) b))))))

(defn t-cdf
  "Computes P(T <= t) for a Student t distribution with nu degrees of freedom."
  ^double [^double t ^double nu]
  (let [; The two-sided tail mass beyond |t|.
        tails (regularized-incomplete-beta (* 0.5 nu) 0.5
                (/ nu (+ nu (* t t))))]
    (if (pos? t)
      (- 1.0 (* 0.5 tails))
      (* 0.5 tails))))

(def ^:private ^:const quantile-bound 1.0e6)
(def ^:private ^:const quantile-iterations 100)

(defn t-quantile
  "Computes the value t for which P(T <= t) = p, by bisection."
  ^double [^double p ^double nu]
  (loop [i 0
         lo (- quantile-bound)
         hi quantile-bound]
    (let [mid (* 0.5 (+ lo hi))]
      (if (= i quantile-iterations)
        mid
        (if (< (t-cdf mid nu) p)
          (recur (inc i) mid hi)
          (recur (inc i) lo mid))))))

(def ^:private ^:const jzs-nodes 256)
(def ^:private ^:const jzs-half-width 18.0)

(defn jzs-log-bf10
  "Computes the log of the JZS default Bayes factor in favor of a non-zero
   effect (Rouder et al. 2009).

   The nuisance parameters get the location-scale invariant prior
   p(mean, sd) ∝ 1/sd, and the standardized effect size gets a
   Cauchy(0, prior-scale) prior. Because the nuisance prior is the right-Haar
   prior, the sequence of Bayes factors over a growing sample of independent
   observations is a test martingale under the null hypothesis, so it can be
   inspected after every observation without inflating the error rate:
   Ville's inequality bounds the probability that it ever exceeds K at 1/K.
   That guarantee is exact only for that setting; a caller that re-batches
   the series between inspections or that stops on a posterior tail
   probability instead (see finefile.compare) inherits it approximately.

   t is the t statistic and nu its degrees of freedom. n-eff is the effective
   sample size: n for a paired or one-sample test, and
   (n-a * n-b) / (n-a + n-b) for a two-sample test.

   The marginal likelihood of the null is folded into the integrand rather
   than divided out afterwards, because for the large t values that are worth
   detecting both terms underflow on their own."
  ^double [^double t ^double n-eff ^double nu ^double prior-scale]
  (let [t2-nu (/ (* t t) nu)
        null-term (Math/log1p t2-nu)
        ; Substituting g = e^u turns the improper integral over the prior on
        ; the variance of the effect size into a well-behaved one that the
        ; trapezoid rule nails; the integrand decays to zero at both ends.
        ;
        ; The integrand peaks near the prior scale when the data are weak and
        ; near the observed squared effect size when they are strong, so we
        ; centre the window on whichever dominates. A window fixed at the prior
        ; misses the peak entirely once t gets large.
        u0 (- (Math/log (+ (* prior-scale prior-scale) (/ (* t t) n-eff)))
             jzs-half-width)
        h (/ (* 2.0 jzs-half-width) (dec jzs-nodes))
        fs (double-array jzs-nodes)]
    (dotimes [i jzs-nodes]
      (let [u (+ u0 (* i h))
            ng (* n-eff (Math/exp u))
            ; log of the InverseGamma(1/2, prior-scale²/2) density of g,
            ; including the Jacobian of the substitution.
            log-prior (- (Math/log prior-scale)
                        (* 0.5 (Math/log (* 2.0 Math/PI)))
                        (* 0.5 u)
                        (* 0.5 prior-scale prior-scale (Math/exp (- u))))
            log-kernel (+ (* -0.5 (Math/log1p ng))
                         (* 0.5 (inc nu)
                           (- null-term (Math/log1p (/ t2-nu (inc ng))))))]
        (aset fs i (+ log-prior log-kernel))))
    (+ (Math/log h) (log-sum-exp fs))))

(def ^:private ^:const default-credible-mass 0.95)
; A min-batch of one asserts that the observations are already independent and
; turns batching off. Anything larger is a floor under a batch length that
; otherwise grows with the square root of the observation count.
(def ^:private ^:const default-min-batch 1)
(def ^:private ^:const default-prior-scale 0.707)

(defn- batch-means
  "Averages consecutive observations into non-overlapping batches of size b,
   returning nil if there are not enough for two batches.

   Benchmark rounds are not independent. A machine drifts over seconds, so
   consecutive differences come out correlated - measured at lag one, around
   +0.46 against a local server - and the sample standard deviation then
   understates the standard error of their mean by a factor of two or so. That
   is the difference between an honest answer and a confident wrong one, since
   a run of rounds that all drift the same way looks exactly like a real
   effect.

   Averaging blocks of rounds gives observations much closer to independent.
   The block length grows with the square root of the round count, which is
   the usual batch-means estimator for a correlated series: it keeps the
   estimate consistent as the run gets longer without assuming any particular
   correlation length up front."
  ^doubles [^doubles A ^long b]
  (let [n (alength A)
        k (quot n b)]
    (when (<= 2 k)
      (let [out (double-array k)
            ; Keep the most recent whole batches. The earliest rounds are the
            ; least likely to have reached a steady state.
            offset (- n (* k b))]
        (dotimes [i k]
          (let [start (+ offset (* i b))
                sum (loop [j 0
                           sum 0.0]
                      (if (= j b)
                        sum
                        (recur (inc j) (+ sum (aget A (+ start j))))))]
            (aset out i (double (/ sum b)))))
        out))))

; A standard deviation of exactly zero means unbounded evidence. Capping the
; t statistic keeps every derived number finite and serializable.
(def ^:private ^:const max-t 1.0e8)

(defn- interval-tail
  "Computes P(|X| > bound) for X ~ t(nu) located at mean with scale se."
  ^double [^double mean ^double se ^double nu ^double bound]
  (if (pos? se)
    (+ (- 1.0 (t-cdf (/ (- bound mean) se) nu))
      (t-cdf (/ (- (- bound) mean) se) nu))
    (if (< bound (Math/abs mean)) 1.0 0.0)))

(defn paired-comparison
  "Analyzes a sequence of paired differences, e.g. of log throughput.

   The differences are grouped into batches first, so returns nil until there
   are enough rounds for two of them. Otherwise returns a map with:

     :batch-size  rounds averaged into each observation
     :batches     number of observations the inference actually rests on
     :bf10        evidence for a difference over no difference
     :ci          credible interval for the mean difference
     :p-different posterior probability that the two rates differ at all,
                  with equal prior odds on the two hypotheses
     :p-practical posterior probability that they differ by more than
                  min-effect, averaged over both hypotheses so that it can
                  never exceed :p-different; present only when min-effect
                  is positive
     :rounds      number of differences supplied

   min-effect is given as a ratio, so 0.01 asks about a 1% difference."
  [ds {:keys [credible-mass min-batch min-effect prior-scale]}]
  (let [raw (double-array ds)
        rounds (alength raw)
        min-batch (long (or min-batch default-min-batch))
        batch-size (if (<= min-batch 1)
                     1
                     (Math/max min-batch (long (Math/sqrt rounds))))
        ^doubles A (batch-means raw batch-size)]
    (when A
      (let [credible-mass (or credible-mass default-credible-mass)
            prior-scale (or prior-scale default-prior-scale)
            n (alength A)
            nu (dec n)
            mean (/ (kahan-sum A) n)
            stddev (Math/sqrt (sample-variance A))
            se (/ stddev (Math/sqrt n))
            t (cond
                (pos? se) (/ mean se)
                (zero? mean) 0.0
                :else (Math/copySign max-t mean))
            log-bf10 (jzs-log-bf10 t n nu prior-scale)
            ; Equal prior odds is the maximum entropy choice over the two
            ; hypotheses, which makes the posterior probability the logistic of
            ; the log Bayes factor.
            p-different (/ 1.0 (+ 1.0 (Math/exp (- log-bf10))))
            half-width (* se (t-quantile (- 1.0 (* 0.5 (- 1.0 credible-mass))) nu))]
        (cond->
          {:batch-size batch-size
           :batches n
           :bf10 (Math/exp log-bf10)
           :ci [(- mean half-width) (+ mean half-width)]
           :credible-mass credible-mass
           :log-bf10 log-bf10
           :mean mean
           :rounds rounds
           :p-different p-different
           :prior-scale prior-scale
           :stddev stddev
           :t t}
          (and min-effect (pos? min-effect))
          ; Differing by more than min-effect is a special case of differing at
          ; all, so this has to come out below :p-different. Getting that means
          ; averaging over the same two hypotheses the Bayes factor weighs: the
          ; point null contributes nothing, since it puts no mass beyond the
          ; margin, leaving the probability that the effect is real at all
          ; times the probability it clears the margin given that it is. The
          ; flat-prior tail stands in for that second factor, which the Cauchy
          ; prior would shrink a little further toward zero.
          ;
          ; The tail on its own answers a narrower question and is what this
          ; used to report. It pays no Occam penalty for the alternative and
          ; does not saturate the way the Bayes factor does at one or two
          ; degrees of freedom, so it would read 100% over batches whose Bayes
          ; factor cannot get past 88% however large the effect.
          (assoc :p-practical
            (* p-different (interval-tail mean se nu (Math/log1p min-effect)))))))))

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
