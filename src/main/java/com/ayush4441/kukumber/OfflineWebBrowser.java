package com.ayush4441.kukumber;

import me.friwi.jcefmaven.CefAppBuilder;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefStringVisitor;
import org.cef.handler.*;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Kukumber – a JCEF (Java Chromium Embedded Framework) + Swing browser with:
 * <ul>
 *   <li>Network-level ad-blocking via {@code CefRequestHandler}</li>
 *   <li>DOM-level ad-blocking via CSS/JS injection after every page load</li>
 *   <li>Auto-save pages to a disk cache (toggle: AutoSave ON/OFF)</li>
 *   <li>Offline mode that serves pages from cache (toggle: Offline ON/OFF)</li>
 * </ul>
 *
 * <p>First launch downloads the CEF binaries into {@code ~/.kukumber/jcef-bundle/}.</p>
 * <p>Run with: {@code mvn exec:java}</p>
 */
public class OfflineWebBrowser extends JFrame {

    private static final Logger LOG = Logger.getLogger(OfflineWebBrowser.class.getName());
    private static final String HOME_URL = "https://www.google.com";

    private final CefApp cefApp;
    private final CefClient cefClient;
    private final CefBrowser cefBrowser;
    private final BrowserCache cache;
    private final AdBlocker adBlocker;

    /** Set before loadURL(data:...) calls; cleared in onLoadEnd to avoid re-saving cached pages. */
    private volatile boolean loadingFromCache = false;

    private volatile boolean autoSaveEnabled = false;
    private volatile boolean offlineModeEnabled = false;

    // UI components referenced in handlers (initialised in buildUI before handlers fire)
    private JTextField urlBar;
    private JLabel statusLabel;
    private JToggleButton autoSaveToggle;
    private JToggleButton offlineModeToggle;

    // \u2500\u2500 Constructor \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    public OfflineWebBrowser() throws Exception {
        super("Kukumber");
        cache = new BrowserCache();
        adBlocker = new AdBlocker();

        // Initialise JCEF – downloads CEF on first run into ~/.kukumber/jcef-bundle/
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(
                new File(System.getProperty("user.home"),
                        ".kukumber" + File.separator + "jcef-bundle"));
        builder.getCefSettings().windowless_rendering_enabled = false;
        cefApp = builder.build();
        cefClient = cefApp.createClient();

        registerHandlers();

        cefBrowser = cefClient.createBrowser(HOME_URL, false, false);
        buildUI(cefBrowser.getUIComponent());
    }

    // \u2500\u2500 CEF event handlers \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    private void registerHandlers() {

        // \u2500\u2500 Load handler: ad injection + autosave + loading state \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        cefClient.addLoadHandler(new CefLoadHandlerAdapter() {

            @Override
            public void onLoadingStateChange(CefBrowser browser, boolean isLoading,
                    boolean canGoBack, boolean canGoForward) {
                if (isLoading) {
                    SwingUtilities.invokeLater(() -> {
                        if (statusLabel != null) statusLabel.setText("Loading\u2026");
                    });
                }
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (!frame.isMain()) return;
                String url = browser.getURL();

                if (loadingFromCache) {
                    // Cache-load path: inject blocker and clear flag; don't update URL bar
                    loadingFromCache = false;
                    adBlocker.injectBlocking(browser, url);
                    return;
                }

                // Normal network load
                adBlocker.injectBlocking(browser, url);
                SwingUtilities.invokeLater(() -> {
                    if (urlBar != null) urlBar.setText(url);
                });

                if (autoSaveEnabled && url != null && !url.isBlank()) {
                    final String savedUrl = url;
                    browser.getSource(new CefStringVisitor() {
                        @Override
                        public void visit(String source) {
                            cache.save(savedUrl, source);
                            SwingUtilities.invokeLater(() -> {
                                if (statusLabel != null)
                                    statusLabel.setText("Saved: " + shortUrl(savedUrl));
                            });
                        }
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        if (statusLabel != null)
                            statusLabel.setText("Loaded: " + shortUrl(url));
                    });
                }
            }

            @Override
            public void onLoadError(CefBrowser browser, CefFrame frame,
                    CefLoadHandler.ErrorCode errorCode, String errorText, String failedUrl) {
                if (!frame.isMain() || loadingFromCache) return;
                SwingUtilities.invokeLater(() -> {
                    if (cache.contains(failedUrl)) {
                        if (statusLabel != null)
                            statusLabel.setText("Network failed \u2013 loading from cache");
                        loadFromCache(failedUrl);
                    } else if (statusLabel != null) {
                        statusLabel.setText("Error: " + errorText);
                    }
                });
            }
        });

        // \u2500\u2500 Request handler: offline interception + network ad-blocking \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        cefClient.addRequestHandler(new CefRequestHandlerAdapter() {

            /**
             * Intercept navigation in offline mode: cancel the live request and
             * serve from cache (or show a placeholder if the page is not cached).
             */
            @Override
            public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame,
                    CefRequest request, boolean userGesture, boolean isRedirect) {
                if (!offlineModeEnabled || loadingFromCache) return false;
                String url = request.getURL();
                if (url == null || url.startsWith("data:")
                        || url.startsWith("about:") || url.startsWith("chrome-error:")) {
                    return false;
                }
                // Cancel live navigation; serve from cache on the EDT
                SwingUtilities.invokeLater(() -> {
                    if (cache.contains(url)) {
                        loadFromCache(url);
                    } else {
                        showOfflinePlaceholder(url);
                    }
                });
                return true; // cancel the original request
            }

            /**
             * Block sub-resource requests whose domain belongs to a known
             * ad or tracking network.
             */
            @Override
            public CefResourceRequestHandler getResourceRequestHandler(
                    CefBrowser browser, CefFrame frame, CefRequest request,
                    boolean isNavigation, boolean isDownload, String requestInitiator,
                    BoolRef disableDefaultHandling) {
                if (!adBlocker.shouldBlock(request.getURL())) return null;
                return new CefResourceRequestHandlerAdapter() {
                    @Override
                    public boolean onBeforeResourceLoad(CefBrowser b, CefFrame f, CefRequest r) {
                        return true; // true = block/cancel the request
                    }
                };
            }
        });

        // \u2500\u2500 Display handler: keep URL bar and window title in sync \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        cefClient.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                SwingUtilities.invokeLater(() -> {
                    if (urlBar != null && url != null && !loadingFromCache) urlBar.setText(url);
                });
            }

            @Override
            public void onTitleChange(CefBrowser browser, String title) {
                SwingUtilities.invokeLater(() ->
                        setTitle(title != null && !title.isEmpty()
                                ? title + " \u2014 Kukumber" : "Kukumber"));
            }
        });
    }

    // \u2500\u2500 UI construction \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    private void buildUI(Component browserComponent) {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cefClient.dispose();
                cefApp.dispose();
                dispose();
                System.exit(0);
            }
        });

        // Navigation controls
        JButton backBtn   = new JButton("\u25c4");
        JButton fwdBtn    = new JButton("\u25ba");
        JButton reloadBtn = new JButton("\u27f3");
        urlBar = new JTextField(HOME_URL);
        JButton goBtn = new JButton("Go");

        autoSaveToggle    = styledToggle("AutoSave OFF");
        offlineModeToggle = styledToggle("Offline OFF");
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        // Button actions
        backBtn.addActionListener(e -> cefBrowser.goBack());
        fwdBtn.addActionListener(e -> cefBrowser.goForward());
        reloadBtn.addActionListener(e -> {
            if (offlineModeEnabled) {
                loadFromCache(cefBrowser.getURL());
            } else {
                cefBrowser.reload();
            }
        });
        goBtn.addActionListener(e -> navigate(urlBar.getText()));
        urlBar.addActionListener(e -> navigate(urlBar.getText()));

        autoSaveToggle.addActionListener(e -> {
            autoSaveEnabled = autoSaveToggle.isSelected();
            autoSaveToggle.setText(autoSaveEnabled ? "AutoSave ON" : "AutoSave OFF");
        });
        offlineModeToggle.addActionListener(e -> {
            offlineModeEnabled = offlineModeToggle.isSelected();
            offlineModeToggle.setText(offlineModeEnabled ? "Offline ON" : "Offline OFF");
        });

        // Layout: nav bar
        JPanel leftNav = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        leftNav.setOpaque(false);
        leftNav.add(backBtn);
        leftNav.add(fwdBtn);
        leftNav.add(reloadBtn);

        JPanel navBar = new JPanel(new BorderLayout(4, 0));
        navBar.setBackground(new Color(240, 240, 240));
        navBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        navBar.add(leftNav, BorderLayout.WEST);
        navBar.add(urlBar, BorderLayout.CENTER);
        navBar.add(goBtn, BorderLayout.EAST);

        // Layout: feature bar (toggles + status)
        JPanel featureBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        featureBar.setBackground(new Color(240, 240, 240));
        featureBar.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        featureBar.add(autoSaveToggle);
        featureBar.add(offlineModeToggle);
        featureBar.add(statusLabel);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(new Color(240, 240, 240));
        topPanel.add(navBar, BorderLayout.NORTH);
        topPanel.add(featureBar, BorderLayout.SOUTH);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(topPanel, BorderLayout.NORTH);
        getContentPane().add(browserComponent, BorderLayout.CENTER);

        setSize(1280, 800);
        setLocationRelativeTo(null);
    }

    // \u2500\u2500 Navigation helpers \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    private void navigate(String input) {
        if (input == null || input.isBlank()) return;
        String url = input.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("file://")) {
            url = (url.contains(".") && !url.contains(" "))
                    ? "https://" + url
                    : "https://www.google.com/search?q=" + url.replace(" ", "+");
        }
        if (offlineModeEnabled) {
            if (cache.contains(url)) {
                loadFromCache(url);
            } else {
                showOfflinePlaceholder(url);
            }
        } else {
            cefBrowser.loadURL(url);
        }
    }

    /**
     * Loads cached page HTML via a {@code data:} URI so that no network
     * request is made.  The {@code loadingFromCache} flag prevents
     * {@link #onLoadEnd} from treating the data-URI load as a new page to save.
     */
    private void loadFromCache(String url) {
        String content = cache.load(url);
        if (content == null) return;
        loadingFromCache = true; // cleared in onLoadEnd after the data-URI load completes
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        cefBrowser.loadURL("data:text/html;charset=utf-8;base64," + b64);
        if (statusLabel != null) statusLabel.setText("Cached: " + shortUrl(url));
    }

    private void showOfflinePlaceholder(String url) {
        if (statusLabel != null) statusLabel.setText("Offline \u2013 not cached");
        String html =
                "<!DOCTYPE html><html><head><title>Offline</title>" +
                "<style>body{font-family:sans-serif;max-width:600px;margin:80px auto;" +
                "text-align:center;}h1{color:#e55;}</style></head><body>" +
                "<h1>Offline Mode</h1>" +
                "<p>The page <strong>" + escapeHtml(url) + "</strong> is not in the cache.</p>" +
                "<p>Disable <em>Offline Mode</em> to load it from the network, " +
                "then enable <em>AutoSave</em> so future visits are cached.</p>" +
                "</body></html>";
        String b64 = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        cefBrowser.loadURL("data:text/html;charset=utf-8;base64," + b64);
    }

    // \u2500\u2500 Static utilities \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    private static JToggleButton styledToggle(String text) {
        JToggleButton tb = new JToggleButton(text);
        tb.setPreferredSize(new Dimension(130, 28));
        return tb;
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        return url.length() > 60 ? url.substring(0, 57) + "\u2026" : url;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // \u2500\u2500 Entry point \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                LOG.warning("Could not set system look and feel: " + e.getMessage());
            }
            try {
                OfflineWebBrowser browser = new OfflineWebBrowser();
                browser.setVisible(true);
            } catch (Exception e) {
                LOG.severe("Failed to start browser: " + e.getMessage());
                JOptionPane.showMessageDialog(null,
                        "Failed to initialise JCEF browser.\n" + e.getMessage(),
                        "Startup Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
