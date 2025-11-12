{
  description = "finefile CLI for hyperfine benchmarks";

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
      };
      let
        finefileSrc = lib.sources.sourceFilesBySuffices self [
          ".clj"
          ".edn"
          ".json"
        ];
        finefileBin = clj-nix.lib.mkCljApp {
          pkgs = nixpkgs.legacyPackages.${system};
          modules = [
            {
              main-ns = "finefile.cli";
              name = "finefile";
              nativeImage.enable = true;
              projectSrc = finefileSrc;
              version = "0.1.0";
            }
          ];
        };
        runtimePaths = [
          hyperfine-flake.packages.${system}.default
          hyperfine-flake.packages.${system}.scripts
          pkgs.taplo
        ];
        finefileWrapped =
          runCommand finefileBin.name
            {
              inherit (finefileBin) pname version meta;

              nativeBuildInputs = [ makeWrapper ];
            }
            ''
              mkdir -p $out/bin
              makeWrapper ${finefileBin}/bin/finefile $out/bin/finefile \
                --prefix PATH : ${lib.makeBinPath runtimePaths}
            '';
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs =
            with pkgs;
            [
              clojure
              deps-lock
              fd
              jsonfmt
              just
              nixfmt-rfc-style
              omnix
            ]
            ++ runtimePaths;
        };
        packages = {
          default = finefileWrapped;
          finefile = finefileWrapped;
          finefile-unwrapped = finefileBin;
        };
      }
    );
}
