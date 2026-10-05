repo_root := `pwd`

alias b := build
alias fmt := format
alias t := test
alias u := update

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Run benchmarks
bench *args: build
    nix run . -- bench {{ args }}

# Build the finefile package
build:
    nix build

check:
    nix flake check

# Format source and then check for unfixable issues
format:
    fd -e json -x jsonfmt -w
    just --fmt --unstable
    fd -e nix -x nixfmt
    standard-clj fix
    fd -e toml -x taplo format

# Run finefile
run *args:
    clojure -M -m finefile.cli {{ args }}

# Run the Clojure tests and show their output
test: (_report "finefile-test-report")

# Build a report package, show its log, and exit with its status
_report attr:
    #!/usr/bin/env bash
    set -euo pipefail
    out=$(nix build --no-link --print-out-paths ".#{{ attr }}")
    cat "$out/log"
    exit "$(cat "$out/status")"

# Update dependencies
update: && update-deps-lock
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
