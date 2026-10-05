{
  description = "Development tools for finefile";

  inputs = {
    finefile.url = "./..";
    healthy.follows = "finefile/healthy";
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
        system: pkgs: {
          default = pkgs.mkShell {
            buildInputs = [ inputs.healthy.packages.${system}.default ];
          };
        }
      );
    };
}
