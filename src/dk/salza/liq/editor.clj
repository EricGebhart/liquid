(ns dk.salza.liq.editor
  (:require [dk.salza.liq.buffer :as buffer]
            [dk.salza.liq.window :as window]
            [dk.salza.liq.tools.util :as util]
            [dk.salza.liq.clojureutil :as clojureutil]
            [clojure.java.io :as io]
            [dk.salza.liq.coreutil :refer :all]
            [clojure.string :as str]))

(def top-of-window (atom {})) ; Keys are windowname-buffername
(def default-highlighter (atom nil))
(def default-keymap (atom nil))
(def default-app (atom nil))
(def searchstring (atom ""))
(def macro-seq (atom '()))
(def full-gui-update (atom false))
(def updates (atom 0))

(def macro-record (atom false))

(def editor (ref {::buffers '()
                  ::windows '()
                  ::global-keymap {}
                  ::file-eval {}
                  ::settings {::searchpaths '()
                              ::files '()
                              ::snippets '()
                              ::commands '()
                              ::interactive '()}}))

(defn request-full-gui-update
  []
  (reset! full-gui-update true))

(defn updated
  []
  (swap! updates inc))


(defn check-full-gui-update
  []
  (let [res @full-gui-update]
    (reset! full-gui-update false)
    res))

(defn set-default-keymap
  [keymap]
  (reset! default-keymap keymap))

(defn set-default-highlighter
  [highlighter]
  (reset! default-highlighter highlighter))

(defn set-default-app
  [app]
  (reset! default-app app))

(defn doto-buffer
  "Apply the given function to the top-most buffer."
  [fun & args]
  (dosync
    (alter editor update ::buffers #(apply doto-first (list* % fun args))))
  nil)

(defn current-buffer [] (-> @editor ::buffers first))
(defn current-window [] (-> @editor ::windows first))

(defn setting [keyw] (-> @editor ::settings keyw))
(defn add-to-setting
  [keyw entry]
  (when (not (some #{entry} (setting keyw)))
    (dosync (alter editor update-in [::settings keyw] conj entry))))

(defn set-setting
  [keyw value]
  (dosync (alter editor assoc-in [::settings keyw] value)))

(defn set-global-key
  [keyw fun]
  (dosync (alter editor assoc-in [::global-keymap keyw] fun)) nil)

(defn set-eval-function
  [extension fun]
  (dosync (alter editor assoc-in [::file-eval extension] fun)) nil)

(defn add-command [fun] (add-to-setting ::commands fun) nil)
(defn add-searchpath [s] (add-to-setting ::searchpaths s) nil)
(defn add-snippet [s] (add-to-setting ::snippets s) nil)
(defn add-file [f] (add-to-setting ::files f) nil)

(defn add-interactive
  [label fun & arglabels]
  (add-to-setting ::interactive [label fun arglabels])
  nil)

(defn add-window [window] (dosync (alter editor update ::windows conj window)))
(defn get-windows [] (@editor ::windows))

(defn get-buffer
  [name]
  (first (filter #(= (% ::buffer/name) name) (@editor ::buffers))))

(defn buffer-names
  []
  (map ::buffer/name (@editor ::buffers)))

(defn dirty-buffers
  []
  (map ::buffer/name (filter ::buffer/dirty (@editor ::buffers))))

  
(defn switch-to-buffer
  [buffername]
  (dosync
    (alter editor update ::buffers bump ::buffer/name buffername)
    ;(when (or (= buffername "commandmode") (not (some #{buffername} (map ::window/buffername (editor ::windows)))))
    (let [win (get-match (get-windows) ::window/buffername buffername)]
      (if win
        (alter editor update ::windows bump ::window/buffername buffername)
        (alter editor update ::windows doto-first assoc ::window/buffername buffername)))))
    ;)
  ;; If the buffer is displayed (window ::name) = buffername for some
  ;; window bump that window. Otherwise choose the top window for display

(defn update-mem-col [] (doto-buffer buffer/update-mem-col ((current-window) ::window/columns)))

(defn get-filename [] (-> (current-buffer) buffer/get-filename))
(defn get-folder
  []
  (if-let [filepath (get-filename)]
    (str (.getParent (io/file filepath)))))


(defn get-visible-content [] (-> (current-buffer) buffer/get-visible-content))
(defn insert [string] (doto-buffer buffer/insert string) (update-mem-col))
(defn insert-line [] (doto-buffer buffer/insert-line) (update-mem-col))
(defn forward-char ([amount] (doto-buffer buffer/forward-char amount) (update-mem-col))
                   ([]       (doto-buffer buffer/forward-char 1) (update-mem-col)))
(defn forward-word [] (doto-buffer buffer/forward-word) (update-mem-col))
(defn end-of-word [] (doto-buffer buffer/end-of-word) (update-mem-col))
(defn backward-char ([amount] (doto-buffer buffer/backward-char amount) (update-mem-col))
                    ([]       (doto-buffer buffer/backward-char 1) (update-mem-col)))
(defn delete ([amount] (doto-buffer buffer/delete amount) (update-mem-col))
             ([] (doto-buffer buffer/delete 1) (update-mem-col)))
(defn delete-char [] (doto-buffer buffer/delete-char) (update-mem-col))
(defn replace-char [s] (delete-char) (insert s) (backward-char))
(defn end-of-line [] (doto-buffer buffer/end-of-line) (update-mem-col))
(defn beginning-of-line [] (doto-buffer buffer/beginning-of-line) (update-mem-col))
(defn beginning-of-buffer [] (doto-buffer buffer/beginning-of-buffer) (update-mem-col))
(defn end-of-buffer [] (doto-buffer buffer/end-of-buffer) (update-mem-col))
(defn clear [] (doto-buffer buffer/clear))
(defn selection-set [] (doto-buffer buffer/set-mark "selection"))
(defn selection-cancel [] (doto-buffer buffer/remove-mark "selection"))
(defn selection-toggle
  []
  (if (buffer/get-mark (current-buffer) "selection")
    (selection-cancel)
    (selection-set)))
(defn select-sexp-at-point [] (doto-buffer buffer/select-sexp-at-point))
(defn undo [] (doto-buffer buffer/undo))

(defn get-selection [] (-> (current-buffer) buffer/get-selection))
(defn get-content [] (-> (current-buffer) buffer/get-content))
(defn sexp-at-point [] (-> (current-buffer) buffer/sexp-at-point))
(defn get-context [] (-> (current-buffer) buffer/get-context))
(defn get-line [] (-> (current-buffer) buffer/get-line))
(defn get-char [] (-> (current-buffer) buffer/get-char))
(defn get-name [] (-> (current-buffer) buffer/get-name))
(defn get-point [] (-> (current-buffer) buffer/get-point))
(defn end-of-buffer? [] (-> (current-buffer) buffer/end-of-buffer?))

(defn forward-line
  []
  (doto-buffer buffer/forward-visual-line ((current-window) ::window/columns)))

(defn backward-line
  []
  (doto-buffer buffer/backward-visual-line ((current-window) ::window/columns)))

(defn forward-page
  []
  (let [towid (window/get-towid (current-window) (current-buffer))]
    (doto-buffer buffer/set-point (or (@top-of-window towid)  0))
    (dotimes [n ((current-window) ::window/rows)] (forward-line))
    (beginning-of-line)
    (swap! top-of-window assoc towid (get-point))))

(defn top-align-page
  []
  (let [towid (window/get-towid (current-window) (current-buffer))]
    (beginning-of-line)
    (swap! top-of-window assoc towid (get-point))))


(defn other-window
  []
  (let [buffername (-> @editor ::windows second (window/get-buffername))]
    (dosync (alter editor update ::windows bump 1)
            (alter editor update ::buffers bump ::buffer/name buffername))))

(defn previous-buffer
  []
  (switch-to-buffer (-> @editor ::buffers second ::buffer/name)))

(defn previous-real-buffer
  []
  (when-let [previous (first (filter (fn [x] (not (re-find #"^-" x))) (rest (buffer-names))))]
    (switch-to-buffer previous)))

(defn set-keymap
  [keymap]
  (doto-buffer buffer/set-keymap keymap))

(defn get-keymap
  []
  (buffer/get-keymap (current-buffer)))

(defn set-highlighter
  [highlighter]
  (doto-buffer buffer/set-highlighter highlighter))

(defn get-highlighter
  []
  (buffer/get-highlighter (current-buffer)))

(defn get-action
  [keyw]
  (or (buffer/get-action (current-buffer) keyw)
      (-> @editor ::global-keymap keyw)))

(defn new-buffer
  [name]
  (when (not (get-buffer name))
    (dosync
      (alter editor update ::buffers conj (buffer/create name))))
  (switch-to-buffer name)
  (set-highlighter @default-highlighter)
  (set-keymap @default-keymap))

(defn find-file
  [filepath]
  (@default-app filepath))

(defn create-buffer-from-file
  [filepath]
  (if (not (get-buffer filepath))
    (do
      (dosync
        (alter editor update ::buffers conj (buffer/create-from-file filepath)))
      (switch-to-buffer filepath)
      (set-keymap @default-keymap)
      (set-highlighter @default-highlighter))
    (switch-to-buffer filepath)))

(defn save-file [] (doto-buffer buffer/save-buffer))

(defn copy-selection
  []
  (when-let [r (get-selection)]
    (util/set-clipboard-content r)
    true))

(defn copy-line
  []
  (when-let [r (get-line)]
    (util/set-clipboard-content r)))

(defn delete-line
  []
  (copy-line)
  (doto-buffer buffer/delete-line)
  (update-mem-col))

(defn delete-selection
  []
  (when (copy-selection)
    (doto-buffer buffer/delete-selection)
    (update-mem-col)
    true))

(defn paste
  []
  (insert (util/clipboard-content)))

(defn swap-line-down
  []
  (delete-line)
  (insert-line)
  (paste)
  (beginning-of-line))

(defn swap-line-up
  []
  (delete-line)
  (backward-line)
  (backward-line)
  (insert-line)
  (paste)
  (beginning-of-line))

(defn prompt-append
  [string]
  (switch-to-buffer "-prompt-")
  (insert (str string "\n"))
  (previous-buffer))

(defn prompt-input
  [string]
  (switch-to-buffer "-prompt-")
  (end-of-buffer)
  (insert (str "\n" string))
  (backward-char 2))

(defn prompt-set
  [string]
  (switch-to-buffer "-prompt-")
  (clear)
  (insert string)
  (previous-buffer))

(defn evaluate-file-raw
  ([filepath]
    (try (load-file filepath)
      (catch Exception e (prompt-set (util/pretty-exception e)))))
  ([] (when-let [filepath (get-filename)] (evaluate-file-raw filepath))))

(defn evaluate-file
  ([filepath]
    (let [extension (or (re-find #"(?<=\.)\w*$" filepath) :empty)
          fun (or ((@editor ::file-eval) extension) ((@editor ::file-eval) :default))
          output (when fun
                   (try
                     (with-out-str
                       (println
                         (fun filepath)))
                    (catch Exception e (util/pretty-exception e))))]
        (prompt-set (str/trim (or output "")))))
  ([] (when-let [filepath (get-filename)] (evaluate-file filepath))))

(defn eval-sexp
  [sexp]
  (let [isprompt (= (get-name) "-prompt-")]
    (when isprompt (other-window))
    (let [output (try
                   (if sexp (with-out-str (println (load-string (str "(do (in-ns 'user) " sexp ")")))) "")
                   (catch Exception e (do (spit "/tmp/liq.log" e) (util/pretty-exception e))))]
      (when (and (not= output "") (not isprompt)) (prompt-set output))))
  (request-full-gui-update))

(defn eval-safe
  [fun]
  (let [output (try
                 (with-out-str (fun))
                 (catch Exception e (do (spit "/tmp/liq.log" e) (util/pretty-exception e))))]
      (when output (prompt-set output))))


(defn eval-last-sexp
  []
  (eval-sexp (sexp-at-point)))

(defn search
  []
  (prompt-input (str "(editor/find-next \"""\")")))

(defn find-next
  ([search]
    (reset! searchstring search)
    (doto-buffer buffer/find-next search)
    (update-mem-col))
  ([]
    (doto-buffer buffer/find-next @searchstring)
    (update-mem-col)))

(defn top-next-headline
  []
  (find-next "\n# ")
  (forward-char 1)
  (top-align-page))
  
(defn goto-definition
  [funname]
  (if (re-find #"/" funname)
    (let [[alias funstr] (str/split funname #"/")
          filepath (clojureutil/get-file-path (clojureutil/get-class-path (current-buffer) alias))]
      (find-file filepath)
      (goto-definition funstr))
  (do
    (beginning-of-buffer)
    (find-next (str "(defn " "" funname)))))

(defn get-available-functions
  []
  (let [content (get-content)]
    (concat 
      (apply concat
             (for [[fullmatch nsmatch aliasmatch] (re-seq #"\[([^ ]*) :as ([^]]*)\]" content)]
               (when (resolve (symbol aliasmatch))
                 (for [f (keys (ns-publics (symbol nsmatch)))]
                   (str "(" aliasmatch "/" f ")")))))
      (map #(str "(" % ")") (keys (ns-publics (symbol "clojure.core")))))))

(defn context-action
  []
  (let [context (get-context)
        type (context :type)
        value (context :value)]
    (prompt-set (str context))
    (prompt-append (str "TYPE:  " "" type))
    (prompt-append (str "VALUE: " "" value))
    (cond (= type :file) (if (.isAbsolute (io/file value))
                           (find-file value)
                           (find-file (str (io/file (get-folder) value))))
          (= type :url) (when-let [start-browser (setting :start-browser)]
                          (start-browser value))
          (= type :function) (goto-definition value))))

(defn remove-buffer
  [buffername]
  (dosync
    (alter editor update ::buffers remove-item ::buffer/name buffername)))

(defn kill-buffer
  []
  (let [buffername (get-name)]
    (when (not (buffer/get-dirty (get-buffer buffername)))
      (previous-real-buffer)
      (remove-buffer buffername)
   )
  ))

(defn tmp-test
  []
  (prompt-append "Testing")
  ;(doto-buffer buffer/tmp-buffer ((current-window) ::window/columns))
  ;(prompt-append (clojureutil/get-file-path "dk.salza.liq.editor"))
  ;(prompt-append (clojureutil/get-class-path (current-buffer) "window"))
  ;(prompt-append (str/join "\n" (get-available-functions)))
  (prompt-append (str "--" (get-context) "--"))
  ;(prompt-append (str "--" (get-line) "--"))
  ;(prompt-append (str "--" (get-selection) "--"))
  ;(find-file "/tmp/something.txt")
  )


(def submap (atom nil))

(defn escape
  []
  (selection-cancel)
  (reset! submap nil))

(defn force-quit
  []
  (print "\033[0;37m\033[2J")
  (print "\033[?25h")
  (flush)
  (util/cmd "/bin/sh" "-c" "stty -echo cooked </dev/tty")
  (util/cmd "/bin/sh" "-c" "stty -echo sane </dev/tty")
  (Thread/sleep 100)
  (println "")
  (Thread/sleep 100)
  (System/exit 0))

(defn quit
  []
  (let [dirty (dirty-buffers)]
        (if (empty? dirty)
          (force-quit)
          (prompt-set (str "There are dirty buffers:\n\n"
                           (str/join "\n" dirty) "\n\n"
                           "Press C-M-q to quit anyway.")))))

(defn handle-input
  [keyw]
  (when (and @macro-record (not= keyw :H))
    (swap! macro-seq conj keyw))
  (let [action (if @submap (@submap keyw) (get-action keyw))]
    (cond (map? action) (reset! submap action)
          action (do (reset! submap nil)
                     (action))
          :else (reset! submap nil))
    (updated)))

(defn record-macro
  []
  (if (not @macro-record)
    (do
      (prompt-append "Recording macro")
      (reset! macro-seq '()))
    (prompt-append "Recording finished"))
  (swap! macro-record not))

(defn run-macro
  []
  (when (not @macro-record)
    (doall (map handle-input (reverse @macro-seq)))))

(defn tmp-do-macro
  []
  (doall (map handle-input '(:i :i :j))))