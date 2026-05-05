{
  description = "finefile CLI for hyperfine benchmarks";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
    clj-nix = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:jlesquembre/clj-nix";
    };
    healthy = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:john-shaffer/healthy";
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
        "aarch64-linux"
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
                inputs.healthy.packages.${system}.default
                jsonfmt
                just
                nixfmt
                omnix
                siege
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
      checks = forAllSystems (
        system: pkgs: {
          smoke = pkgs.runCommand "finefile-smoke-test" { } ''
            ${self.packages.${system}.finefile-unwrapped}/bin/finefile --help
            touch $out
          '';
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
                nativeImage = {
                  enable = true;
                  extraNativeImageBuildArgs = [
                    "--enable-url-protocols=http,https"
                  ];
                  graalvm = graalvmPackages.graalvm-ce;
                };
                projectSrc = finefileSrc;
                version = "0.1.0";
              }
            ];
          };
          finefileJvmBin = clj-nix.lib.mkCljApp {
            inherit pkgs;
            modules = [
              {
                jdk = jdkPackage;
                main-ns = "finefile.cli";
                name = "finefile";
                projectSrc = finefileSrc;
                version = "0.1.0";
              }
            ];
          };
          finefileAotCache = stdenv.mkDerivation {
            name = "finefile-aot-cache";
            dontUnpack = true;
            buildPhase = ''
              jar=$(grep -oE '/nix/store/[^ ]+\.jar' ${finefileJvmBin}/bin/finefile | head -1)
              ${jdkPackage}/bin/java -XX:AOTMode=record -XX:AOTConfiguration=finefile.aotconf -jar "$jar" || true
              ${jdkPackage}/bin/java -XX:AOTMode=create -XX:AOTConfiguration=finefile.aotconf -XX:AOTCache=finefile.aot -jar "$jar"
            '';
            installPhase = ''
              mkdir -p $out
              cp finefile.aot $out/finefile.aot
            '';
          };
          finefileJvmUnwrapped = stdenv.mkDerivation {
            inherit (finefileJvmBin) meta version;
            name = "finefile-jvm";
            phases = [ "installPhase" ];
            installPhase = ''
              mkdir -p $out/bin $out/share/finefile-jvm
              cp ${finefileJvmBin}/bin/finefile $out/bin/finefile
              cp ${finefileAotCache}/finefile.aot $out/share/finefile-jvm/finefile.aot
              cp ${finefileSrc}/schema/finefile.toml.latest.schema.json $out/share/finefile-jvm
            '';
          };
          finefileJvmWrapped =
            runCommand "finefile-jvm"
              {
                inherit (finefileJvmUnwrapped) meta name version;
                nativeBuildInputs = [ makeWrapper ];
              }
              ''
                mkdir -p $out/bin
                makeWrapper ${finefileJvmUnwrapped}/bin/finefile $out/bin/finefile \
                  --prefix PATH : ${lib.makeBinPath runtimePaths} \
                  --set-default FINEFILE_SCHEMA ${finefileJvmUnwrapped}/share/finefile-jvm/finefile.toml.latest.schema.json \
                  --set-default JDK_JAVA_OPTIONS "-XX:AOTCache=${finefileJvmUnwrapped}/share/finefile-jvm/finefile.aot"
              '';
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
          finefile-jvm = finefileJvmWrapped;
          finefile-jvm-unwrapped = finefileJvmUnwrapped;
          finefile-unwrapped = finefileUnwrapped;
        }
      );
    };
}
