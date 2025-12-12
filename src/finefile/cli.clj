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

(defn- deep-merge
  "Recursively deep merges maps. Treats nil as
   an empty map when merging."
  [& args]
  (if (every? #(or (map? %) (nil? %)) args)
    (apply merge-with deep-merge args)
    (last args)))

(def step-names
  ["setup"
   "prepare"
   "command"
   "conclude"
   "cleanup"])

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
    [["-f" "--file FILE" "Configuration file. Default: \"finefile.toml\". May be specified multiple times, in which case configuration will be merged. Values in later files override values in earlier files."
      :id :config-files
      :multi true
      :default ["finefile.toml"]
      :update-fn (fnil conj [])]
     ["-c" "--include-command COMMAND_NAME"
      "Include a command by name. May be specified multiple times."
      :id :include-commands
      :multi true
      :update-fn (fnil conj #{})]
     ["-C" "--exclude-command COMMAND_NAME"
      "Exclude a command by name. May be specified multiple times."
      :id :exclude-commands
      :multi true
      :update-fn (fnil conj #{})]
     ["-t" "--include-tag TAG"
      "Include only commands with at least one included tag. May be specified multiple times."
      :id :include-tags
      :multi true
      :update-fn (fnil conj #{})]
     ["-T" "--exclude-tag TAG"
      "Exclude commands with at least one excluded tag. May be specified multiple times."
      :id :exclude-tags
      :multi true
      :update-fn (fnil conj #{})]
     [nil "--step STEP"
      "Execute only the given step(s). May be specified multiple times."
      :id :steps
      :multi true
      :update-fn (fnil conj #{})
      :validate
      [#(boolean (some (partial = %) step-names))
       (str "Must be one of: " (str/join ", " step-names))]]]}
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

(defn- destroy-process-tree [^Process p]
  (doseq [handle (-> p .toHandle .descendants .iterator iterator-seq)]
    (.destroy handle)))

(defn- interruptible-exec [opts & args]
  (let [p (apply p/start opts args)
        exit (try (-> p p/exit-ref deref)
               (catch InterruptedException e
                 (destroy-process-tree p)
                 (throw e)))]
    (when-not (zero? exit)
      (throw (RuntimeException. (str "Process failed with exit=" exit))))))

(defn bench-cmds [{:keys [base-dir cmds steps]}]
  (keep
    (fn [cmd]
      (let [{:keys [arg-seq command export-file k]} cmd
            {:strs [dir setup timeout-seconds]} command
            cmd-dir (str (fs/path base-dir (or dir ".")))
            env (some->> (get command "env")
                  (map (fn [[k v]] [k (str v)])))
            shell (get command "shell")
            fut
            (future
              (when (and (steps "setup") (seq setup))
                (apply interruptible-exec
                  {:dir cmd-dir
                   :env env
                   :err :inherit
                   :out :discard}
                  (concat
                    (when (and shell (not= "none" shell))
                      [shell "-c"])
                    [setup])))
              ; We might not have any arg-seq if none of the steps
              ; were selected to be run.
              (when (seq arg-seq)
                (apply interruptible-exec
                  {:dir cmd-dir
                   :env env
                   :err :inherit
                   :out :inherit}
                  "hyperfine"
                  (concat arg-seq
                    ["--export-json" (str export-file)]))))
            result (if timeout-seconds
                     (deref fut (* 1000 timeout-seconds) :not-found)
                     (deref fut))]
        (if (= :not-found result)
          (do
            (future-cancel fut)
            (println k "benchmark timed out after" timeout-seconds "seconds")
            (assoc cmd :status "failed"))
          (assoc cmd :status "succeeded"))))
    cmds))

(defn bench [{:keys [options]}]
  (fs/with-temp-dir [tmpdir {:prefix "finefile"}]
    (let [options (update options :steps #(or % (set step-names)))
          {:keys [config-files steps]} options
          base-config (first config-files)
          base-dir (if (= "-" base-config)
                     (fs/cwd)
                     (fs/parent base-config))
          m (->> config-files
              ; Ensure we only try to read each file once, particularly stdin
              ; Keep the last copy of each filename since that one has merge precedence
              reverse distinct reverse
              (reduce
                (fn [m fname]
                  (let [config-str (if (= "-" fname)
                                     (slurp *in*)
                                     (slurp fname))]
                    (check-config-str config-str options)
                    (deep-merge m (toml/read-string config-str))))
                {})
              core/conform-config)
          command-defaults (get-in m ["defaults" "commands"])
          cmds (map
                 (fn [[k command]]
                   (let [command (merge command-defaults command)
                         command (if (steps "command")
                                   command
                                   ; If we're not running the actual command,
                                   ; just run hyperfine once to run the other steps
                                   (assoc command
                                     "command" "true"
                                     "runs" 1))]
                     {:arg-seq (core/command->hyperfine-args k (dissoc command "setup") options)
                      :command command
                      :export-file (fs/path tmpdir (str (random-uuid) ".json"))
                      :k k}))
                 (core/select-commands m options))
          plots (get m "plots")
          cmds (doall
                 (bench-cmds {:base-dir base-dir :cmds cmds :steps steps}))
          cmds (map
                 (fn [{:as m :keys [export-file status]}]
                   (if (= "succeeded" status)
                     (assoc m :result-map
                       (with-open [rdr (-> export-file fs/file io/reader)]
                         (core/read-bench-json rdr)))
                     m))
                 cmds)]
      (doseq [[export-json cmds] (group-by #(get (:command %) "export-json") cmds)
              :when (seq export-json)
              :let [results (->> cmds (keep :result-map) merge-result-maps)]]
        (with-open [w (io/writer (fs/file base-dir export-json))]
          (json/write results w {:indent true})))
      (when (seq plots)
        (let [results (->> cmds (keep :result-map) merge-result-maps)
              plots-import (fs/path tmpdir (str (random-uuid) ".json"))]
          (with-open [w (io/writer (fs/file plots-import))]
            (json/write results w))
          (doseq [[_k plot] plots]
            (try
              (apply p/exec
                {:err :discard
                 :out :inherit}
                (core/plot->args plot (str (fs/path base-dir plots-import))))
              (catch Exception _
                (apply p/exec
                  {:err :inherit
                   :out :inherit}
                  (core/plot->args plot (str (fs/path base-dir plots-import)))))))))
      (if (some #(not= "succeeded" (:status %)) cmds)
        (System/exit 1)
        (System/exit 0)))))

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
            {:err :inherit
             :in (if (= "-" file) :inherit :pipe)
             :out :inherit}
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
