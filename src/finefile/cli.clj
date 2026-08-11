(ns finefile.cli
  (:require
   [babashka.fs :as fs]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [finefile.compare :as cmp]
   [finefile.core :as core]
   [finefile.http.bench :as http-bench]
   [finefile.util :as u]
   [toml-clj.core :as toml])
  (:gen-class))

(set! *warn-on-reflection* true)

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
   "alpha.compare"
   {:description
    "Determine whether two http benchmark commands actually differ."
    :options
    [["-f" "--file FILE" "Configuration file. Default: \"finefile.toml\". May be specified multiple times, in which case configuration will be merged. Values in later files override values in earlier files."
      :id :config-files
      :multi true
      :update-fn (fnil conj [])]
     ["-c" "--include-comparison NAME"
      "Include a comparison by name. May be specified multiple times."
      :id :include-comparisons
      :multi true
      :update-fn (fnil conj #{})]
     ["-C" "--exclude-comparison NAME"
      "Exclude a comparison by name. May be specified multiple times."
      :id :exclude-comparisons
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
                (subs "                  " 0 (- 16 (count k)))
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

(defn config-files->base-dir [config-files]
  (let [base-config (first config-files)]
    (if (= "-" base-config)
      (fs/cwd)
      (fs/parent base-config))))

(defn read-config
  "Reads and merges config-files, checks each against the schema, and conforms
   the result. Values in later files override values in earlier ones."
  [config-files options]
  (->> config-files
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
    core/conform-config))

(defn merge-result-maps [result-maps]
  (->> result-maps
    (map #(get % "results"))
    (reduce into [])
    (hash-map "results")))

(defn bench-cmds [{:keys [base-dir cmds steps]}]
  (keep
    (fn [cmd]
      (let [{:keys [arg-seq command export-file k]} cmd
            {:strs [dir setup timeout-seconds]} command
            cmd-dir (str (fs/path base-dir (or dir ".")))
            env (u/command-env command)
            shell (u/command-shell command)
            fut
            (future
              (when (and (steps "setup") (seq setup))
                (apply u/interruptible-exec
                  {:dir cmd-dir
                   :env env
                   :err :inherit
                   :out :discard}
                  (concat
                    (when shell
                      [shell "-c"])
                    [setup])))
              ; We might not have any arg-seq if none of the steps
              ; were selected to be run.
              (when (seq arg-seq)
                (apply u/interruptible-exec
                  {:dir cmd-dir
                   :env env
                   :err :inherit
                   :out :inherit}
                  "hyperfine"
                  (concat arg-seq
                    ["--export-json" (str export-file)])))
              (when (and (steps "command") (http-bench/http-command? command))
                (http-bench/bench base-dir k command)))
            [outcome result] (try
                               [:ok (if timeout-seconds
                                      (deref fut (* 1000 timeout-seconds) :not-found)
                                      (deref fut))]
                               (catch Throwable t
                                 ; deref wraps anything thrown by the future in
                                 ; an ExecutionException.
                                 [:error (or (ex-cause t) t)]))]
        (cond
          (= :error outcome)
          (do
            (future-cancel fut)
            (println k "benchmark failed:" (ex-message result))
            (assoc cmd :status "failed"))

          (= :not-found result)
          (do
            (future-cancel fut)
            (println k "benchmark timed out after" timeout-seconds "seconds")
            (assoc cmd :status "failed"))

          :else
          (assoc cmd
            :result-map (if (seq result)
                          {"results" [result]}
                          (when (seq arg-seq)
                            (with-open [rdr (-> export-file fs/file io/reader)]
                              (core/read-bench-json rdr))))
            :status "succeeded"))))
    cmds))

(defn bench [{:keys [options]}]
  (fs/with-temp-dir [tmpdir {:prefix "finefile"}]
    (let [options (update options :steps #(or % (set step-names)))
          {:keys [config-files steps]} options
          config-files (or (seq config-files) ["finefile.toml"])
          base-dir (config-files->base-dir config-files)
          m (read-config config-files options)
          command-defaults (get-in m ["defaults" "commands"])
          cmds (->> (core/select-commands m options)
                 (map
                   (fn [[k command]]
                     (let [command (merge command-defaults command)
                           _ (when (and (get command "command")
                                     (http-bench/http-command? command))
                               (throw (ex-info
                                        (str "Command " (pr-str k)
                                          " defines both command and alpha.http")
                                        {:command-name k})))
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
                        :k k})))
                 (sort-by :k))
          plots (get m "plots")
          cmds (doall
                 (bench-cmds {:base-dir base-dir :cmds cmds :steps steps}))]
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

(defn- run-command-step! [base-dir k command step]
  (when-let [cmd (get command step)]
    (apply u/interruptible-exec
      {:dir (str (fs/path base-dir (or (get command "dir") ".")))
       :env (u/command-env command)
       :err :inherit
       :out :discard}
      (concat
        (when-let [shell (u/command-shell command)]
          [shell "-c"])
        [cmd]))
    (println (format "  %s %s: done" k step))))

(defn compare-comparisons
  "Runs each selected comparison and returns a vector of
   {:comparison, :k, :result-map, :status}.

   A comparison that throws fails on its own without stopping the others, the
   same way a failed benchmark does. The setup and cleanup of each compared
   command run once around the whole comparison; prepare and conclude are not
   applied, since a comparison drives its own runs rather than hyperfine's."
  [{:keys [base-dir commands comparisons]}]
  (mapv
    (fn [[k comparison]]
      (let [sides (distinct (keep #(get comparison %) ["a" "b"]))]
        (try
          (try
            (doseq [side sides]
              (run-command-step! base-dir side (get commands side) "setup"))
            (assoc
              {:comparison comparison :k k}
              :result-map (cmp/compare! base-dir k comparison commands)
              :status "succeeded")
            (finally
              (doseq [side sides]
                (try
                  (run-command-step! base-dir side (get commands side) "cleanup")
                  (catch Throwable t
                    (println k "cleanup failed:" (ex-message t)))))))
          (catch Throwable t
            (println k "comparison failed:" (ex-message t))
            {:comparison comparison :k k :status "failed"}))))
    comparisons))

(defn merge-compare-maps [result-maps]
  {"comparisons" (into [] (mapcat #(get % "comparisons")) result-maps)
   "results" (into [] (mapcat #(get % "results")) result-maps)})

(defn write-compare-exports!
  "Writes each comparison's results to its export-json file, merging the
   comparisons that share one."
  [base-dir cmps]
  (doseq [[export-json cmps] (group-by #(get (:comparison %) "export-json") cmps)
          :when (seq export-json)
          :let [results (->> cmps (keep :result-map) merge-compare-maps)]]
    (with-open [w (io/writer (fs/file base-dir export-json))]
      (json/write results w {:indent true}))))

(defn compare-action [{:keys [options]}]
  (let [{:keys [config-files]} options
        config-files (or (seq config-files) ["finefile.toml"])
        base-dir (config-files->base-dir config-files)
        m (read-config config-files options)
        command-defaults (get-in m ["defaults" "commands"])
        commands (into {}
                   (map (fn [[k command]] [k (merge command-defaults command)]))
                   (get m "commands"))
        comparisons (sort-by first (core/select-comparisons m options))
        cmps (compare-comparisons
               {:base-dir base-dir
                :commands commands
                :comparisons comparisons})]
    (when (empty? comparisons)
      (println "No comparisons to run. Define them under [alpha.compare.<name>]."))
    (write-compare-exports! base-dir cmps)
    (if (some #(not= "succeeded" (:status %)) cmps)
      (System/exit 1)
      (System/exit 0))))

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
      (try
        (case action
          "bench" (bench parsed-opts)
          "alpha.compare" (compare-action parsed-opts)
          "check" (check parsed-opts)
          "format" (fmt parsed-opts))
        (catch clojure.lang.ExceptionInfo e
          (if (:debug (:options parsed-opts))
            (throw e)
            (exit 1 (ex-message e))))))))
