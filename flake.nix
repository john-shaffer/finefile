{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.05";
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
      system: with import nixpkgs { inherit system; }; {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            clojure
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
