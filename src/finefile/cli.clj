(ns finefile.cli
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [finefile.core :as core]
   [toml-clj.core :as toml])
  (:gen-class))

(def ^:const BIN-NAME "finefile")
(def ^:const BIN-VERSION "0.1.0")

(def global-options
  [[nil "--debug"]
   ["-h" "--help"]])

(def cli-spec
  {nil
   {:description
    "A CLI for performing hyperfine benchmarks via TOML configuration."
    :options
    [[nil "--version"]]}
   "bench"
   {:description
    "Run the benchmark commands specified in the config file."
    :options
    [["-f" "--file FILE" "Configuration file"
      :default "finefile.toml"]
     ["-t" "--include-tag TAG"
      "Include only commands with at least one included tag. May be specified multiple times."
      :multi true
      :update-fn (fnil conj #{})]]}
   "check"
   {:description "Check syntax of a config file."
    :options
    [["-f" "--file FILE" "Configuration file"
      :default "finefile.toml"]]}
   "format"
   {:description "Format a config file."
    :options
    [["-f" "--file FILE" "Configuration file"
      :default "finefile.toml"]]}})

(defn command-usage [action parsed-opts]
  (let [{:keys [description]} (cli-spec action)
        {:keys [summary]} parsed-opts]
    (str/join "\n"
      (concat
        [(str "Usage:\t" BIN-NAME " " (or action "[command]") " [options]")
         nil]
        (when description
          [description
           nil])
        ["Options:"
         summary]
        (when (nil? action)
          (concat
            [nil
             "Commands:"]
            (for [[k {:keys [description]}] cli-spec
                  :when k]
              (str "  " k
                (subs "                  " 0 (- 12 (count k)))
                description))))))))

(defn reorder-help-args
  "Moves one or more help args after the action, if there is one.
   This allows `finefile --help bench` to work the same as
   `finefile bench --help`."
  [args]
  (let [farg (first args)]
    (if (or (= "-h" farg) (= "--help" farg))
      (let [other-args (some->> args next reorder-help-args)]
        (if (some-> (first other-args) (str/starts-with? "-"))
          args
          (cons (first other-args)
            (cons farg (rest other-args)))))
      args)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [args (reorder-help-args args)
        maybe-action (first args)
        action (when-not (or (nil? maybe-action)
                           (str/starts-with? maybe-action "-"))
                 maybe-action)
        action-args (if action (next args) args)
        valid-action? (contains? cli-spec action)
        parsed-opts (when valid-action?
                      (parse-opts action-args
                        (concat
                          (:options (cli-spec action))
                          global-options)))
        {:keys [options errors]} parsed-opts]
    (when (:debug options)
      (print "parsed-opts: ")
      (prn parsed-opts))
    (cond
      (not valid-action?)
      {:exit-message (str "Unknown command: " action)
       :ok? false}

      (seq errors)
      {:exit-message (str/join \newline errors)
       :ok? false}

      (:help options)
      {:exit-message (command-usage action parsed-opts)
       :ok? true}

      (and (:version options) (nil? action))
      {:exit-message (str BIN-NAME " " BIN-VERSION)
       :ok? true}

      (nil? action)
      {:exit-message (command-usage nil parsed-opts)
       :ok? true}

      :else
      (assoc parsed-opts :action action))))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn get-schema-file []
  (let [schema-file (System/getenv "FINEFILE_SCHEMA")]
    (when (seq schema-file)
      (str "file://" schema-file))))

(defn check-config-str [config-str {:keys [debug]}]
  (let [schema-file (get-schema-file)
        _ (when debug
            (println "schema-file: " schema-file))
        args (concat
               ["taplo" "lint" "--no-auto-config" "-"]
               (when (seq schema-file)
                 ["--schema" schema-file]))
        p (apply p/start
            {:err :discard
             :in :pipe
             :out :discard}
            args)
        _ (with-open [stdin (p/stdin p)]
            (io/copy config-str stdin))
        exit @(p/exit-ref p)]
    ; If validation fails, we re-run it so that we can
    ; get taplo's output.
    (when-not (zero? exit)
      (let [p (apply p/start
                {:err :inherit
                 :in :pipe
                 :out :inherit}
                args)]
        (with-open [stdin (p/stdin p)]
          (io/copy config-str stdin)))
      (System/exit @(p/exit-ref p)))))

(defn merge-result-maps [result-maps]
  (->> result-maps
    (map #(get % "results"))
    (reduce into [])
    (hash-map "results")))

(defn bench [{:keys [options]}]
  (fs/with-temp-dir [dir {:prefix "finefile"}]
    (let [{:keys [file]} options
          config-str (slurp file)
          _ (check-config-str config-str options)
          m (toml/read-string config-str)
          opts {:include-tags (:include-tag options)}
          cmds (map
                 (fn [[k command]]
                   {:arg-seq (core/command->hyperfine-args m k (dissoc command "setup"))
                    :command command
                    :export-file (fs/path dir (str (random-uuid) ".json"))})
                 (core/select-commands m opts))
          plots (get m "plots")]
      (doseq [{:keys [arg-seq command export-file]} cmds
              :let [{:strs [setup]} command
                    env (some->> (get command "env")
                          (map (fn [[k v]] [k (str v)])))
                    shell (get command "shell" "bash")]]
        (when (seq setup)
          (apply p/exec
            {:env env
             :err :inherit
             :out :discard}
            (concat
              (when shell
                [shell "-c"])
              [setup])))
        (apply p/exec
          {:env env
           :err :inherit
           :out :inherit}
          "hyperfine"
          (concat arg-seq
            ["--export-json" (str export-file)])))
      (let [cmds (map
                   (fn [{:as m :keys [export-file]}]
                     (assoc m :result-map
                       (with-open [rdr (-> export-file fs/file io/reader)]
                         (core/read-bench-json rdr))))
                   cmds)]
        (doseq [[export-json cmds] (group-by #(get (:command %) "export-json") cmds)
                :when (seq export-json)
                :let [results (->> cmds (map :result-map) merge-result-maps)]]
          (with-open [w (io/writer (fs/file export-json))]
            (json/write results w {:indent true})))
        (when (seq plots)
          (let [results (->> cmds (map :result-map) merge-result-maps)
                plots-import (fs/path dir (str (random-uuid) ".json"))]
            (with-open [w (io/writer (fs/file plots-import))]
              (json/write results w))
            (doseq [[_k plot] plots]
              (apply p/exec
                {:err :discard
                 :out :inherit}
                (core/plot->args plot (str plots-import))))))))))

(defn check [{:keys [options]}]
  (let [{:keys [debug file]} options
        schema-file (get-schema-file)
        _ (when debug
            (println "schema-file: " schema-file))
        args (concat
               ["taplo" "lint" "--no-auto-config" file]
               (when schema-file
                 ["--schema" schema-file]))
        p (apply p/start
            {:err :inherit :out :inherit}
            args)
        exit @(p/exit-ref p)]
    (when-not (zero? exit)
      (System/exit exit))))

(defn fmt [{:keys [options]}]
  (let [{:keys [file]} options
        p (p/start
            {:err :inherit :out :inherit}
            "taplo" "format" "--no-auto-config" file)
        exit @(p/exit-ref p)]
    (when-not (zero? exit)
      (System/exit exit))))

(defn -main [& args]
  (let [parsed-opts (validate-args args)
        {:keys [action exit-message ok?]} parsed-opts]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "bench" (bench parsed-opts)
        "check" (check parsed-opts)
        "format" (fmt parsed-opts)))))
