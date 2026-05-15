(ns leiningen.download-test
  (:require [clojure.test :refer [are deftest use-fixtures]]
            [leiningen.download :as lein-dl]
            [ring-http-exchange.core :as server])
  (:import (java.io File)
           (java.nio.file FileVisitResult Files Paths SimpleFileVisitor)))


(defn- delete-recursively [directory]
  (let [path (Paths/get ^String directory (into-array String []))]
    (Files/walkFileTree
      path
      (proxy [SimpleFileVisitor] []
        (visitFile [file _]
          (Files/delete file)
          FileVisitResult/CONTINUE)
        (postVisitDirectory [dir _]
          (Files/delete dir)
          FileVisitResult/CONTINUE)))))


(def handler (fn [req]
               (case (:uri req)
                 "/some/path/to/image" {:body (File. "test/resources/file.jpg")}
                 "/some/path/to/image2.jpeg" {:body (File. "test/resources/file.jpg")}
                 "/with/content/disposition" {:headers {"Content-Disposition" " attachment; filename=\"image-name.jpeg\""}
                                              :body    (File. "test/resources/file.jpg")}
                 "/with/url/encoded/disposition" {:headers {"Content-Disposition" " attachment; filename*=UTF-8''G%C3%B6teborg.jpeg"}
                                                  :body    (File. "test/resources/file.jpg")}
                 "/with/url/encoded/disposition-en" {:headers {"Content-Disposition" "attachment; filename*=UTF-8'en'V%C3%A4xj%C3%B6.jpeg"}
                                                     :body    (File. "test/resources/file.jpg")}
                 "/with/content/disposition/without/filename" {:headers {"Content-Disposition" " attachment; "}
                                                               :body    (File. "test/resources/file.jpg")}

                 "/redirect-image.jpeg" {:body (File. "test/resources/file.jpg")}
                 "/redirect" {:headers {"Location" "/redirect-image.jpeg"} :body "" :status 302}
                 {:body "hello"})))


(use-fixtures :each (fn [f]
                      (let [server (server/run-http-server handler {:port 8080})]
                        (when (.exists ^File (File. "./target"))
                          (delete-recursively "./target"))
                        (f)
                        (server/stop-http-server server))))


(deftest download-test
  (are [uri expected-file-location location]
    (do
      (lein-dl/download {:download [{:url (format "http://localhost:8080%s" uri) :location location}]})
      (.exists (File. ^String expected-file-location)))
    "/some/path/to/image" "./target/image.jpeg" nil
    "/some/path/to/image2.jpeg" "./target/image2.jpeg" nil
    "/with/content/disposition" "./target/image-name.jpeg" nil
    "/with/url/encoded/disposition" "./target/Göteborg.jpeg" nil
    "/with/url/encoded/disposition-en" "./target/Växjö.jpeg" nil
    "/some/path/to/image" "./target/some/path/custom-file-location.jpeg" "./target/some/path/custom-file-location.jpeg"
    "/with/content/disposition/without/filename" "./target/filename.jpeg" nil
    "/redirect" "./target/redirect-image.jpeg" nil))
