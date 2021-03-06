(ns powerlaces.boot-cljs-devtools
  {:boot/export-tasks true}
  (:require [boot.core          :as    boot]
            [boot.task.built-in :refer [repl]]
            [boot.util          :as    util]
            [clojure.set        :as    set]
            [clojure.java.io    :as    io]
            [clojure.string     :as    str]))

(def ^:private deps '{:cljs-devtools #{binaryage/devtools} :dirac #{binaryage/dirac}})

(def ^:private preloads {:cljs-devtools 'devtools.preload :dirac 'dirac.runtime.preload})

(defn- add-preload! [lib in-file out-file]
  (let [spec (-> in-file slurp read-string)
        preload (get preloads lib)]
    (when (not= :nodejs (-> spec :compiler-options :target))
      (util/info
       "Adding :preloads %s to %s...\n"
       preload (.getName in-file))
      (io/make-parents out-file)
      (-> spec
          (update-in [:compiler-options :preloads] #(conj % preload))
          pr-str
          ((partial spit out-file))))))

(defn- assert-deps [lib]
  (let [current (->> (boot/get-env :dependencies)
                     (map first)
                     set)
        missing (set/difference (get deps lib) current)]
    (assert (not (seq missing))
            (str "You are missing necessary dependencies for boot-cljs-devtools.\n"
                 "Please add the missing dependencies to your project:\n"
                 (str/join "\n" missing) "\n"))))

(defn- relevant-cljs-edn [prev fileset ids]
  (let [relevant (map #(str % ".cljs.edn") ids)
        f (if ids
            #(boot/by-path relevant %)
            #(boot/by-ext [".cljs.edn"] %))]
    (-> (boot/fileset-diff prev fileset)
        boot/input-files
        f)))

(defn- start-dirac! [config]
  (boot.util/dbug "Starting Dirac...\n")
  (require 'dirac.agent)
  ((resolve 'dirac.agent/boot!) config))

(def nrepl-defaults
  {:port 8230
   :server true
   :middleware ['dirac.nrepl/middleware]})

(boot/deftask cljs-devtools
  "Add Chrome cljs-devtools enhancements for ClojureScript development."
  [b ids BUILD_IDS  #{str} "Only inject devtools into these builds (= .cljs.edn files)"]
  (let [tmp (boot/tmp-dir!)
        prev (atom nil)]
    (assert-deps :cljs-devtools)
    (comp
     (boot/with-pre-wrap fileset
       (doseq [f (relevant-cljs-edn @prev fileset ids)]
         (let [path (boot/tmp-path f)
               in-file (boot/tmp-file f)
               out-file (io/file tmp path)]
           (add-preload! :cljs-devtools in-file out-file)))
       (reset! prev fileset)
       (-> fileset
           (boot/add-resource tmp)
           (boot/commit!))))))

(boot/deftask dirac
  "Add dirac enhancements for ClojureScript development."
  [b ids BUILD_IDS #{str} "Only inject devtools into these builds (= .cljs.edn files)"
   n nrepl-opts NREPL_OPTS edn "Options passed to boot's `repl` task."
   d dirac-opts DIRAC_OPTS edn "Options passed to dirac."]
  (let [tmp (boot/tmp-dir!)
        prev (atom nil)
        nrepl-opts (cond-> (merge nrepl-defaults nrepl-opts)
                     (get-in dirac-opts [:nrepl-server :port]) (assoc :port (get-in dirac-opts [:nrepl-server :port])))
        dirac-opts (cond-> (or dirac-opts {})
                     (:port nrepl-opts) (assoc-in [:nrepl-server :port] (:port nrepl-opts)))
        start-dirac-once (delay (start-dirac! dirac-opts))]
    (util/dbug "Normalized nrepl-opts %s\n" nrepl-opts)
    (util/dbug "Normalize dirac-opts %s\n"dirac-opts)
    (assert-deps :dirac)
    (assert (= (:port nrepl-opts) (get-in dirac-opts [:nrepl-server :port]))
            (format "Nrepl's :port (%s) and Dirac's [:nrepl-server :port] (%s) are not the same."
                    (:port nrepl-opts) (get-in dirac-opts [:nrepl-server :port])))
    (comp
     (boot/with-pre-wrap fileset
       (doseq [f (relevant-cljs-edn @prev fileset ids)]
         (let [path (boot/tmp-path f)
               in-file (boot/tmp-file f)
               out-file (io/file tmp path)]
           (add-preload! :dirac in-file out-file)))
       (reset! prev fileset)
       (-> fileset
           (boot/add-resource tmp)
           (boot/commit!)))
     (apply repl (mapcat identity nrepl-opts))
     (boot/with-pass-thru _
       @start-dirac-once))))

(comment
  (require '[powerlaces.boot-cljs-devtools :as dvt])
  (boot (dvt/cljs-devtools)))
