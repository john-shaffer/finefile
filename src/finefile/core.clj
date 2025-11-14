(ns finefile.core)

(defn command->hyperfine-args [k command]
  (let [{:strs [conclude command prepare]} command]
    (concat
      ["--command-name" k]
      (when prepare ["--prepare" prepare])
      (when command [command])
      (when conclude ["--conclude" conclude]))))

(defn finefile-map->hyperfine-args [finefile-map opts]
  (let [{:keys [export-file include-tags]} opts
        {:strs [commands defaults]} finefile-map
        {:strs [cleanup shell]} defaults
        filters (when include-tags
                  [(filter
                     (fn [[_ command]]
                       (some include-tags (get command "tags"))))])]
    (concat
      (when export-file ["--export-json" (str export-file)])
      (when shell ["--shell" shell])
      (when cleanup ["--cleanup" cleanup])
      (as-> filters $
        (concat $
          [(mapcat
             (fn [[k command]]
               (command->hyperfine-args k (merge defaults command))))])
        (apply comp $)
        (transduce $ conj [] commands)))))

(def ^:private plot-types->script-bin-names
  {"histogram" "hyperfine-plot-histogram"
   "whisker" "hyperfine-plot-whisker"})

(defn plot->args [m input-file]
  (let [{:strs [file type]} m]
    [(plot-types->script-bin-names type)
     input-file
     "--output" file]))
