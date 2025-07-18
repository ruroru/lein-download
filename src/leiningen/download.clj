(ns leiningen.download
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [hato.client :as hato]
            [jj.surykatka :as surykatka])
  (:import (java.io BufferedInputStream File FileOutputStream InputStream)
           (java.net URLDecoder)))


(defn- get-url-decoded-file-name [my-str]
  (let [parts (reduce (fn [acc [key value]]
                        (assoc acc key value))
                      {}
                      (map vector [:charset :language :value]
                           (str/split my-str #"\'")))]
    (URLDecoder/decode ^String (:value parts)
                       ^String (:charset parts))))


(defn- get-file-name [^BufferedInputStream input-stream headers uri]
  (let [header-map (into {} (map (fn [[k v]] [(clojure.string/lower-case (name k)) v]) headers))]
    (cond
      (contains? header-map "content-disposition")
      (let [content-disposition-value (get header-map "content-disposition")]
        (if (str/includes? content-disposition-value "filename")
          (let [name (-> (str/split content-disposition-value #";")
                         second
                         (str/split #"=")
                         second)]

            (if (.contains ^String content-disposition-value ";")
              (if (.startsWith ^String name "\"")
                (-> (.replace ^String name \" \ )
                    .strip)
                (get-url-decoded-file-name name))
              (get-file-name input-stream {} uri)))
          (get-file-name input-stream {} uri)))

      :else
      (do
        (.mark ^BufferedInputStream input-stream 512)
        (let [extension (surykatka/get-file-type input-stream)]
          (.reset ^BufferedInputStream input-stream)
          (let [file-name (second (re-find #".*\/([^\/]+)$" uri))]
            (if (str/includes? file-name ".")
              file-name
              (if (nil? extension)
                (format "%s" file-name)
                (format "%s.%s" file-name
                        (name extension))))))))))


(defn- get-output-file-path [new-file]
  (let [file (File. ^String new-file)
        parent-file (-> (.getCanonicalFile file)
                        .getParentFile)]
    (.mkdirs (io/file (.getPath ^File parent-file)))
    new-file))


(defn download
  "Downloads files from the provided project's download links."
  [project & _]
  (let [c (hato/build-http-client {:connect-timeout 10000
                                   :redirect-policy :always})]
    (doseq [download-link (:download project)]
      (try
        (let [resp (hato/get (format (:url download-link))
                             {:http-client c
                              :as          :stream})]
          (if (= 200 (:status resp))
            (let [input-stream (BufferedInputStream. (:body resp) 4096)
                  output-location (if
                                    (not (nil? (:location download-link)))
                                    (get-output-file-path (:location download-link))
                                    (get-output-file-path (format "./target/%s"
                                                                  (get-file-name input-stream
                                                                                 (:headers resp)
                                                                                 (:uri resp)))))]
              (with-open [output-stream (FileOutputStream. ^String output-location)
                          input-stream input-stream]

                (clojure.java.io/copy input-stream output-stream)))
            (println (format "Failed to download from %s." download-link))))
        (catch Exception e
          (logger/error (.getMessage ^Exception e))
          (println (format "Error downloading from %s." download-link)))))))
