(ns finefile.core
  (:require
   [clojure.data.json :as json]))

(defn command->hyperfine-args [finefile-map k command]
  (let [{:strs [defaults]} finefile-map
        {:strs [cleanup shell]} defaults
        {:strs [conclude command max-runs min-runs prepare
                runs setup warmup-runs]} command]
    (concat
      (when shell ["--shell" shell])
      ["--command-name" k]
      (when setup ["--setup" setup])
      (when prepare ["--prepare" prepare])
      (when warmup-runs ["--warmup" (str warmup-runs)])
      (when command [command])
      (when conclude ["--conclude" conclude])
      (when cleanup ["--cleanup" cleanup])
      (if runs
        ["--runs" (str runs)]
        (concat
          (when max-runs ["--max-runs" (str max-runs)])
          (when min-runs ["--min-runs" (str min-runs)]))))))

(defn conform-config
  "Returns config-map conformed to schema. E.g., with default values set."
  [config-map]
  (-> config-map
    (update-in ["defaults" "commands" "min-runs"] #(or % 10))))

(defn select-commands [finefile-map opts]
  (let [{:keys [exclude-tags include-tags]} opts
        {:strs [commands]} finefile-map]
    (for [[k command] commands
          :when (and
                  (or (not exclude-tags)
                    (not (some exclude-tags (get command "tags"))))
                  (or (not include-tags)
                    (some include-tags (get command "tags"))))]
      [k command])))

(def ^:private plot-types->script-bin-names
  {"histogram" "hyperfine-plot-histogram"
   "whisker" "hyperfine-plot-whisker"})

(defn plot->args [m input-file]
  (let [{:strs [file type]} m]
    [(plot-types->script-bin-names type)
     input-file
     "--output" file]))

(defn read-bench-json [reader]
  (json/read reader
    {:value-fn
     (fn [k v]
       (case k
         "exit_codes" (int-array v)
         "times" (double-array v)
         v))}))
