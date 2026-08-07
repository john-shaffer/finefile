(ns finefile.test-runner
  (:require
   [clojure.string :as str]
   [clojure.test :as t])
  (:import
   (java.io File)
   (java.util.jar JarFile))
  (:gen-class))

(defn- path->ns-sym [path]
  (-> path
    (str/replace "_" "-")
    (str/replace "/" ".")
    (str/replace #"\.clj$" "")
    symbol))

(defn- test-nses-in-jar [^File f]
  (with-open [jar (JarFile. f)]
    (->> (enumeration-seq (.entries jar))
      (keep #(let [n (.getName %)]
               (when (str/ends-with? n "_test.clj")
                 (path->ns-sym n))))
      vec)))

(defn- test-nses-in-dir [^File root]
  (let [prefix (str (.getPath root) "/")]
    (->> (file-seq root)
      (keep #(let [p (.getPath %)]
               (when (and (str/starts-with? p prefix)
                          (str/ends-with? p "_test.clj"))
                 (path->ns-sym (subs p (count prefix)))))))))

(defn- find-test-namespaces []
  (->> (str/split (System/getProperty "java.class.path") #":")
    (mapcat (fn [entry]
              (let [f (File. entry)]
                (cond
                  (str/ends-with? entry ".jar") (test-nses-in-jar f)
                  (.isDirectory f) (test-nses-in-dir f)
                  :else []))))
    distinct
    vec))

(defn -main [& _]
  (let [nses (find-test-namespaces)]
    (doseq [ns-sym nses]
      (require ns-sym))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (shutdown-agents)
      (System/exit (if (pos? (+ fail error)) 1 0)))))
