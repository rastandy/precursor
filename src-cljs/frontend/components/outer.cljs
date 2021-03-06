(ns frontend.components.outer
  (:require [cemerick.url :as url]
            [clojure.set :as set]
            [clojure.string :as str]
            [cljs.reader :as reader]
            [cljs-http.client :as http]
            [datascript.core :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.doc-viewer :as doc-viewer]
            [frontend.components.document-access :as document-access]
            [frontend.components.landing :as landing]
            [frontend.config :as config]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.labs.userAgent.browser :as ua]
            [goog.string :as gstring]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import [goog.ui IdGenerator]))

(defn submit-subdomain-form [app owner]
  (go
    (om/update-state! owner (fn [s]
                              (assoc s :submitting? true :error nil)))
    ;; we wouldn't typically use ajax in a component--it's not advisable in
    ;; this case, but we're short on time
    ;; important to get the state out of owner since we're not re-rendering on update
    (let [{:keys [subdomain]} (om/get-state owner)
          res (<! (http/post "/api/v1/create-team" {:edn-params (merge {:subdomain subdomain}
                                                                       (when (get-in app state/dn-discount-path)
                                                                         {:coupon-code "designer-news"}))
                                                    :headers {"X-CSRF-Token" (utils/csrf-token)}}))]
      (if (:success res)
        (om/update-state! owner (fn [s]
                                  (assoc s
                                         :submitting? false
                                         :submitted? true
                                         :error nil
                                         :team-created? true
                                         :team (-> res :body reader/read-string :team))))
        (do
          ;; handle already taken subdomains
          (om/update-state!
           owner
           (fn [s]
             (assoc s
                    :submitting? false
                    :error [:p (some-> res :body reader/read-string :msg)])))
          (put! (om/get-shared owner [:comms :errors]) [:api-error res]))))))

(defn team-signup [app owner]
  (reify
    om/IDisplayName (display-name [_] "Team Signup")
    om/IInitState (init-state [_] {:subdomain ""
                                   :error nil
                                   :submitting? false
                                   :submitted? false
                                   :team-created? false
                                   :team nil})
    om/IRenderState
    (render-state [_ {:keys [subdomain submitting? error submitted? team-created? team]}]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            disabled? (or submitting? (not (utils/logged-in? owner)))]
        (html
         [:div.page-trial.page-form
          {:class (str (get-in app [:navigation-data :type] " team ")
                       (when team-created? " granted "))}
          [:div.content
           [:div.outer-form-info
            [:h1.outer-form-heading
             "What should we call your team? "
             "Grab your domain to get started. "]
            (if (utils/logged-in? owner)
              [:p.outer-form-copy
               "Your team domain needs more than 3 characters, starting with a letter. "
               "Don't have any teammates? "
               "That's okay, solo teams are also welcome! "]
              [:p.outer-form-copy
               "To get started, you'll just need to sign in with your Google account. "
               "After that, we'll ask you to create a name for your team domain. "])
            (when-not (utils/logged-in? owner)
              [:div.outer-form-sign
               (om/build common/google-login {:source "Team Signup Form"})])]
           [:div.outer-form
            {:class (str (when disabled? "disabled ")
                         (when submitting? "submitting ")
                         (when submitted? "submitted "))}
            [:div.subdomain-input
             [:div.subdomain-input-prepend
              {:tab-index "1"
               :ref "subdomain"
               :content-editable true
               :on-key-down #(when (= "Enter" (.-key %))
                               (.preventDefault %)
                               ;; If they hit enter, submit the form
                               (submit-subdomain-form app owner))
               :on-input #(om/set-state-nr! owner :subdomain (goog.dom/getRawTextContent (.-target %)))}
              subdomain]
             [:div.subdomain-input-placeholder
              {:data-prepend "Your team"
               :data-start " name..."
               :data-busy " name is"
               :data-end "'s domain will be"}]
             [:div.subdomain-input-append
              {:on-click #(.focus (om/get-node owner "subdomain"))}
              ".precursorapp.com"]]
            [:div.calls-to-action
             [:button.bubble-button
              {:tab-index "5"
               :ref "submit-button"
               :disabled (or disabled? submitted?)
               :on-click #(submit-subdomain-form app owner)}
              (cond submitting?
                    (html
                      [:span "Setting up your team"
                       [:i.loading-ellipses
                        [:i "."]
                        [:i "."]
                        [:i "."]]])

                    submitted? [:a.trial-success
                                {:target "_self"
                                 :href (auth/auth-url :redirect-path (str "/document/" (:team/intro-doc team) "/roster")
                                                      :source "create-team-trial"
                                                      :subdomain (:team/subdomain team)
                                                      :login-hint (om/get-shared owner [:cust :cust/email]))}
                                (str (str (:team/subdomain team) "." config/hostname)
                                     " is ready, let's go!")]

                    :else "Start for free.")]]

            (when error
              [:div.error error])

            (when team-created?
              [:div.outer-form-granted
               [:p "New documents made on this team domain will be private by default. "
                   "Enjoy your two weeks of unlimited access. "
                   "We're excited to have you! "]])]]])))))

(defn pricing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Pricing Page")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html
         [:div.pricing
          [:div.content
           [:div.price-blocks
            [:div.price-block.price-solo
             [:div.price-head
              [:h2.price-heading.content-copy
               "Public"]]
             [:div.price-body
              [:h4.content-copy
               "Free for everyone."]
              [:p.price-copy.content-copy
               "Why are the prototyping tools free? "
               "Prototyping should just be simple, and to prove it we made Precursor accessible to anyone, anywhere. "
               "Make something & show someone. "]]
             [:div.price-foot
              [:a.bubble-button {:href "/new"}
               [:span "Make a public doc."]]]]
            [:section.price-divide.left
             [:div.price-divide-line]]
            [:div.price-block.price-team
             [:div.price-head
              [:h2.price-heading.content-copy
               "Teams"]]
             [:div.price-body
              [:h4.content-copy
               "$10 per user/month."]
              [:p.price-copy.content-copy
               "Need more than just prototyping? "
               [:a {:href "/features/team"
                    :target "_top"}
                "Team domains"]
               " have collaboration tools to improve your productivity. "
               "Start your domain to organize team communications with private docs. "]]
             [:div.price-foot
              [:a.bubble-button (merge {:href "/trial"
                                        :target "_top"}
                                       (when (get-in app state/dn-discount-path)
                                         {:data-bottom "DN 50% discount"
                                          :class " hover dn-pricing-button"}))

               [:span "Start a free trial."]]]]
            [:section.price-divide.right
             [:div.price-divide-line]]
            [:div.price-block.price-corp
             [:div.price-head
              [:h2.price-heading.content-copy
               "Enterprise"]]
             [:div.price-body
              [:h4.content-copy
               "Contact sales."]
              [:p.price-copy.content-copy
               "Looking for a customized solution? "
               "Precursor was engineered to easily set up and run on your own server. "
               "Email sales@precursorapp.com and we will respond immediately."]]
             [:div.price-foot
              [:a.bubble-button {:href "mailto:sales@precursorapp.com?Subject=Enterprise%20Inquiry"}
               [:span "Email a sales rep."]]]]]]])))))

(defn team-features [app owner]
  (reify
    om/IDisplayName (display-name [_] "Team Features Page")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html
          [:div.team-features
            [:div.features-head.content
             [:h1.content-heading
              "Team domains make real-time collaboration more productive. "]
             [:p.content-copy
              "Precursor prototyping is free. "
              "But we're a "
              [:a {:href "/blog/clojure-is-a-product-design-tool" :target "_blank"} "product team ourselves"]
              ", and we know that it takes more than just prototyping to be successful. "
              "That's why we built lightweight collaboration tools, to help your team communicate effectively. "
              "Solve problems with your team in real time. "]
             [:div.calls-to-action
              [:a.bubble-button {:href "/pricing"
                                 :target "_top"}
               [:span "See our team pricing."]]]]
            [:div.feature.art-visible.content
             [:div.feature-story
              [:h2.feature-headline
               [:span "Organize your team."]]
              [:p.feature-copy
               [:span.content-copy
                "Team domains help you collaborate on ideas quickly by remembering your teammates. "
                "New documents created in your team domain are automatically shared with your team. "]]
              [:a.feature-link {:href "/trial"
                                :target "_top"}
               [:span.content-copy "Try a team domain."]]]
             [:div.feature-media.reverse
              [:div.art-frame
               [:div.artwork (common/icon :users)]]]]
            [:div.feature-divider]
            [:div.feature.art-visible.content
             [:div.feature-story
              [:h2.feature-headline
               [:span "Share ideas privately."]]
              [:p.feature-copy
               [:span.content-copy
                "We understand that your team needs its privacy, that's why we built private documents. "
                "You should be able to collaborate with peace of mind, knowing that your ideas are safe."]]
              [:a.feature-link {:href "https://precursor.precursorapp.com/document/17592197569407" :target "_blank"}
               [:span.content-copy "Try a private document."]]]
             [:div.feature-media
              [:div.art-frame
               [:div.artwork (common/icon :lock)]]]]
            [:div.feature-divider]
            [:div.feature.art-visible.content
             [:div.feature-story
              [:h2.feature-headline
               [:span "Chat with voice."]]
              [:p.feature-copy
               [:span.content-copy
                "Sometimes speaking out loud is simply easier than typing messages back and forth. "
                "Teammates on your domain will get unlimited access to voice chat in every document. "]]
              [:a.feature-link {:href "https://precursor.precursorapp.com/document/17592197569418" :target "_blank"}
               [:span.content-copy "Try a voice chat."]]]
             [:div.feature-media.reverse
              [:div.art-frame
               [:div.artwork (common/icon :mic)]]]]
            [:div.features-foot.content
             [:h1.content-heading
              "Ready to start?"]
             [:p.content-copy
              "Precursor will become the muse for creativity on your team. "
              "Design wireframes, develop prototypes, and collaborate on any idea in real time. "
              "Use built-in chat to keep feedback and research in one place. "
              "Teams around the world are already collaborating on ideas "
              [:a {:href "/blog/ideas-are-made-with-precursor" :target "_blank"} "made with Precursor"]
              "."]
             [:div.calls-to-action
              [:a.bubble-button {:href "/trial"
                                 :target "_top"}
               [:span "Start a free trial."]]]]])))))

(defn nav-head [app owner]
  (om/component
   (let [cast! (om/get-shared owner :cast!)]
     (html
      [:div.nav.nav-head
       [:a.nav-link.nav-logo
        (merge {:href "/"
                :title "Precursor"}
               (when (utils/logged-in? owner)
                 {:on-click #(cast! :launch-app-clicked {:analytics-data {:source "top-left-nav"}})
                  :on-mouse-enter #(cast! :navigate-to-landing-doc-hovered)}))
        "Precursor"]
       [:a.nav-link.nav-home
        {:href "/home"
         :title "Home"}
        "Home"]
       [:a.nav-link.nav-pricing
        {:href "/pricing"
         :title "Pricing"}
        "Pricing"]
       [:a.nav-link.nav-blog
        {:href "/blog"
         :title "Blog"
         :target "_self"}
        "Blog"]
       (if (utils/logged-in? owner)
         [:a.nav-link.nav-app
          {:role "button"
           :title "Launch Precursor"
           :on-click #(cast! :launch-app-clicked {:analytics-data {:source "top-right-nav"}})
           :on-mouse-enter #(cast! :navigate-to-landing-doc-hovered)}
          "App"]
         [:div.nav-link.nav-google
          (om/build common/google-login {:source "Nav" :size :small})])]))))

(defn nav-foot [app owner]
  (om/component
   (let [cast! (om/get-shared owner :cast!)]
     (html
      [:div.nav.nav-foot
       [:a.nav-link.nav-logo
        (merge {:title "Precursor"
                :href "/"
                :target "_top"}
               (when (utils/logged-in? owner)
                 {:on-click #(cast! :launch-app-clicked {:analytics-data {:source "bottom-nav-logo"}})
                  :on-mouse-enter #(cast! :navigate-to-landing-doc-hovered)}))
        (common/icon :logomark)]
       [:a.nav-link.nav-home
        {:title "Home"
         :href "/home"
         :target "_top"}
        "Home"]
       [:a.nav-link.nav-pricing
        {:title "Pricing"
         :href "/pricing"
         :target "_top"}
        "Pricing"]
       [:a.nav-link.nav-blog
        {:title "Blog"
         :href "/blog"
         :target "_self"}
        "Blog"]
       (if (utils/logged-in? owner)
         [:a.nav-link.nav-app
          {:title "Launch Precursor"
           :on-click #(cast! :launch-app-clicked {:analytics-data {:source "bottom-nav"}})
           :on-mouse-enter #(cast! :navigate-to-landing-doc-hovered)
           :role "button"}
          "App"]
         [:a.nav-link {:title "Sign in with Google"
                       :href (auth/auth-url :source "bottom-nav")
                       :role "button"}
          "Sign in"])
       [:a.nav-link.nav-twitter
        {:title "@PrecursorApp"
         :href "https://twitter.com/PrecursorApp"}
        (common/icon :twitter)]]))))

(def outer-components
  {:landing landing/landing
   :pricing pricing
   :trial team-signup
   :team-features team-features})

(defn outer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Outer")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            nav-point (:navigation-point app)
            component (get outer-components nav-point landing/landing)]
        (html
          [:div.outer {:class (concat [(str "page-" (name nav-point))]
                                      (when (= (:page-count app) 1) ["entry"])
                                      (when (utils/logged-in? owner) ["logged-in"]))}
           [:div.outer-head (om/build nav-head {})]
           (om/build component app)
           [:div.outer-foot (om/build nav-foot {})]])))))
