(ns build
  (:require
   [clojure.tools.build.api :as b]
   [juxt.pack.cli.api :as pack]
   [deps-deploy.deps-deploy :as deps-deploy]))

(def lib 'com.crypticbutter/snoop)
(def basis (b/create-basis {:project "deps.edn"}))
(def version "21-353-alpha")
(def jar-file (format "target/build/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn pom
  "Remove malli from pom after running"
  [_]
  (b/write-pom
   {;:class-dir class-dir
    :lib lib
    :target ""
    :version version
    :basis basis
    :src-dirs ["src"]}))

(defn jar [_]
  (pack/skinny {:basis basis
                :path jar-file
                :path-coerce :jar}))

(defn deploy [_]
  (deps-deploy/deploy
   {:installer :remote
    :sign-releases? false
    :sign-key-id nil
    :artifact jar-file}))

(defn jardeploy [_]
  (jar nil)
  (deploy nil))
