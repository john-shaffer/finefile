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
        finefileUnwrapped = stdenv.mkDerivation {
          inherit (finefileBin) meta name version;
          phases = [ "installPhase" ];
          installPhase = ''
            mkdir -p $out/bin
            mkdir -p $out/share/finefile
            cp ${finefileBin}/bin/finefile $out/bin/finefile
            cp ${finefileSrc}/schema/finefile.toml.latest.schema.json $out/share/finefile
          '';
        };
        runtimePaths = [
          hyperfine-flake.packages.${system}.default
          hyperfine-flake.packages.${system}.scripts
          pkgs.taplo
        ];
        finefileWrapped =
          runCommand finefileUnwrapped.name
            {
              inherit (finefileUnwrapped) meta name version;

              nativeBuildInputs = [ makeWrapper ];
            }
            ''
              mkdir -p $out/bin
              makeWrapper ${finefileUnwrapped}/bin/finefile $out/bin/finefile \
                --prefix PATH : ${lib.makeBinPath runtimePaths} \
                --set-default FINEFILE_SCHEMA ${finefileUnwrapped}/share/finefile/finefile.toml.latest.schema.json
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
