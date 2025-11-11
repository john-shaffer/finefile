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

run:
    clojure -M -m finefile.cli finefile.toml

update-deps:
    nix flake update
    clj -M:antq --upgrade --force
