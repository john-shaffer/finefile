(ns finefile.cli
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as p]
   [finefile.core :as core]
   [toml-clj.core :as toml])
  (:gen-class))

(defn -main [& args]
  (doseq [filename args]
    (let [m (with-open [rdr (clojure.java.io/reader filename)]
              (toml/read rdr))]
      (apply p/exec
        {:err :inherit :out :inherit}
        "hyperfine"
        (core/finefile-map->hyperfine-args m)))))
