(ns finefile.util
  (:require
   [clojure.java.process :as p])
  (:import
   (java.lang ProcessHandle)))

(set! *warn-on-reflection* true)

(defn- destroy-process-tree [^Process p]
  (doseq [^ProcessHandle handle (-> p .toHandle .descendants .iterator iterator-seq)]
    (.destroy handle)))

(defn interruptible-exec [opts & args]
  (let [p (apply p/start opts args)
        exit (try (-> p p/exit-ref deref)
               (catch InterruptedException e
                 (destroy-process-tree p)
                 (throw e)))]
    (when-not (zero? exit)
      (throw (RuntimeException. (str "Process failed with exit=" exit))))))

(defn command-env [command]
  (some->> (get command "env")
    (map (fn [[k v]] [k (str v)]))))

(defn command-shell [command]
  (let [shell (get command "shell")]
    (when (not= "none" shell)
      shell)))
