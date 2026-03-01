package com.ayush4441.kukumber;

import javafx.scene.web.WebEngine;

import java.util.Set;

/**
 * Ad-blocker that works in two steps once a page has loaded:
 * <ol>
 *   <li>Injects CSS that hides elements known to carry advertisements.</li>
 *   <li>Executes JavaScript that removes ad-related {@code <iframe>},
 *       {@code <script>} and {@code <img>} elements whose {@code src}
 *       matches a blocked domain, and watches for new ones via
 *       {@code MutationObserver}.</li>
 * </ol>
 *
 * <p>The {@link #shouldBlock(String)} helper can be used by callers who want
 * to decide at the navigation level whether a URL belongs to an ad network.</p>
 */
public class AdBlocker {

    /** Domains whose resources are considered advertising/tracking. */
    private static final Set<String> BLOCKED_DOMAINS = Set.of(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adnxs.com",
            "amazon-adsystem.com",
            "scorecardresearch.com",
            "quantserve.com",
            "outbrain.com",
            "taboola.com",
            "advertising.com",
            "adroll.com",
            "criteo.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "2mdn.net",
            "moatads.com",
            "adsafeprotected.com",
            "ads.yahoo.com",
            "yimg.com"
    );

    /**
     * Returns {@code true} when {@code url} belongs to a known ad/tracking
     * domain.
     */
    public boolean shouldBlock(String url) {
        if (url == null || url.isEmpty()) return false;
        for (String domain : BLOCKED_DOMAINS) {
            if (url.contains(domain)) return true;
        }
        return false;
    }

    /**
     * Inject CSS and JavaScript ad-blocking rules into the currently loaded
     * page.  Safe to call from any thread – the script is executed on the
     * JavaFX Application Thread via the engine's normal call path.
     */
    public void injectBlocking(WebEngine engine) {
        try {
            // 1. CSS – hide common ad containers by id/class/attribute
            String css = buildBlockingCss();
            engine.executeScript(
                    "var __adcss = document.createElement('style');" +
                    "__adcss.type = 'text/css';" +
                    "__adcss.textContent = " + jsString(css) + ";" +
                    "document.head && document.head.appendChild(__adcss);"
            );

            // 2. JS – remove ad iframes/scripts with blocked src attributes
            engine.executeScript(buildBlockingScript());
        } catch (Exception e) {
            // Page may not have a <head> yet or JS execution is unavailable –
            // silently skip rather than crashing the browser.
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String buildBlockingCss() {
        return
            "[id*='ad'],[id*='ads'],[id*='advert'],[id*='advertisement']," +
            "[class*='ad-'],[class*='-ad'],[class*='ads-'],[class*='advert']," +
            "[class*='banner-ad'],[class*='ad_banner'],[class*='google-ad']," +
            "[class*='sponsored'],[class*='sponsor-']," +
            "ins.adsbygoogle,.adsbygoogle," +
            "iframe[src*='doubleclick'],iframe[src*='googlesyndication']," +
            "iframe[src*='adnxs'],iframe[src*='advertising']," +
            "iframe[src*='criteo'],iframe[src*='taboola'],iframe[src*='outbrain']," +
            "[data-ad],[data-ads],[data-adunit],[data-ad-unit]," +
            ".advertisement,.ad-container,.ad-wrapper,.ad-slot," +
            ".sidebar-ad,.banner-ad,.popup-ad,.sticky-ad," +
            "#ad-banner,#ad-container,#advertisement,#ads,#ad-slot" +
            "{ display:none !important; visibility:hidden !important; }";
    }

    private static String buildBlockingScript() {
        // Build the blocked-domains array literal for inline JS
        StringBuilder domains = new StringBuilder("[");
        boolean first = true;
        for (String d : BLOCKED_DOMAINS) {
            if (!first) domains.append(',');
            domains.append('\'').append(d).append('\'');
            first = false;
        }
        domains.append(']');

        return
            "(function(){\n" +
            "  var blocked=" + domains + ";\n" +
            "  function srcBlocked(src){\n" +
            "    if(!src) return false;\n" +
            "    for(var i=0;i<blocked.length;i++){\n" +
            "      if(src.indexOf(blocked[i])!==-1) return true;\n" +
            "    }\n" +
            "    return false;\n" +
            "  }\n" +
            "  function removeAds(){\n" +
            "    var sel='iframe,script,img,div[data-ad],ins.adsbygoogle';\n" +
            "    document.querySelectorAll(sel).forEach(function(el){\n" +
            "      var s=el.src||el.getAttribute('src')||'';\n" +
            "      if(srcBlocked(s)) el.parentNode&&el.parentNode.removeChild(el);\n" +
            "    });\n" +
            "  }\n" +
            "  removeAds();\n" +
            "  if(typeof MutationObserver!=='undefined'){\n" +
            "    new MutationObserver(removeAds)\n" +
            "      .observe(document.documentElement,{childList:true,subtree:true});\n" +
            "  }\n" +
            "})();";
    }

    /** Wraps {@code value} in single quotes, escaping backslashes, quotes, and control characters. */
    private static String jsString(String value) {
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "'";
    }
}
