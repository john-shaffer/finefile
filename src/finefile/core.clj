(ns finefile.core)

(defn benchmark->hyperfine-args [k benchmark]
  (let [{:strs [cleanup conclude command prepare]} benchmark]
    (concat
      ["--command-name" k]
      (when prepare ["--prepare" prepare])
      (when command [command])
      (when conclude ["--conclude" conclude])
      (when cleanup ["--cleanup" cleanup]))))

(defn finefile-map->hyperfine-args [finefile-map]
  (let [{:strs [benchmarks defaults]} finefile-map
        {:strs [shell]} defaults]
    (concat
      (when shell ["--shell" shell])
      (mapcat
        (fn [[k benchmark]]
          (benchmark->hyperfine-args k (merge defaults benchmark)))
        benchmarks))))
