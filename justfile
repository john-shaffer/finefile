repo_root := `pwd`

alias fmt := format
alias u := update-deps

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Format source and then check for unfixable issues
format:
    fd -e json -x jsonfmt -w
    just --fmt --unstable
    fd -e nix -x nixfmt
    standard-clj fix
    fd -e toml -x taplo format

run *args:
    clojure -M -m finefile.cli {{ args }}

update-deps: && update-deps-lock
    nix flake update
    clj -M:antq --upgrade --force

update-deps-lock:
    deps-lock deps.edn
