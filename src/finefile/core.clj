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

(def ^:private plot-types->script-bin-names
  {"histogram" "hyperfine-plot-histogram"
   "whisker" "hyperfine-plot-whisker"})

(defn plot->args [m input-file]
  (let [{:strs [file type]} m]
    [(plot-types->script-bin-names type)
     input-file
     "--output" file]))
