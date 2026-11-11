package com.finsight.news.collect;

import java.net.URI;

/** Publisher image references only; this application never downloads or hosts the image. */
public final class NewsImageUrl {
    private NewsImageUrl() {}
    public static String sanitize(String value, String base) { return sanitize(value,base,null,null); }
    public static String sanitize(String value, String base, String width, String height) {
        if (tiny(width) || tiny(height)) return null;
        if (value == null || value.isBlank() || value.length() > 2000) return null;
        try {
            URI uri = URI.create(value.strip());
            if (!uri.isAbsolute()) uri = URI.create(base).resolve(uri);
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || host == null || uri.getUserInfo() != null
                    || host.matches("(?i)localhost|.*\\.localhost|127\\..*|0\\..*|10\\..*|192\\.168\\..*|169\\.254\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|\\[.*\\]")) return null;
            String path = java.util.Objects.toString(uri.getPath(), "");
            if (java.util.regex.Pattern.compile("(?i)(?:^|[/_.-])(?:logo|banner|advert(?:isement)?|ads?|pixel|tracker|spacer|1x1|no[-_]?image|default)(?:[/_.-]|$)").matcher(path).find()
                    || host.matches("(?i)(?:.*\\.)?(?:doubleclick\\.net|googlesyndication\\.com|googleadservices\\.com|adnxs\\.com)")
                    || host.matches("(?i)(?:ad|ads|adserver|adservice)\\..*")) return null;
            return uri.toString().length() <= 2000 ? uri.toString() : null;
        } catch (IllegalArgumentException | NullPointerException invalid) { return null; }
    }
    private static boolean tiny(String size) {
        if (size == null) return false;
        try { double n=Double.parseDouble(size);return n>=0 && n<=1; }
        catch (NumberFormatException ignored) { return false; }
    }
}
