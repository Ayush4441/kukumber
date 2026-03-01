package com.ayush4441.kukumber;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Disk-based page cache. Each page is stored as an HTML file named after the
 * MD5 hash of its URL inside {@code ~/.kukumber/cache/}.
 */
public class BrowserCache {

    private static final Logger LOG = Logger.getLogger(BrowserCache.class.getName());
    private static final String CACHE_DIR =
            System.getProperty("user.home") + File.separator + ".kukumber" + File.separator + "cache";

    public BrowserCache() {
        new File(CACHE_DIR).mkdirs();
    }

    /** Persist {@code content} for {@code url}. */
    public void save(String url, String content) {
        if (url == null || content == null) return;
        try {
            Files.writeString(cachePath(url), content, StandardCharsets.UTF_8);
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.log(Level.WARNING, "Failed to cache page: " + url, e);
        }
    }

    /** Return the cached HTML for {@code url}, or {@code null} if not present. */
    public String load(String url) {
        if (url == null) return null;
        try {
            Path p = cachePath(url);
            if (Files.exists(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.log(Level.WARNING, "Failed to read cached page: " + url, e);
        }
        return null;
    }

    /** Returns {@code true} when the URL has a cached copy on disk. */
    public boolean contains(String url) {
        if (url == null) return false;
        try {
            return Files.exists(cachePath(url));
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path cachePath(String url) throws NoSuchAlgorithmException {
        return Path.of(CACHE_DIR, md5(url) + ".html");
    }

    private static String md5(String text) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(32);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
