(ns leiningen.download
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as logger]
            [hato.client :as hato]
            [jj.surykatka :as surykatka])
  (:import (java.io BufferedInputStream File FileOutputStream)
           (java.net URLDecoder)
           (java.util.concurrent Executors Future TimeUnit)))


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


(defn- download-single-file
  "Downloads a single file from the provided download link."
  [download-link http-client]
  (try
    (let [resp (hato/get (format (:url download-link))
                         {:http-client http-client
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
            (clojure.java.io/copy input-stream output-stream))
          {:status :success :url (:url download-link) :location output-location})
        (do
          (logger/info (format "Failed to download from %s. Status: %d" (:url download-link) (:status resp)))
          {:status :failed :url (:url download-link) :reason (format "HTTP %d" (:status resp))})))
    (catch Exception e
      (logger/error (.getMessage ^Exception e))
      (logger/info (format "Error downloading from %s: %s" (:url download-link) (.getMessage ^Exception e)))
      {:status :error :url (:url download-link) :reason (.getMessage ^Exception e)})))


(defn download
  "Downloads files from the provided project's download links in parallel using virtual threads."
  [project & _]
  (let [c (hato/build-http-client {:connect-timeout 10000
                                   :redirect-policy :always})
        executor (Executors/newVirtualThreadPerTaskExecutor)
        download-links (:download project)]
    (try
      (let [futures (mapv (fn [download-link]
                            (.submit executor
                                     ^Callable (fn [] (download-single-file download-link c))))
                          download-links)

            results (mapv (fn [^Future future]
                            (try
                              (.get future)
                              (catch Exception e
                                {:status :error :reason (.getMessage ^Exception e)})))
                          futures)]

        (let [success-count (count (filter #(= :success (:status %)) results))
              failed-count (count (filter #(= :failed (:status %)) results))
              error-count (count (filter #(= :error (:status %)) results))]
          (logger/info (format "\n=== Download Summary ==="))
          (logger/info (format "Successful: %d" success-count))
          (logger/info (format "Failed: %d" failed-count))
          (logger/info (format "Errors: %d" error-count))
          results))

      (finally
        (.shutdown executor)
        (if (not (.awaitTermination executor 60 TimeUnit/SECONDS))
          (do
            (logger/warn "Executor did not terminate in time, forcing shutdown")
            (.shutdownNow executor)))))))