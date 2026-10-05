{
  description = "Development tools for finefile";

  inputs = {
    finefile.url = "./..";
    healthy = {
      inputs.nixpkgs.follows = "finefile/nixpkgs";
      url = "github:john-shaffer/healthy";
    };
  };
  outputs =
    inputs:
    let
      supportedSystems = [
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems =
        function:
        inputs.finefile.inputs.nixpkgs.lib.genAttrs supportedSystems (
          system: function system inputs.finefile.inputs.nixpkgs.legacyPackages.${system}
        );
    in
    {
      devShells = forAllSystems (
        system: pkgs:
        let
          finefileLib = inputs.finefile.lib.${system};
        in
        {
          default = pkgs.mkShell {
            buildInputs =
              with pkgs;
              [
                (clojure.override { jdk = finefileLib.jdk; })
                finefileLib.jdk
                fd
                inputs.finefile.inputs.clj-nix.packages.${system}.deps-lock
                inputs.healthy.packages.${system}.default
                jsonfmt
                just
                nixfmt
                omnix
                siege
              ]
              ++ finefileLib.runtimePaths;
            shellHook = ''
              echo
              echo -e "Run '\033[1mjust <recipe>\033[0m' to get started"
              just --list
            '';
          };
        }
      );
    };
}
