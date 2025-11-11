(ns finefile.core)

(defn command->hyperfine-args [k command]
  (let [{:strs [cleanup conclude command prepare]} command]
    (concat
      ["--command-name" k]
      (when prepare ["--prepare" prepare])
      (when command [command])
      (when conclude ["--conclude" conclude])
      (when cleanup ["--cleanup" cleanup]))))

(defn finefile-map->hyperfine-args [finefile-map]
  (let [{:strs [commands defaults]} finefile-map
        {:strs [export-json shell]} defaults]
    (concat
      (when export-json ["--export-json" export-json])
      (when shell ["--shell" shell])
      (mapcat
        (fn [[k command]]
          (command->hyperfine-args k (merge defaults command)))
        commands))))
