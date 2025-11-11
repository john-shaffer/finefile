(ns finefile.cli
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [finefile.core :as core]
   [toml-clj.core :as toml])
  (:gen-class))

(def ^:const BIN-NAME "finefile")
(def ^:const BIN-VERSION "0.1.0")

(def cli-spec
  {nil
   {:description
    "A CLI for performing hyperfine benchmarks via TOML configuration."
    :options
    [["-h" "--help"]
     [nil "--version"]]}
   "bench"
   {:description
    "Run the benchmark commands specified in the config file."
    :options
    [["-f" "--file FILE" "Configuration file"
      :default "finefile.toml"]
     ["-h" "--help"]
     ["-t" "--include-tag TAG"
      "Include only commands with at least one included tag. May be specified multiple times."
      :multi true
      :update-fn (fnil conj #{})]]}})

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
                        (:options (cli-spec action))))
        {:keys [options errors]} parsed-opts]
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

(defn bench [{:keys [options]}]
  (let [{:keys [file]} options
        m (with-open [rdr (io/reader file)]
            (toml/read rdr))
        opts {:include-tags (:include-tag options)}]
    (apply p/exec
      {:err :inherit :out :inherit}
      "hyperfine"
      (core/finefile-map->hyperfine-args m opts))
    (doseq [[_k plot] (get m "plots")]
      (apply p/exec
        {:err :discard :out :inherit}
        (core/plot->args plot (get-in m ["defaults" "export-json"]))))))

(defn -main [& args]
  (let [parsed-opts (validate-args args)
        {:keys [action exit-message ok?]} parsed-opts]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "bench" (bench parsed-opts)))))
