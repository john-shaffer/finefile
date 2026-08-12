# finefile

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/john-shaffer/finefile)

*Status: Alpha*

A CLI for performing [hyperfine](https://github.com/sharkdp/hyperfine) benchmarks via [TOML](https://toml.io/) configuration.

## Comparing two http benchmarks

`finefile alpha.compare` answers one question: do these two commands actually
perform differently? It runs them interleaved and stops as soon as the answer
is clear, which for an obvious difference is a few seconds.

```toml
[commands.baseline]
alpha.http = { concurrency = 64, requests = 10000, urls = ["http://127.0.0.1:8080"] }

[commands.candidate]
alpha.http = { concurrency = 64, requests = 10000, urls = ["http://127.0.0.1:8081"] }

[alpha.compare.rewrite]
a = "baseline"
b = "candidate"
export-json = "compare.json"
timeout-seconds = 300
```

```
$ finefile alpha.compare
Compare: rewrite (a = baseline, b = candidate)
  round   8 ( 32 runs)   ratio 1.3334   P(different) 79.2%   P(> 1.0%) 79.2%
  round   9 ( 36 runs)   ratio 1.3633   P(different) 70.3%   P(> 1.0%) 70.1%
  round  10 ( 40 runs)   ratio 1.3565   P(different) 77.7%   P(> 1.0%) 77.7%
  Different: baseline is 33.4% faster than candidate
  Throughput ratio baseline/candidate:  1.3338  [1.2835, 1.3860] (95.0% credible)
  P(different) 99.1%   BF10 108   after 18 rounds (4 batches of 4)
  P(difference exceeds 1.0%) 99.1%
```

Progress lines begin once there are enough rounds to batch — round 8 with the
defaults — and a long comparison prints only every tenth round; the stopping
rule itself may not fire before round 16, for reasons covered below.

Any failing status code or connection error stops a comparison, so a server
that falls over is never quietly timed as if it were fast. Set
`alpha.http.ignore-failure` on a command to measure it anyway.

### How it decides

Each round runs the two commands `a, b, b, a` and records the difference in
their mean log throughput. Interleaving means anything the two share within a
round — a thermal ramp, a noisy neighbour, a cache warming up — cancels out of
that difference instead of inflating its variance, and running `b, a, a, b` on
alternate rounds cancels what is left. Set `interleave = "alternate"` for `a, b`
rounds, which cost half as many runs but are sensitive to drift.

Interleaving does not make the rounds independent of each other, though. A
machine drifts over seconds, so consecutive differences come out correlated —
measured at lag one, around `+0.46` against a local server — and their sample
standard deviation then understates the standard error of their mean by about
half. Uncorrected, that is enough to report a 10% difference between a command
and itself, confidently, within five rounds. So rounds are averaged into
batches of at least `batch-rounds` (default 4, growing with the square root of
the round count) before any inference, which is the usual batch-means
estimator for a correlated series. That is why the round floor is 16 rather
than a handful: the comparison needs enough batches to have a spread worth
trusting.

After every round finefile computes two posterior probabilities and prints
both:

- **`P(different)`** is the probability that the two throughputs differ at
  all, from the JZS default Bayes factor: a `1/sd` prior on the nuisance
  parameters and a `Cauchy(0, prior-scale)` prior on the standardized effect.
- **`P(> min-effect)`** is the probability that they differ by more than
  `min-effect`, which defaults to 1%. Differing by that much is a special case
  of differing at all, so this is averaged over the same two hypotheses and
  always lands at or below `P(different)`: the point null puts no mass beyond
  the margin, which leaves the probability that the effect is real at all
  times the probability it clears the margin given that it is.

That averaging is why a comparison cannot call two batches *different* however
large the effect looks. One degree of freedom caps the Bayes factor near 7, so
the most any two batches can say is about 88%.

The two track each other closely once a difference dwarfs the margin, as in the
run above, where clearing 1% is barely a weaker claim than differing at all.
They separate when it does not: a real difference of a fifth of a percent
drives `P(> 1.0%)` to zero while `P(different)` climbs toward one.

`P(> min-effect)` is what the stopping rule uses. It stops at `certainty`
(default 99%) for *different*, at `1 - certainty` for *indistinguishable*, and
otherwise runs to `max-rounds` or `timeout-seconds` and reports the result as
inconclusive.

Setting `min-effect = 0` switches the decision to `P(different)`. That answers
the purer question, but only usefully in one direction: evidence for a
point null accumulates as the square root of the round count, so ruling a
difference *out* takes on the order of ten thousand rounds, where a 1% margin
takes a handful.

Checking after every round instead of fixing a sample size in advance rests on
a martingale argument: because the nuisance prior is the right-Haar prior, the
point-null Bayes factor over independent observations is a test martingale
under the null, and Ville's inequality bounds the probability that it ever
crosses `K` at `1/K`. That bound is exact for `min-effect = 0` with batching
off. The defaults re-batch the rounds as they accumulate and stop on
`P(> min-effect)` rather than the Bayes factor, so for them the guarantee is
approximate — a test in the suite holds the empirical false-positive rate of
the batched rule near its nominal 1%.

### Sizing a run

You need `|t| ≈ 4` to reach 99%, so the batches needed are roughly `(4s/Δ)²`,
where `Δ` is the log of the true throughput ratio and `s` the standard
deviation of the batch means. Since batches grow with the square root of the
round count, evidence accumulates with the square root of the rounds rather
than linearly — which is what correlated observations are actually worth.
Prefer more rounds over more `requests` per run: lengthening a run does not
shrink the round-to-round variance that the comparison is up against.

An inconclusive result is a real answer, not a failure. It means the credible
interval never got small enough to fit inside `min-effect`, and the interval
itself tells you how much noise the host has. If a comparison keeps timing out,
either the difference you are chasing is smaller than the machine can resolve,
or the machine needs to be quieter. Raising `min-effect` to something the host
can actually measure is usually the honest fix.

Batching handles drift on timescales shorter than a batch. Nothing here can
rescue you from drift slower than the whole run — if the machine is steadily
getting warmer for ten minutes, a ten-minute comparison cannot tell that apart
from a real difference. Interleaving is what limits the damage, since both
commands ride the same drift.
