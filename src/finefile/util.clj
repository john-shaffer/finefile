(ns finefile.util
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as p])
  (:import
   (java.lang ProcessHandle)))

(set! *warn-on-reflection* true)

(defn check-positive!
  "Throws unless v is a positive integer. context names the command or
   comparison the value came from, and k the configuration key."
  [context k v]
  (when-not (and (integer? v) (pos? v))
    (throw (ex-info (str k " must be a positive integer for " (pr-str context)
                      ", got " (pr-str v))
             {:context context :key k :value v}))))

(defn- destroy-process-tree [^Process p]
  (doseq [^ProcessHandle handle (-> p .toHandle .descendants .iterator iterator-seq)]
    (.destroy handle)))

(defn- await-exit [p]
  (try @(p/exit-ref p)
    (catch InterruptedException e
      (destroy-process-tree p)
      (throw e))))

(defn interruptible-exec [opts & args]
  (let [p (apply p/start opts args)
        exit (await-exit p)]
    (if (zero? exit)
      p
      (throw (RuntimeException. (str "Process failed with exit=" exit))))))

(defn exec-lines
  "Starts a process with :out :pipe, drains stdout into a vector of lines,
   then waits for exit. Returns the lines vector. Throws on non-zero exit."
  [opts & args]
  (let [p (apply p/start (assoc opts :out :pipe) args)
        lines (with-open [rdr (-> p p/stdout io/reader)]
                (vec (line-seq rdr)))
        exit (await-exit p)]
    (if (zero? exit)
      lines
      (throw (RuntimeException. (str "Process failed with exit=" exit))))))

(defn command-env [command]
  (some->> (get command "env")
    (map (fn [[k v]] [k (str v)]))))

(defn command-shell [command]
  (let [shell (get command "shell")]
    (when (not= "none" shell)
      shell)))
