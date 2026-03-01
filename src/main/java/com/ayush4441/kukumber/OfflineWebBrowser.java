package com.ayush4441.kukumber;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

/**
 * Kukumber – a JavaFX WebView browser with:
 * <ul>
 *   <li>Auto-save pages to a disk cache (toggle: AutoSave ON/OFF)</li>
 *   <li>Offline mode that loads pages from cache when the network is
 *       unavailable (toggle: Offline ON/OFF)</li>
 *   <li>Built-in ad-blocker that injects CSS/JS once every page loads</li>
 * </ul>
 */
public class OfflineWebBrowser extends Application {

    private static final String HOME_URL = "https://www.google.com";

    private WebView webView;
    private WebEngine webEngine;
    private BrowserCache cache;
    private AdBlocker adBlocker;

    /** Prevents a cache-loaded page from triggering another cache save. */
    private boolean loadingFromCache = false;

    private boolean autoSaveEnabled = false;
    private boolean offlineModeEnabled = false;

    // ── Application entry point ───────────────────────────────────────────────

    @Override
    public void start(Stage primaryStage) {
        cache = new BrowserCache();
        adBlocker = new AdBlocker();

        webView = new WebView();
        webEngine = webView.getEngine();

        // ── toolbar controls ─────────────────────────────────────────────────
        Button backBtn = new Button("◀");
        Button fwdBtn  = new Button("▶");
        Button reloadBtn = new Button("⟳");

        TextField urlBar = new TextField(HOME_URL);
        HBox.setHgrow(urlBar, Priority.ALWAYS);

        Button goBtn = new Button("Go");

        ToggleButton autoSaveToggle   = styledToggle("AutoSave OFF", false);
        ToggleButton offlineModeToggle = styledToggle("Offline OFF", false);

        Label statusLabel = new Label("Ready");
        statusLabel.setPadding(new Insets(0, 6, 0, 6));

        // ── layout ───────────────────────────────────────────────────────────
        HBox navBar = new HBox(4, backBtn, fwdBtn, reloadBtn, urlBar, goBtn);
        navBar.setPadding(new Insets(6));
        navBar.setStyle("-fx-background-color: #f0f0f0;");

        HBox featureBar = new HBox(10, autoSaveToggle, offlineModeToggle, statusLabel);
        featureBar.setPadding(new Insets(0, 6, 6, 6));
        featureBar.setStyle("-fx-background-color: #f0f0f0;");

        VBox topBar = new VBox(navBar, featureBar);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(webView);

        // ── navigation actions ────────────────────────────────────────────────
        goBtn.setOnAction(e -> navigate(urlBar.getText(), statusLabel));
        urlBar.setOnAction(e -> navigate(urlBar.getText(), statusLabel));
        backBtn.setOnAction(e -> webEngine.getHistory().go(-1));
        fwdBtn.setOnAction(e -> webEngine.getHistory().go(1));
        reloadBtn.setOnAction(e -> {
            if (offlineModeEnabled) {
                loadFromCache(webEngine.getLocation(), statusLabel);
            } else {
                webEngine.reload();
            }
        });

        // ── toggle button actions ─────────────────────────────────────────────
        autoSaveToggle.setOnAction(e -> {
            autoSaveEnabled = autoSaveToggle.isSelected();
            autoSaveToggle.setText(autoSaveEnabled ? "AutoSave ON" : "AutoSave OFF");
        });

        offlineModeToggle.setOnAction(e -> {
            offlineModeEnabled = offlineModeToggle.isSelected();
            offlineModeToggle.setText(offlineModeEnabled ? "Offline ON" : "Offline OFF");
            if (offlineModeEnabled) {
                // Immediately try to serve the current page from cache
                String loc = webEngine.getLocation();
                if (loc != null && !loc.isEmpty() && cache.contains(loc)) {
                    loadFromCache(loc, statusLabel);
                }
            }
        });

        // ── sync URL bar with page navigation ────────────────────────────────
        webEngine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null && !newLoc.isEmpty()) {
                urlBar.setText(newLoc);
            }
        });

        // ── load-worker state machine ─────────────────────────────────────────
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {

            if (newState == Worker.State.SCHEDULED && offlineModeEnabled && !loadingFromCache) {
                // Intercept link-click navigation while offline
                String url = (String) webEngine.getLoadWorker().getMessage();
                String loc  = webEngine.getLocation();
                String target = (url != null && !url.isEmpty()) ? url : loc;
                if (target != null && !target.isEmpty()) {
                    Platform.runLater(() -> {
                        webEngine.getLoadWorker().cancel();
                        if (cache.contains(target)) {
                            loadFromCache(target, statusLabel);
                        } else {
                            showOfflinePlaceholder(target, statusLabel);
                        }
                    });
                }
                return;
            }

            if (newState == Worker.State.SUCCEEDED && !loadingFromCache) {
                String loc = webEngine.getLocation();
                // Inject ad-blocking CSS/JS
                adBlocker.injectBlocking(webEngine);

                // Auto-save
                if (autoSaveEnabled && loc != null && !loc.isEmpty()) {
                    try {
                        String html = (String) webEngine.executeScript(
                                "document.documentElement.outerHTML");
                        cache.save(loc, html);
                        statusLabel.setText("Saved: " + shortUrl(loc));
                    } catch (Exception ex) {
                        // page may restrict script access – ignore
                    }
                } else {
                    statusLabel.setText("Loaded: " + shortUrl(loc));
                }
            }

            if (newState == Worker.State.FAILED) {
                String loc = webEngine.getLocation();
                if (loc != null && cache.contains(loc)) {
                    statusLabel.setText("Network failed – loading from cache");
                    loadFromCache(loc, statusLabel);
                } else {
                    statusLabel.setText("Failed to load page");
                }
            }

            if (newState == Worker.State.RUNNING) {
                statusLabel.setText("Loading…");
            }
        });

        // ── title binding ─────────────────────────────────────────────────────
        webEngine.titleProperty().addListener((obs, o, n) ->
                primaryStage.setTitle(n != null && !n.isEmpty() ? n + " — Kukumber" : "Kukumber"));

        // ── show window ──────────────────────────────────────────────────────
        Scene scene = new Scene(root, 1280, 800, Color.WHITE);
        primaryStage.setTitle("Kukumber");
        primaryStage.setScene(scene);
        primaryStage.show();

        navigate(HOME_URL, statusLabel);
    }

    // ── navigation helpers ────────────────────────────────────────────────────

    private void navigate(String url, Label statusLabel) {
        if (url == null || url.isBlank()) return;
        url = url.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("file://")) {
            // Bare domain or search term
            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                url = "https://www.google.com/search?q=" + url.replace(" ", "+");
            }
        }

        if (offlineModeEnabled) {
            if (cache.contains(url)) {
                loadFromCache(url, statusLabel);
            } else {
                showOfflinePlaceholder(url, statusLabel);
            }
            return;
        }

        webEngine.load(url);
    }

    private void loadFromCache(String url, Label statusLabel) {
        String content = cache.load(url);
        if (content != null) {
            loadingFromCache = true;
            webEngine.loadContent(content);
            loadingFromCache = false;
            statusLabel.setText("Cached: " + shortUrl(url));
            adBlocker.injectBlocking(webEngine);
        }
    }

    private void showOfflinePlaceholder(String url, Label statusLabel) {
        statusLabel.setText("Offline – not cached");
        webEngine.loadContent(
                "<!DOCTYPE html><html><head><title>Offline</title>" +
                "<style>body{font-family:sans-serif;max-width:600px;margin:80px auto;text-align:center;}" +
                "h1{color:#e55;} a{color:#05a;}</style></head><body>" +
                "<h1>Offline Mode</h1>" +
                "<p>The page <strong>" + escapeHtml(url) + "</strong> is not in the cache.</p>" +
                "<p>Disable <em>Offline Mode</em> to load it from the network, " +
                "then enable <em>AutoSave</em> so future visits are cached.</p>" +
                "</body></html>");
    }

    // ── static utilities ──────────────────────────────────────────────────────

    private static ToggleButton styledToggle(String text, boolean selected) {
        ToggleButton tb = new ToggleButton(text);
        tb.setSelected(selected);
        tb.setStyle("-fx-min-width:120px;");
        return tb;
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        return url.length() > 60 ? url.substring(0, 57) + "…" : url;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
