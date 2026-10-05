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

# Run the http benchmarks in finefile-http.toml against a local healthy server
bench-http *args: build
    just _with-healthy nix run . -- bench -f finefile-http.toml {{ args }}

# Run the http comparisons in finefile-http.toml against a local healthy server
compare-http *args: build
    just _with-healthy nix run . -- alpha.compare -f finefile-http.toml {{ args }}

# Run a command while healthy listens on port 1234, as finefile-http.toml expects
_with-healthy +cmd:
    nix develop ./dev -c bash -c ' \
        PORT=1234 healthy > /dev/null & \
        trap "kill $!" EXIT; \
        until (exec 3<> /dev/tcp/127.0.0.1/1234) 2> /dev/null; do sleep 0.1; done; \
        "$@"' _ {{ cmd }}

# Build the finefile package
build:
    nix build

check:
    nix flake check
    nix flake check ./dev

# Format source and then check for unfixable issues
format:
    sand fmt
    standard-clj fix

# Compile the Java sources into target/classes
javac:
    javac -d target/classes $(fd -e java . src-java)

# Run finefile
run *args: javac
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
    nix flake update --flake ./dev
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
