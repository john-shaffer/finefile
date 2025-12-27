{
  description = "finefile CLI for hyperfine benchmarks";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    clj-nix = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:jlesquembre/clj-nix";
    };
    hyperfine-flake = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:john-shaffer/hyperfine-flake";
    };
  };
  outputs =
    inputs:
    with inputs;
    let
      supportedSystems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-darwin"
        "x86_64-linux"
      ];
      forAllSystems =
        function:
        nixpkgs.lib.genAttrs supportedSystems (
          system:
          function system (
            import nixpkgs {
              inherit system;
              overlays = [
                clj-nix.overlays.default
              ];
            }
          )
        );
      getJdk = (pkgs: pkgs.jdk25_headless);
      getRuntimePaths = (
        system: pkgs: [
          pkgs.hyperfine
          hyperfine-flake.packages.${system}.scripts
          pkgs.taplo
        ]
      );
    in
    {
      devShells = forAllSystems (
        system: pkgs: {
          default = pkgs.mkShell {
            buildInputs =
              with pkgs;
              [
                (clojure.overrideAttrs { jdk = getJdk pkgs; })
                deps-lock
                fd
                jsonfmt
                just
                nixfmt
                omnix
              ]
              ++ getRuntimePaths system pkgs;
            shellHook = ''
              echo
              echo -e "Run '\033[1mjust <recipe>\033[0m' to get started"
              just --list
            '';
          };
        }
      );
      packages = forAllSystems (
        systems: pkgs:
        with pkgs;
        let
          jdkPackage = getJdk pkgs;
          finefileSrc = lib.sources.sourceFilesBySuffices self [
            ".clj"
            ".edn"
            ".json"
          ];
          finefileBin = clj-nix.lib.mkCljApp {
            inherit pkgs;
            modules = [
              {
                jdk = jdkPackage;
                main-ns = "finefile.cli";
                name = "finefile";
                nativeImage.enable = true;
                nativeImage.graalvm = graalvmPackages.graalvm-ce;
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
          runtimePaths = getRuntimePaths system pkgs;
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
          default = finefileWrapped;
          finefile = finefileWrapped;
          finefile-unwrapped = finefileUnwrapped;
        }
      );
    };
}
