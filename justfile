repo_root := `pwd`

alias b := build
alias fmt := format
alias u := update-deps

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Build the finefile package
build:
    nix build

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

# Update dependencies
update-deps: && update-deps-lock
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
