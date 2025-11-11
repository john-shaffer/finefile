repo_root := `pwd`

alias fmt := format
alias u := update-deps

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Format source and then check for unfixable issues
format:
    just --fmt --unstable
    fd --glob "*.nix" -x nixfmt
    standard-clj fix
    fd --glob "*.toml" -x taplo format

run *args:
    clojure -M -m finefile.cli {{ args }}

update-deps: && update-deps-lock
    nix flake update
    clj -M:antq --upgrade --force

update-deps-lock:
    deps-lock deps.edn
