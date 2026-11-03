package com.finsight.news.collect;

import java.net.URI;

/** Publisher image references only; this application never downloads or hosts the image. */
public final class NewsImageUrl {
    private NewsImageUrl() {}
    public static String sanitize(String value, String base) {
        if (value == null || value.isBlank() || value.length() > 2000) return null;
        try {
            URI uri = URI.create(value.strip());
            if (!uri.isAbsolute()) uri = URI.create(base).resolve(uri);
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || host == null || uri.getUserInfo() != null
                    || host.matches("(?i)localhost|.*\\.localhost|127\\..*|0\\..*|10\\..*|192\\.168\\..*|169\\.254\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|\\[.*\\]")) return null;
            return uri.toString().length() <= 2000 ? uri.toString() : null;
        } catch (IllegalArgumentException | NullPointerException invalid) { return null; }
    }
}
