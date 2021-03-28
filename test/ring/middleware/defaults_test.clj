(ns ring.middleware.defaults-test
  (:require [clojure.test :refer :all]
            [ring.middleware.defaults :refer :all]
            [ring.util.response :refer [response content-type]]
            [ring.mock.request :refer [request header]]))

(deftest test-wrap-defaults
  (testing "api defaults"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults api-defaults))
          resp    (handler (request :get "/"))]
      (is (= resp {:status 200
                   :headers {"Content-Type" "application/octet-stream"}
                   :body "foo"}))))

  (testing "site defaults"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults site-defaults))
          resp    (handler (request :get "/"))]
      (is (= (:status resp) 200))
      (is (= (:body resp) "foo"))
      (is (= (set (keys (:headers resp)))
             #{"X-Frame-Options"
               "X-Content-Type-Options"
               "X-XSS-Protection"
               "Content-Type"
               "Set-Cookie"}))
      (is (= (get-in resp [:headers "X-Frame-Options"]) "SAMEORIGIN"))
      (is (= (get-in resp [:headers "X-Content-Type-Options"]) "nosniff"))
      (is (= (get-in resp [:headers "X-XSS-Protection"]) "0"))
      (is (= (get-in resp [:headers "Content-Type"]) "application/octet-stream"))
      (let [set-cookie (first (get-in resp [:headers "Set-Cookie"]))]
        (is (.startsWith set-cookie "ring-session="))
        (is (.contains set-cookie "HttpOnly"))
        (is (.contains set-cookie "SameSite=Strict")))))

  (testing "default charset"
    (let [handler (-> (constantly (-> (response "foo") (content-type "text/plain")))
                      (wrap-defaults site-defaults))
          resp    (handler (request :get "/"))]
      (is (= (get-in resp [:headers "Content-Type"]) "text/plain; charset=utf-8"))))

  (testing "middleware overrides"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults
                       (assoc-in site-defaults [:security :frame-options] :deny)))
          resp    (handler (request :get "/"))]
      (is (= (get-in resp [:headers "X-Frame-Options"]) "DENY"))
      (is (= (get-in resp [:headers "X-Content-Type-Options"]) "nosniff"))))

  (testing "disabled middleware"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults
                       (assoc-in site-defaults [:security :frame-options] false)))
          resp    (handler (request :get "/"))]
      (is (nil? (get-in resp [:headers "X-Frame-Options"])))
      (is (= (get-in resp [:headers "X-Content-Type-Options"]) "nosniff"))))

  (testing "ssl redirect (site)"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults secure-site-defaults))
          resp    (handler (request :get "/foo"))]
      (is (= resp {:status 301
                   :headers {"Location" "https://localhost/foo"}
                   :body ""}))))

  (testing "ssl redirect (api)"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults secure-api-defaults))
          resp    (handler (request :get "/foo"))]
      (is (= resp {:status 301
                   :headers {"Location" "https://localhost/foo"}
                   :body ""}))))

  (testing "ssl proxy redirect"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults (assoc secure-site-defaults :proxy true)))
          resp    (handler (-> (request :get "/foo")
                               (header "x-forwarded-proto" "https")))]
      (is (= (:status resp) 200))
      (is (= (:body resp) "foo"))))

  (testing "secure api defaults"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults secure-api-defaults))
          resp    (handler (request :get "https://localhost/foo"))]
      (is (= resp {:status 200
                   :headers {"Content-Type" "application/octet-stream"
                             "Strict-Transport-Security"
                             "max-age=31536000; includeSubDomains"}
                   :body "foo"}))))

  (testing "secure site defaults"
    (let [handler (-> (constantly (response "foo"))
                      (wrap-defaults secure-site-defaults))
          resp    (handler (request :get "https://localhost/"))]
      (is (= (:status resp) 200))
      (is (= (:body resp) "foo"))
      (is (= (set (keys (:headers resp)))
             #{"X-Frame-Options"
               "X-Content-Type-Options"
               "X-XSS-Protection"
               "Strict-Transport-Security"
               "Content-Type"
               "Set-Cookie"}))
      (is (= (get-in resp [:headers "X-Frame-Options"]) "SAMEORIGIN"))
      (is (= (get-in resp [:headers "X-Content-Type-Options"]) "nosniff"))
      (is (= (get-in resp [:headers "X-XSS-Protection"]) "0"))
      (is (= (get-in resp [:headers "Strict-Transport-Security"])
             "max-age=31536000; includeSubDomains"))
      (is (= (get-in resp [:headers "Content-Type"]) "application/octet-stream"))
      (let [set-cookie (first (get-in resp [:headers "Set-Cookie"]))]
        (is (.startsWith set-cookie "secure-ring-session="))
        (is (.contains set-cookie "HttpOnly"))
        (is (.contains set-cookie "Secure")))))

  (testing "proxy headers"
    (let [handler (wrap-defaults response {:proxy true})
          resp    (handler (-> (request :get "/")
                               (header "x-forwarded-proto" "https")
                               (header "x-forwarded-for" "10.0.0.1, 1.2.3.4")))
          body    (:body resp)]
      (is (= (:scheme body) :https))
      (is (= (:remote-addr body) "1.2.3.4"))))

  (testing "nil response"
    (let [handler (wrap-defaults (constantly nil) site-defaults)]
      (is (nil? (handler (request :get "/"))))))

  (testing "single resource path"
    (let [handler (wrap-defaults
                   (constantly nil)
                   (assoc-in site-defaults [:static :resources] "ring/assets/public1"))]
      (is (= (slurp (:body (handler (request :get "/foo.txt")))) "foo1\n"))
      (is (nil? (handler (request :get "/bar.txt"))))))

  (testing "multiple resource paths"
    (let [handler (wrap-defaults
                   (constantly nil)
                   (assoc-in site-defaults
                             [:static :resources]
                             ["ring/assets/public1"
                              "ring/assets/public2"]))]
      (is (= (slurp (:body (handler (request :get "/foo.txt")))) "foo2\n"))
      (is (= (slurp (:body (handler (request :get "/bar.txt")))) "bar\n"))))

  (testing "single file path"
    (let [handler (wrap-defaults
                   (constantly nil)
                   (assoc-in site-defaults [:static :files] "test/ring/assets/public1"))]
      (is (= (slurp (:body (handler (request :get "/foo.txt")))) "foo1\n"))
      (is (nil? (handler (request :get "/bar.txt"))))))

  (testing "multiple file paths"
    (let [handler (wrap-defaults
                   (constantly nil)
                   (assoc-in site-defaults
                             [:static :files]
                             ["test/ring/assets/public1"
                              "test/ring/assets/public2"]))]
      (is (= (slurp (:body (handler (request :get "/foo.txt")))) "foo2\n"))
      (is (= (slurp (:body (handler (request :get "/bar.txt")))) "bar\n"))))

  (testing "async handlers"
    (let [handler (-> (fn [_ respond _] (respond (response "foo")))
                      (wrap-defaults api-defaults))
          resp    (promise)
          ex      (promise)]
      (handler (request :get "/") resp ex)
      (is (not (realized? ex)))
      (is (= @resp {:status 200
                    :headers {"Content-Type" "application/octet-stream"}
                    :body "foo"})))))
