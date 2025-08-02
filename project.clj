(defproject org.clojars.jj/lein-download "1.0.1-SNAPSHOT"
  :description "Leiningen plugin to download files."
  :url "https://github.com/ruroru/lein-download"
  :license {:name "EPL-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.1"]
                 [org.clojars.jj/surykatka "1.3.2"]
                 [hato "1.0.0"]]

  :deploy-repositories [["clojars" {:url      "https://repo.clojars.org"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass}]]

  :eval-in-leiningen true

  :plugins [[org.clojars.jj/bump "1.0.4"]
            [org.clojars.jj/strict-check "1.0.2"]
            ]
  :profiles {:test {:dependencies [[org.clojars.jj/ring-http-exchange "1.2.1"]]}}
  :repl-options {:init-ns lein.download})
