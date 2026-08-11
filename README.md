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
alpha.http = { concurrency = 64, requests = 10000, urls = ["http://127.0.0.1:1234"] }

[commands.candidate]
alpha.http = { concurrency = 64, requests = 10000, urls = ["http://127.0.0.1:5678"] }

[alpha.compare.rewrite]
a = "baseline"
b = "candidate"
export-json = "compare.json"
timeout-seconds = 300
```

```
$ finefile alpha.compare
Compare: rewrite (a = baseline, b = candidate)
  round   2 (  8 runs)   ratio 2.0082   P(different) 77.6%   P(> 1.0%) 100.0%
  round   3 ( 12 runs)   ratio 2.0080   P(different) 98.3%   P(> 1.0%) 100.0%
  round   4 ( 16 runs)   ratio 2.0091   P(different) 100.0%  P(> 1.0%) 100.0%
  round   5 ( 20 runs)   ratio 1.9698   P(different) 99.9%   P(> 1.0%) 100.0%
  Different: baseline is 97.0% faster than candidate
  Throughput ratio baseline/candidate:  1.9698  [1.8619, 2.0839] (95.0% credible)
  P(different) 99.9%   BF10 1.98e+03   after 5 rounds
  P(difference exceeds 1.0%) 100.0%
```

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

After every round finefile computes two posterior probabilities and prints
both:

- **`P(different)`** is the probability that the two throughputs differ at
  all, from the JZS default Bayes factor: a `1/sd` prior on the nuisance
  parameters and a `Cauchy(0, prior-scale)` prior on the standardized effect.
- **`P(> min-effect)`** is the probability that they differ by more than
  `min-effect`, which defaults to 1%.

`P(> min-effect)` is what the stopping rule uses. It stops at `certainty`
(default 99%) for *different*, at `1 - certainty` for *indistinguishable*, and
otherwise runs to `max-rounds` or `timeout-seconds` and reports the result as
inconclusive.

Setting `min-effect = 0` switches the decision to `P(different)`. That answers
the purer question, but only usefully in one direction: evidence for a
point null accumulates as the square root of the round count, so ruling a
difference *out* takes on the order of ten thousand rounds, where a 1% margin
takes a handful.

Because the nuisance prior is the right-Haar prior, the Bayes factor is a test
martingale under the null. Checking it after every round therefore costs
nothing in error rate — Ville's inequality bounds the probability that it ever
crosses `K` at `1/K` — so there is no need to fix a sample size in advance.

### Sizing a run

You need `|t| ≈ 4` to reach 99%, so the rounds needed are roughly `(4s/Δ)²`,
where `Δ` is the log of the true throughput ratio and `s` the round-to-round
standard deviation of the paired difference. At `s = 2%`, a 10% difference
stops at `min-rounds`; a 1% difference needs around 65 rounds. Prefer more
rounds over more `requests` per run: lengthening a run does not shrink the
round-to-round variance that the comparison is actually up against.
