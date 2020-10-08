;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.colorpicker
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [cuerdas.core :as str]
   [app.common.geom.point :as gpt]
   [app.common.math :as math]
   [app.common.uuid :refer [uuid]]
   [app.util.dom :as dom]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.colors :as dwc]
   [app.main.data.modal :as modal]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [t]]))

;; --- Refs

(def picking-color?
  (l/derived :picking-color? refs/workspace-local))

(def picked-color
  (l/derived :picked-color refs/workspace-local))

(def picked-color-select
  (l/derived :picked-color-select refs/workspace-local))

(def picked-shift?
  (l/derived :picked-shift? refs/workspace-local))

(def viewport
  (l/derived (l/in [:workspace-local :vport]) st/state))


;; --- Color Picker Modal

(mf/defc value-saturation-selector [{:keys [hue saturation value on-change]}]
  (let [dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                {:keys [x y]} (-> ev dom/get-client-position)
                px (math/clamp (/ (- x left) (- right left)) 0 1)
                py (* 255 (- 1 (math/clamp (/ (- y top) (- bottom top)) 0 1)))]
            (on-change px py)))]
    [:div.value-saturation-selector
     {:on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-pointer-down (partial dom/capture-pointer)
      :on-pointer-up (partial dom/release-pointer)
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}
     [:div.handler {:style {:pointer-events "none"
                            :left (str (* 100 saturation) "%")
                            :top (str (* 100 (- 1 (/ value 255))) "%")}}]]))


(mf/defc slider-selector [{:keys [value class min-value max-value vertical? reverse? on-change]}]
  (let [min-value (or min-value 0)
        max-value (or max-value 1)
        dragging? (mf/use-state false)
        calculate-pos
        (fn [ev]
          (when on-change
            (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                  {:keys [x y]} (-> ev dom/get-client-position)
                  unit-value (if vertical?
                               (math/clamp (/ (- bottom y) (- bottom top)) 0 1)
                               (math/clamp (/ (- x left) (- right left)) 0 1))
                  unit-value (if reverse?
                               (math/abs (- unit-value 1.0))
                               unit-value)
                  value (+ min-value (* unit-value (- max-value min-value)))]
              (on-change value))))]

    [:div.slider-selector
     {:class (str (if vertical? "vertical " "") class)
      :on-mouse-down #(reset! dragging? true)
      :on-mouse-up #(reset! dragging? false)
      :on-pointer-down (partial dom/capture-pointer)
      :on-pointer-up (partial dom/release-pointer)
      :on-click calculate-pos
      :on-mouse-move #(when @dragging? (calculate-pos %))}

     (let [value-percent (* (/ (- value min-value)
                               (- max-value min-value)) 100)

           value-percent (if reverse?
                           (math/abs (- value-percent 100))
                           value-percent)
           value-percent-str (str value-percent "%")

           style-common #js {:pointerEvents "none"}
           style-horizontal (obj/merge! #js {:left value-percent-str} style-common)
           style-vertical   (obj/merge! #js {:bottom value-percent-str} style-common)]
       [:div.handler {:style (if vertical? style-vertical style-horizontal)}])]))


(defn create-color-wheel
  [canvas-node]
  (let [ctx    (.getContext canvas-node "2d")
        width  (obj/get canvas-node "width")
        height (obj/get canvas-node "height")
        radius (/ width 2)
        cx     (/ width 2)
        cy     (/ width 2)
        step   0.2]

    (.clearRect ctx 0 0 width height)

    (doseq [degrees (range 0 360 step)]
      (let [degrees-rad (math/radians degrees)
            x (* radius (math/cos (- degrees-rad)))
            y (* radius (math/sin (- degrees-rad)))]
        (obj/set! ctx "strokeStyle" (str/format "hsl(%s, 100%, 50%)" degrees))
        (.beginPath ctx)
        (.moveTo ctx cx cy)
        (.lineTo ctx (+ cx x) (+ cy y))
        (.stroke ctx)))

    (let [grd (.createRadialGradient ctx cx cy 0 cx cx radius)]
      (.addColorStop grd 0 "white")
      (.addColorStop grd 1 "rgba(255, 255, 255, 0")
      (obj/set! ctx "fillStyle" grd)

      (.beginPath ctx)
      (.arc ctx cx cy radius 0 (* 2 math/PI) true)
      (.closePath ctx)
      (.fill ctx))))

(mf/defc ramp-selector [{:keys [color on-change]}]
  (let [{hue :h saturation :s value :v alpha :alpha} color

        on-change-value-saturation
        (fn [new-saturation new-value]
          (let [hex (uc/hsv->hex [hue new-saturation new-value])
                [r g b] (uc/hex->rgb hex)]
            (on-change {:hex hex
                        :r r :g g :b b
                        :s new-saturation
                        :v new-value})))

        on-change-hue
        (fn [new-hue]
          (let [hex (uc/hsv->hex [new-hue saturation value])
                [r g b] (uc/hex->rgb hex)]
            (on-change {:hex hex
                        :r r :g g :b b
                        :h new-hue} )))

        on-change-opacity
        (fn [new-opacity]
          (on-change {:alpha new-opacity} ))]
    [:*
     [:& value-saturation-selector
      {:hue hue
       :saturation saturation
       :value value
       :on-change on-change-value-saturation}]

     [:div.shade-selector
      [:div.color-bullet]
      [:& slider-selector {:class "hue"
                           :max-value 360
                           :value hue
                           :on-change on-change-hue}]

      [:& slider-selector {:class "opacity"
                           :max-value 1
                           :value alpha
                           :on-change on-change-opacity}]]]))

(defn color->point
  [canvas-side hue saturation]
  (let [hue-rad (math/radians (- hue))
        comp-x (* saturation (math/cos hue-rad))
        comp-y (* saturation (math/sin hue-rad))
        x (+ (/ canvas-side 2) (* comp-x (/ canvas-side 2)))
        y (+ (/ canvas-side 2) (* comp-y (/ canvas-side 2)))]
    (gpt/point x y)))

(mf/defc harmony-selector [{:keys [color on-change]}]
  (let [canvas-ref (mf/use-ref nil)
        {hue :h saturation :s value :v alpha :alpha} color

        canvas-side 152
        pos-current (color->point canvas-side hue saturation)
        pos-complement (color->point canvas-side (mod (+ hue 180) 360) saturation)
        dragging? (mf/use-state false)

        calculate-pos (fn [ev]
                        (let [{:keys [left right top bottom]} (-> ev dom/get-target dom/get-bounding-rect)
                              {:keys [x y]} (-> ev dom/get-client-position)
                              px (math/clamp (/ (- x left) (- right left)) 0 1)
                              py (math/clamp (/ (- y top) (- bottom top)) 0 1)

                              px (- (* 2 px) 1)
                              py (- (* 2 py) 1)

                              angle (math/degrees (math/atan2 px py))
                              new-hue (math/precision (mod (- angle 90 ) 360) 2)
                              new-saturation (math/clamp (math/distance [px py] [0 0]) 0 1)
                              hex (uc/hsv->hex [new-hue new-saturation value])
                              [r g b] (uc/hex->rgb hex)]
                          (on-change {:hex hex
                                      :r r :g g :b b
                                      :h new-hue
                                      :s new-saturation})))

        on-change-value (fn [new-value]
                          (let [hex (uc/hsv->hex [hue saturation new-value])
                                [r g b] (uc/hex->rgb hex)]
                            (on-change {:hex hex
                                        :r r :g g :b b
                                        :v new-value})))
        on-complement-click (fn [ev]
                              (let [new-hue (mod (+ hue 180) 360)
                                    hex (uc/hsv->hex [new-hue saturation value])
                                    [r g b] (uc/hex->rgb hex)]
                                (on-change {:hex hex
                                            :r r :g g :b b
                                            :h new-hue
                                            :s saturation})))

        on-change-opacity (fn [new-alpha] (on-change {:alpha new-alpha}))]

    (mf/use-effect
     (mf/deps canvas-ref)
     (fn [] (when canvas-ref
              (create-color-wheel (mf/ref-val canvas-ref)))))

    [:div.harmony-selector
     [:div.hue-wheel-wrapper
      [:canvas.hue-wheel
       {:ref canvas-ref
        :width canvas-side
        :height canvas-side
        :on-mouse-down #(reset! dragging? true)
        :on-mouse-up #(reset! dragging? false)
        :on-pointer-down (partial dom/capture-pointer)
        :on-pointer-up (partial dom/release-pointer)
        :on-click calculate-pos
        :on-mouse-move #(when @dragging? (calculate-pos %))}]
      [:div.handler {:style {:pointer-events "none"
                             :left (:x pos-current)
                             :top (:y pos-current)}}]
      [:div.handler.complement {:style {:left (:x pos-complement)
                                        :top (:y pos-complement)
                                        :cursor "pointer"}
                                :on-click on-complement-click}]]
     [:div.handlers-wrapper
      [:& slider-selector {:class "value"
                           :vertical? true
                           :reverse? true
                           :value value
                           :max-value 255
                           :vertical true
                           :on-change on-change-value}]
      [:& slider-selector {:class "opacity"
                           :vertical? true
                           :value alpha
                           :max-value 1
                           :vertical true
                           :on-change on-change-opacity}]]]))

(mf/defc hsva-selector [{:keys [color on-change]}]
  (let [{hue :h saturation :s value :v alpha :alpha} color
        handle-change-slider (fn [key]
                               (fn [new-value]
                                 (let [change (hash-map key new-value)
                                       {:keys [h s v]} (merge color change)
                                       hex (uc/hsv->hex [h s v])
                                       [r g b] (uc/hex->rgb hex)]
                                   (on-change (merge change
                                                     {:hex hex
                                                      :r r :g g :b b})))))
        on-change-opacity (fn [new-alpha] (on-change {:alpha new-alpha}))]
    [:div.hsva-selector
     [:span.hsva-selector-label "H"]
     [:& slider-selector
      {:class "hue" :max-value 360 :value hue :on-change (handle-change-slider :h)}]

     [:span.hsva-selector-label "S"]
     [:& slider-selector
      {:class "saturation" :max-value 1 :value saturation :on-change (handle-change-slider :s)}]

     [:span.hsva-selector-label "V"]
     [:& slider-selector
      {:class "value" :reverse? true :max-value 255 :value value :on-change (handle-change-slider :v)}]

     [:span.hsva-selector-label "A"]
     [:& slider-selector
      {:class "opacity" :max-value 1 :value alpha :on-change on-change-opacity}]]))

(mf/defc color-inputs [{:keys [type color on-change]}]
  (let [{red :r green :g blue :b
         hue :h saturation :s value :v
         hex :hex alpha :alpha} color

        parse-hex (fn [val] (if (= (first val) \#) val (str \# val)))

        refs {:hex   (mf/use-ref nil)
              :r     (mf/use-ref nil)
              :g     (mf/use-ref nil)
              :b     (mf/use-ref nil)
              :h     (mf/use-ref nil)
              :s     (mf/use-ref nil)
              :v     (mf/use-ref nil)
              :alpha (mf/use-ref nil)}

        on-change-hex
        (fn [e]
          (let [val (-> e dom/get-target-val parse-hex)]
            (when (uc/hex? val)
              (let [[r g b] (uc/hex->rgb val)
                    [h s v] (uc/hex->hsv hex)]
                (on-change {:hex val
                            :h h :s s :v v
                            :r r :g g :b b})))))

        on-change-property
        (fn [property max-value]
          (fn [e]
            (let [val (-> e dom/get-target-val (math/clamp 0 max-value))
                  val (if (#{:s} property) (/ val 100) val)]
              (when (not (nil? val))
                (if (#{:r :g :b} property)
                  (let [{:keys [r g b]} (merge color (hash-map property val))
                        hex (uc/rgb->hex [r g b])
                        [h s v] (uc/hex->hsv hex)]
                    (on-change {:hex hex
                                :h h :s s :v v
                                :r r :g g :b b}))

                  (let [{:keys [h s v]} (merge color (hash-map property val))
                        hex (uc/hsv->hex [h s v])
                        [r g b] (uc/hex->rgb hex)]
                    (on-change {:hex hex
                                :h h :s s :v v
                                :r r :g g :b b})))))))

        on-change-opacity
        (fn [e]
          (when-let [new-alpha (-> e dom/get-target-val (math/clamp 0 100) (/ 100))]
            (on-change {:alpha new-alpha})))]


    ;; Updates the inputs values when a property is changed in the parent
    (mf/use-effect
     (mf/deps color type)
     (fn []
       (doseq [ref-key (keys refs)]
         (let [property-val (get color ref-key)
               property-ref (get refs ref-key)]
           (when (and property-val property-ref)
             (when-let [node (mf/ref-val property-ref)]
               (case ref-key
                 (:s :alpha) (dom/set-value! node (math/round (* property-val 100)))
                 :hex (dom/set-value! node property-val)
                 (dom/set-value! node (math/round property-val)))))))))

    [:div.color-values
     [:input {:id "hex-value"
                        :ref (:hex refs)
                        :default-value hex
                        :on-change on-change-hex}]

     (if (= type :rgb)
       [:*
        [:input {:id "red-value"
                 :ref (:r refs)
                 :type "number"
                 :min 0
                 :max 255
                 :default-value red
                 :on-change (on-change-property :r 255)}]

        [:input {:id "green-value"
                 :ref (:g refs)
                 :type "number"
                 :min 0
                 :max 255
                 :default-value green
                 :on-change (on-change-property :g 255)}]

        [:input {:id "blue-value"
                 :ref (:b refs)
                 :type "number"
                 :min 0
                 :max 255
                 :default-value blue
                 :on-change (on-change-property :b 255)}]]
       [:*
        [:input {:id "hue-value"
                 :ref (:h refs)
                 :type "number"
                 :min 0
                 :max 360
                 :default-value hue
                 :on-change (on-change-property :h 360)}]

        [:input {:id "saturation-value"
                 :ref (:s refs)
                 :type "number"
                 :min 0
                 :max 100
                 :step 1
                 :default-value saturation
                 :on-change (on-change-property :s 100)}]

        [:input {:id "value-value"
                 :ref (:v refs)
                 :type "number"
                 :min 0
                 :max 255
                 :default-value value
                 :on-change (on-change-property :v 255)}]])

     [:input.alpha-value {:id "alpha-value"
                          :ref (:alpha refs)
                          :type "number"
                          :min 0
                          :step 1
                          :max 100
                          :default-value (if (= alpha :multiple) "" (math/precision alpha 2))
                          :on-change on-change-opacity}]

     [:label.hex-label {:for "hex-value"} "HEX"]
     (if (= type :rgb)
       [:*
        [:label.red-label {:for "red-value"} "R"]
        [:label.green-label {:for "green-value"} "G"]
        [:label.blue-label {:for "blue-value"} "B"]]
       [:*
        [:label.red-label {:for "hue-value"} "H"]
        [:label.green-label {:for "saturation-value"} "S"]
        [:label.blue-label {:for "value-value"} "V"]])
     [:label.alpha-label {:for "alpha-value"} "A"]]))


(defn as-color-components [value opacity]
  (let [value (if (uc/hex? value) value "#000000")
        [r g b] (uc/hex->rgb value)
        [h s v] (uc/hex->hsv value)]

    {:hex (or value "000000")
     :alpha (or opacity 1)
     :r r :g g :b b
     :h h :s s :v v}))

(mf/defc colorpicker
  [{:keys [value opacity on-change on-accept]}]
  (let [current-color (mf/use-state (as-color-components value opacity))

        active-tab (mf/use-state :ramp #_:harmony #_:hsva)
        selected-library (mf/use-state "recent")
        current-library-colors (mf/use-state [])
        ref-picker (mf/use-ref)

        file-colors (mf/deref refs/workspace-file-colors)
        shared-libs (mf/deref refs/workspace-libraries)
        recent-colors (mf/deref refs/workspace-recent-colors)

        picking-color? (mf/deref picking-color?)
        picked-color (mf/deref picked-color)
        picked-color-select (mf/deref picked-color-select)
        picked-shift? (mf/deref picked-shift?)

        locale    (mf/deref i18n/locale)

        value-ref (mf/use-var value)

        on-change (or on-change identity)

        parse-selected (fn [selected]
                         (if (#{"recent" "file"} selected)
                           (keyword selected)
                           (uuid selected)) )

        change-tab (fn [tab] #(reset! active-tab tab))

        handle-change-color (fn [changes]
                              (swap! current-color merge changes)
                              (when (:hex changes)
                                (reset! value-ref (:hex changes)))
                              (on-change (:hex changes (:hex @current-color))
                                         (:alpha changes (:alpha @current-color))))]

    ;; Update state when there is a change in the props upstream
    (mf/use-effect
     (mf/deps value opacity)
     (fn []
       (reset! current-color (as-color-components value opacity))))

    ;; Updates the CSS color variable when there is a change in the color
    (mf/use-effect
     (mf/deps @current-color)
     (fn [] (let [node (mf/ref-val ref-picker)
                  rgb [(:r @current-color) (:g @current-color) (:b @current-color)]
                  hue-rgb (uc/hsv->rgb [(:h @current-color) 1.0 255])
                  hsl-from (uc/hsv->hsl [(:h @current-color) 0 (:v @current-color)])
                  hsl-to (uc/hsv->hsl [(:h @current-color) 1 (:v @current-color)])

                  format-hsl (fn [[h s l]]
                               (str/fmt "hsl(%s, %s, %s)"
                                        h
                                        (str (* s 100) "%")
                                        (str (* l 100) "%")))]
              (dom/set-css-property node "--color" (str/join ", " rgb))
              (dom/set-css-property node "--hue-rgb" (str/join ", " hue-rgb))
              (dom/set-css-property node "--saturation-grad-from" (format-hsl hsl-from))
              (dom/set-css-property node "--saturation-grad-to" (format-hsl hsl-to)))))

    ;; Load library colors when the select is changed
    (mf/use-effect
     (mf/deps @selected-library)
     (fn []
       (let [mapped-colors
             (cond
               (= @selected-library "recent")
               (map #(hash-map :value %) (reverse (or recent-colors [])))

               (= @selected-library "file")
               (map #(select-keys % [:id :value]) (vals file-colors))

               :else ;; Library UUID
               (map #(merge {:file-id (uuid @selected-library)} (select-keys % [:id :value]))
                    (vals (get-in shared-libs [(uuid @selected-library) :data :colors]))))]
         (reset! current-library-colors (into [] mapped-colors)))))

    ;; If the file colors change and the file option is selected updates the state
    (mf/use-effect
     (mf/deps file-colors)
     (fn [] (when (= @selected-library "file")
              (let [colors (map #(select-keys % [:id :value]) (vals file-colors))]
                (reset! current-library-colors (into [] colors))))))

    ;; When closing the modal we update the recent-color list
    (mf/use-effect
     (fn [] (fn []
              (st/emit! (dwc/stop-picker))
              (when @value-ref
                (st/emit! (dwl/add-recent-color @value-ref))))))

    (mf/use-effect
     (mf/deps picking-color? picked-color)
     (fn [] (when picking-color?
              (let [[r g b] (or picked-color [0 0 0])
                    hex (uc/rgb->hex [r g b])
                    [h s v] (uc/hex->hsv hex)]
                (swap! current-color assoc
                       :r r :g g :b b
                       :h h :s s :v v
                       :hex hex)
                (reset! value-ref hex)
                (when picked-color-select
                  (on-change hex (:alpha @current-color) nil nil picked-shift?))))))

    (mf/use-effect
     (mf/deps picking-color? picked-color-select)
     (fn [] (when (and picking-color? picked-color-select)
              (on-change (:hex @current-color) (:alpha @current-color) nil nil picked-shift?))))

    [:div.colorpicker {:ref ref-picker}
     [:div.colorpicker-content
      [:div.top-actions
       [:button.picker-btn
        {:class (when picking-color? "active")
         :on-click (fn []
                     (modal/allow-click-outside!)
                     (st/emit! (dwc/start-picker)))}
        i/picker]

       [:div.gradients-buttons
        [:button.gradient.linear-gradient #_{:class "active"}]
        [:button.gradient.radial-gradient]]]

      #_[:div.gradient-stops
       [:div.gradient-background {:style {:background "linear-gradient(90deg, #EC0BE5, #CDCDCD)" }}]
       [:div.gradient-stop-wrapper
        [:div.gradient-stop.start {:style {:background-color "#EC0BE5"}}]
        [:div.gradient-stop.end {:style {:background-color "#CDCDCD"
                                         :left "100%"}}]]]

      (if picking-color?
        [:div.picker-detail-wrapper
         [:div.center-circle]
         [:canvas#picker-detail {:width 200 :height 160}]]
        (case @active-tab
          :ramp [:& ramp-selector {:color @current-color :on-change handle-change-color}]
          :harmony [:& harmony-selector {:color @current-color :on-change handle-change-color}]
          :hsva [:& hsva-selector {:color @current-color :on-change handle-change-color}]
          nil))

      [:& color-inputs {:type (if (= @active-tab :hsva) :hsv :rgb) :color @current-color :on-change handle-change-color}]

      [:div.libraries
       [:select {:on-change (fn [e]
                              (let [val (-> e dom/get-target dom/get-value)]
                                (reset! selected-library val)))
                 :value @selected-library}
        [:option {:value "recent"} (t locale "workspace.libraries.colors.recent-colors")]
        [:option {:value "file"} (t locale "workspace.libraries.colors.file-library")]
        (for [[_ {:keys [name id]}] shared-libs]
          [:option {:key id
                    :value id} name])]

       [:div.selected-colors
        (when (= "file" @selected-library)
          [:div.color-bullet.button.plus-button {:style {:background-color "white"}
                                                 :on-click #(st/emit! (dwl/add-color (:hex @current-color)))}
           i/plus])

        [:div.color-bullet.button {:style {:background-color "white"}
                                   :on-click #(st/emit! (dwc/show-palette (parse-selected @selected-library)))}
         i/palette]

        (for [[idx {:keys [id file-id value]}] (map-indexed vector @current-library-colors)]
          [:div.color-bullet {:key (str "color-" idx)
                              :on-click (fn []
                                          (swap! current-color assoc :hex value)
                                          (reset! value-ref value)
                                          (let [[r g b] (uc/hex->rgb value)
                                                [h s v] (uc/hex->hsv value)]
                                            (swap! current-color assoc
                                                   :r r :g g :b b
                                                   :h h :s s :v v)
                                            (on-change value (:alpha @current-color) id file-id)))
                              :style {:background-color value}}])]]]
     [:div.colorpicker-tabs
      [:div.colorpicker-tab {:class (when (= @active-tab :ramp) "active")
                             :on-click (change-tab :ramp)} i/picker-ramp]
      [:div.colorpicker-tab {:class (when (= @active-tab :harmony) "active")
                             :on-click (change-tab :harmony)} i/picker-harmony]
      [:div.colorpicker-tab {:class (when (= @active-tab :hsva) "active")
                             :on-click (change-tab :hsva)} i/picker-hsv]]
     (when on-accept
       [:div.actions
        [:button.btn-primary.btn-large
         {:on-click (fn []
                      (on-accept @value-ref)
                      (modal/hide!))}
         (t locale "workspace.libraries.colors.save-color")]])])
  )

(defn calculate-position
  "Calculates the style properties for the given coordinates and position"
  [{vh :height} position x y]
  (let [;; picker height in pixels
        h 360
        ;; Checks for overflow outside the viewport height
        overflow-fix (max 0 (+ y (- 50) h (- vh)))]
    (cond
      (or (nil? x) (nil? y)) {:left "auto" :right "16rem" :top "4rem"}
      (= position :left) {:left (str (- x 270) "px")
                          :top (str (- y 50 overflow-fix) "px")}
      :else {:left (str (+ x 24) "px")
             :top (str (- y 50 overflow-fix) "px")})))


(mf/defc colorpicker-modal
  {::mf/register modal/components
   ::mf/register-as :colorpicker}
  [{:keys [x y default value opacity page on-change on-close disable-opacity position on-accept] :as props}]
  (let [vport (mf/deref viewport)
        dirty? (mf/use-var false)
        last-change (mf/use-var nil)
        position (or position :left)
        style (calculate-position vport position x y)

        handle-change (fn [new-value new-opacity id file-id shift-clicked?]
                        (when (or (not= new-value value) (not= new-opacity opacity))
                          (reset! dirty? true))
                        (reset! last-change [new-value new-opacity id file-id])
                        (when on-change
                          (on-change new-value new-opacity id file-id shift-clicked?)))]

    (mf/use-effect
     (fn []
       #(when (and @dirty? on-close)
          (when-let [[value opacity id file-id] @last-change]
            (on-close value opacity id file-id)))))

    [:div.colorpicker-tooltip
     {:style (clj->js style)}
     [:& colorpicker {:value (or value default)
                      :opacity (or opacity 1)
                      :on-change handle-change
                      :on-accept on-accept
                      :disable-opacity disable-opacity}]]))

