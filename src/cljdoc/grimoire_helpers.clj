(ns cljdoc.grimoire-helpers
  (:refer-clojure :exclude [import])
  (:require [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [cljdoc.git-repo :as git]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.config :as cfg]
            [grimoire.api]
            [grimoire.api.fs]
            [grimoire.api.fs.write]
            [grimoire.api.fs.read]
            [grimoire.things]
            [grimoire.util]
            [grimoire.either])
  (:import [org.eclipse.jgit.api Git]))

(def v "0.1.0")

(defn write-docs-for-def
  "General case of writing documentation for a Var instance with
  metadata. Compute a \"docs\" structure from the var's metadata and then punt
  off to write-meta which does the heavy lifting."
  [store def-thing codox-meta]
  (assert (grimoire.things/def? def-thing))
  (let [docs (-> codox-meta
                 (update :name name))]
    (when-not (:name docs)
      (log/error "Var name missing:" docs))
    (assert (:name docs) "Var name was nil!")
    (assert (nil? (:namespace docs)) "Namespace should not get written to def-meta")
    (assert (nil? (:platform docs)) "Platform should not get written to def-meta")
    (spec/assert :cljdoc.spec/def-minimal docs)
    (grimoire.api/write-meta store def-thing docs)))

(defn write-docs-for-ns
  "Function of a configuration and a Namespace which writes namespace metadata
  to the :datastore in config."
  [store ns-thing ns-meta]
  (assert (grimoire.things/namespace? ns-thing))
  (grimoire.api/write-meta store ns-thing ns-meta)
  (log/info "Finished namespace" (:name ns-meta)))

(defn version-thing [project version]
  (-> (grimoire.things/->Group    (cljdoc.util/group-id project))
      (grimoire.things/->Artifact (cljdoc.util/artifact-id project))
      (grimoire.things/->Version  version)))

(defn platform-thing [project version platf]
  (-> (version-thing project version)
      (grimoire.things/->Platform (grimoire.util/normalize-platform platf))))

(defn grimoire-store [^java.io.File dir]
  (grimoire.api.fs/->Config (.getPath dir) "" ""))

(defn import-api [{:keys [platform store codox-namespaces]}]
  (grimoire.api/write-meta store platform {})
  (let [namespaces codox-namespaces]
    (doseq [ns namespaces
            :let [publics  (:publics ns)
                  ns-thing (grimoire.things/->Ns platform (-> ns :name name))]]
      (write-docs-for-ns store ns-thing (dissoc ns :publics))
      (doseq [public publics]
        (try
          (write-docs-for-def store
                              (grimoire.things/->Def ns-thing (-> public :name name))
                              public)
          (catch Throwable e
            (throw (ex-info "Failed to write docs for def"
                            {:codox/public public}
                            e))))))))

(defn import-doc
  [{:keys [version store ^Git git-repo]}]
  {:pre [(grimoire.things/version? version)
         (some? store)
         (some? git-repo)]}
  (let [git-dir   (.. git-repo getRepository getWorkTree)]
    (log/info "Writing bare meta for" (grimoire.things/thing->path version))
    (doto store
      (grimoire.api/write-meta (grimoire.things/thing->group version) {})
      (grimoire.api/write-meta (grimoire.things/thing->artifact version) {})
      (grimoire.api/write-meta version
                               {:scm (->> (grimoire.things/thing->name version)
                                          (git/read-repo-meta git-repo))
                                :doc (some->> (or (get-in (cfg/config :defaults)
                                                          [:cljdoc/hardcoded
                                                           (->> (grimoire.things/thing->artifact version)
                                                                (grimoire.things/thing->name))
                                                           :cljdoc.doc/tree])
                                                  (doctree/derive-toc git-dir))
                                              (doctree/process-toc git-dir))}))))

(defn import
  [{:keys [cljdoc-edn grimoire-dir git-repo]}]
  ;; TODO assert format of cljdoc-edn
  (let [project (-> cljdoc-edn :pom :project)
        version (-> cljdoc-edn :pom :version)
        store   (grimoire-store grimoire-dir)]
    ;; TODO logging
    (import-doc {:version (version-thing project version)
                 :store store
                 :git-repo git-repo})

    ;; TODO logging
    (doseq [platf (keys (:codox cljdoc-edn))]
      (assert (#{"clj" "cljs"} platf) (format "was %s" platf))
      (import-api {:platform (platform-thing project version platf)
                   :store store
                   :codox-namespaces (get-in cljdoc-edn [:codox platf])}))))

(comment
  (build-grim {:group-id "bidi"
               :artifact-id  "bidi"
               :version "2.1.3"
               :platform "clj"}
              "target/jar-contents/" "target/grim-test")

  (->> (#'codox.main/read-namespaces
        {:language     :clojure
         ;; :root-path    (System/getProperty "user.dir")
         :source-paths ["target/jar-contents/"]
         :namespaces   :all
         :metadata     {}
         :exclude-vars #"^(map)?->\p{Upper}"}))

  (let [c (grimoire.api.fs/->Config "target/grim-test" "" "")]
    (build-grim "bidi" "bidi" "2.1.3" "target/jar-contents/" "target/grim-test")
    #_(write-docs-for-var c (var bidi.bidi/match-route)))

  (resolve (symbol "bidi.bidi" "match-route"))

  (var->src (var bidi.bidi/match-route))
  ;; (symbol (subs (str (var bidi.bidi/match-route)) 2))
  ;; (clojure.repl/source-fn 'bidi.bidi/match-route)
  ;; (var (symbol "bidi.bidi/match-route"))

  )
