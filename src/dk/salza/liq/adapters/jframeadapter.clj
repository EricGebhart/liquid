(ns dk.salza.liq.adapters.jframeadapter
  (:require [dk.salza.liq.tools.util :as util]
            [dk.salza.liq.renderer :as renderer]
            [dk.salza.liq.editor :as editor]
            [dk.salza.liq.window :as window]
            [dk.salza.liq.logging :as logging]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.awt Font Color GraphicsEnvironment Dimension GraphicsDevice Window]
           [java.awt.event InputEvent KeyListener ComponentListener WindowAdapter]
           [java.awt.image BufferedImage]
           [javax.swing JFrame ImageIcon JPanel]))


(def ^:private frame (atom nil))
(def ^:private panel (atom nil))
(def ^:private pane (atom nil))
(def ^:private old-lines (atom {}))
(def ^:private updater (atom (future nil)))
(def ^:private rows (atom 46))
(def ^:private columns (atom 160))

(defn- is-windows
  []
  (re-matches #"(?i)win.*" (System/getProperty "os.name")))

(defn- hexcolor
  [h]
  (Color/decode (str "0x" h)))

(defn convert-colormap
  "Convert hex values in colormap to
  type java.awt.Color."
  [m]
  (reduce (fn [r [k v]] (assoc r k (hexcolor v))) {} m))

(def colors (atom {}))
(def bgcolors (atom {}))

(def fontsize (atom 14))

(def font (atom nil))

(defn list-fonts
  "Output a list of all fonts"
  []
  (str/join "\n"
    (.getAvailableFontFamilyNames
      (GraphicsEnvironment/getLocalGraphicsEnvironment))))

(defn- update-font
  ([f]
    (reset! font
      (let [allfonts (into #{} (.getAvailableFontFamilyNames (GraphicsEnvironment/getLocalGraphicsEnvironment)))
            myfonts (concat
                      (when f (list f))
                        (list
                        "Lucida Sans Typewriter"
                        "Consolas"
                        "monospaced"
                        "Inconsolata"
                        "DejaVu Sans Mono"
                        "Ubuntu Mono"
                        "Courier"
                      ))]
        (Font. (some allfonts myfonts) Font/PLAIN @fontsize))))
  ([] (update-font nil)))

(def ^:private fontwidth (atom 8))
(def ^:private fontheight (atom 18))

(defn- view-draw
  []
  (.repaint @panel))

(defn- view-handler
  [key reference old new]
  (when (future-done? @updater)
    (reset! updater
      (future
        (editor/quit-on-exception
         (loop [u @editor/updates]
           (view-draw)
           (when (not= u @editor/updates)
             (recur @editor/updates))))))))

(defn- toggle-fullscreen
  []
  (let [ge (GraphicsEnvironment/getLocalGraphicsEnvironment)
        screen (.getDefaultScreenDevice ge)]
      (when (.isFullScreenSupported screen)
        (if (.getFullScreenWindow screen)
          (.setFullScreenWindow screen nil)
          (.setFullScreenWindow screen @frame)))))

(defn- model-update
  [input]
  (logging/log "INPUT" input)
  (if (= input "f11")
    (toggle-fullscreen)
    (future
      (editor/handle-input input))))

(defn- draw-char
  [g ch row col color bgcolor]
  (let [w @fontwidth
        h @fontheight]
    (.setColor g (@bgcolors bgcolor))
    (.fillRect g (* col w) (* (- row 1) h) w h)
    (.setColor g (@colors color))
    (.drawString g ch (* col w) (- (* row h) (quot @fontsize 4) 1))))

(defn- draw
  [g]
  (let [lineslist (renderer/render-screen)]
    (.setFont g @font)
    (when (editor/fullupdate?)
      (reset! old-lines {})
      (reset! colors (convert-colormap (editor/setting :jframe-colors)))
      (reset! bgcolors (convert-colormap (editor/setting :jframe-bgcolors)))
      (.setColor g (@bgcolors :plain))
      (.fillRect g 0 0 (.getWidth @panel) (.getHeight @panel))
      (.setColor g (@bgcolors :statusline))
      (.fillRect g 0 (* (- @rows 1) @fontheight) (.getWidth @panel) @fontheight))
    (doseq [line (apply concat lineslist)]
      (let [row (line :row)
            column (line :column)
            content (line :line)
            key (str "k" row "-" column)
            oldcontent (@old-lines key)]
          (when (not= oldcontent content)
            (let [oldcount (count (filter #(and (string? %) (not= % "")) oldcontent))]
              (loop [c oldcontent offset 0]
                (when (not-empty c)
                  (let [ch (first c)]
                    (draw-char g " " row (+ column offset) :plain :plain)
                    (recur (rest c) (+ offset 1)))))
              (draw-char g " " row (- column 1) :plain :statusline)
              (loop [c content offset 0 color :plain bgcolor :plain]
                (when (not-empty c)
                  (let [ch (first c)]

                    (if (or (string? ch) (char? ch))
                      (let [char-sym (cond (= (str ch) "\t") "¬"
                                           (= (str ch) "\r") "ɹ"
                                           true (str ch))]
                        (draw-char g char-sym row (+ column offset) color bgcolor)
                        (recur (rest c) (+ offset 1) color bgcolor))
                      (let [nextcolor (or (ch :face) color)
                            nextbgcolor (or (ch :bgface) bgcolor)]
                        (let [char-sym (cond (= (str (ch :char)) "\t") "¬"
                                             (= (str (ch :char)) "\r") "ɹ"
                                             (ch :char) (str (ch :char))
                                             true "…")] 
                          (draw-char g char-sym row (+ column offset) nextcolor nextbgcolor))
                        (recur (rest c) (+ offset 1) nextcolor nextbgcolor))))))))
        (swap! old-lines assoc key content)))))

(defn- handle-keydown
  [e]
  (let [code (.getExtendedKeyCode e)
        raw (int (.getKeyChar e))
        ctrl (when (.isControlDown e) "C-")
        alt (when (or (.isAltDown e) (.isMetaDown e)) "M-")
        shift (when (.isShiftDown e) "S-")
        key (cond (<= 112 code 123) (str shift ctrl alt "f" (- code 111))
                  (= code 135) "~"
                  (= code 129) "|"
                  (> raw 40000) (str shift (cond
                                  (= code 36) "home"
                                  (= code 35) "end"
                                  (= code 34) "pgdn"
                                  (= code 33) "pgup"
                                  (= code 37) "left"
                                  (= code 39) "right"
                                  (= code 38) "up"
                                  (= code 40) "down"))
                  (and ctrl alt (= raw 36)) "$"
                  (and ctrl alt (= raw 64)) "@"
                  (and ctrl alt (= raw 91)) "["
                  (and ctrl alt (= raw 92)) "\\" ;"
                  (and ctrl alt (= raw 93)) "]"
                  (and ctrl alt (= raw 123)) "{"
                  (and ctrl alt (= raw 125)) "}"
                  (and ctrl (= raw 32)) "C- "
                  ctrl (str ctrl alt (char (+ raw 96)))
                  alt (str ctrl alt (char raw))
                  (= raw 127) "delete"
                  (>= raw 32) (str (char raw))
                  (= raw 8) "backspace"
                  (= raw 9) "\t"
                  (= raw 10) "\n"
                  (= raw 13) "\r"
                  (= raw 27) "esc"
                  true (str (char raw)))]
    (when (and key (not= code 65406) (not= code 16)) (model-update key))))

(defn redraw-frame
  []
  (let [tmpg (.getGraphics (BufferedImage. 40 40 BufferedImage/TYPE_INT_RGB))]
    (.setFont tmpg @font)
    (reset! fontwidth (.stringWidth (.getFontMetrics tmpg) "M"))
    (reset! fontheight (+ (.getHeight (.getFontMetrics tmpg)) 1))
    (let [wndcount (count (editor/get-windows))
          buffername (editor/get-name)]
      (editor/set-frame-dimensions (quot (.getHeight @panel) @fontheight) (quot (.getWidth @panel) @fontwidth))
      (reset! rows (editor/get-frame-rows))
      (reset! columns (editor/get-frame-columns))
      (when (= wndcount 2)
        (editor/split-window-right 0.22)
        (editor/switch-to-buffer "-prompt-")
        (editor/other-window))
      (editor/switch-to-buffer buffername)
      (editor/request-fullupdate)
      (editor/updated)
      (view-draw)
      (Thread/sleep 20)
      (editor/request-fullupdate)
      (editor/updated)
      (view-draw))))

(defn set-font
  [font-name font-size]
  (reset! fontsize font-size)
  (update-font font-name)
  (redraw-frame))

(defn init
  [rowcount columncount & {:keys [font-name font-size]}]
  (when font-size (reset! fontsize font-size))
  (update-font font-name)
  (let [icon (io/resource "liquid.png")
        tmpg (.getGraphics (BufferedImage. 40 40 BufferedImage/TYPE_INT_RGB))]
    (.setFont tmpg @font)
    (reset! fontwidth (.stringWidth (.getFontMetrics tmpg) "M"))
    (reset! fontheight (+ (.getHeight (.getFontMetrics tmpg)) 1))
    (reset! rows rowcount)
    (reset! columns columncount)
    (reset! colors (convert-colormap (editor/setting :jframe-colors)))
    (reset! bgcolors (convert-colormap (editor/setting :jframe-bgcolors)))

    (reset! panel
      (proxy [JPanel] []
        (paintComponent [g]
          (draw g))))
    (.setFocusTraversalKeysEnabled @panel false)
    (.setPreferredSize @panel (Dimension. (* @columns @fontwidth) (* @rows @fontheight)))
    (.setDoubleBuffered @panel true)

    (reset! frame (JFrame. "λiquid"))
    (.setDefaultCloseOperation @frame (JFrame/EXIT_ON_CLOSE))
    (.setContentPane @frame @panel)
    (.setBackground @frame (@bgcolors :plain))
    (.setFocusTraversalKeysEnabled @frame false)
    (.addKeyListener @frame
      (proxy [KeyListener] []
        (keyPressed [e] (handle-keydown e))
        (keyReleased [e] (do))
        (keyTyped [e] (do))))
    (.addWindowListener @frame
      (proxy [WindowAdapter] []
        (windowActivated [e] (editor/request-fullupdate) (view-draw))))
    (.setIconImage @frame (when icon (.getImage (ImageIcon. icon))))
    (.pack @frame)
    (.show @frame)
    (add-watch editor/updates "jframe" view-handler)
    (editor/request-fullupdate)
    (editor/updated)
    (view-draw)
    (Thread/sleep 400)
    (editor/request-fullupdate)
    (editor/updated)
    (view-draw)
    (.addComponentListener @frame
      (proxy [ComponentListener] []
        (componentShown [c] (redraw-frame))
        (componentMoved [c] (redraw-frame))
        (componentHidden [c] (redraw-frame))
        (componentResized [c] (redraw-frame))))))

(defn jframequit
  []
  (System/exit 0))