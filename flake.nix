{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.05";
    clj-nix = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:jlesquembre/clj-nix";
    };
    flake-utils.url = "github:numtide/flake-utils";
    hyperfine-flake = {
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:john-shaffer/hyperfine-flake";
    };
  };
  outputs =
    inputs:
    with inputs;
    flake-utils.lib.eachDefaultSystem (
      system:
      with import nixpkgs {
        inherit system;
        overlays = [ clj-nix.overlays.default ];
      }; {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            clojure
            deps-lock
            hyperfine-flake.packages.${system}.default
            hyperfine-flake.packages.${system}.scripts
            just
            nixfmt-rfc-style
            taplo
          ];
        };
      }
    );
}
