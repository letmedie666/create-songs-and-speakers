package net.songsandspeakers;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns common "share" links into direct file links and recognizes links that can never work. */
public final class LinkFixer {
    private static final Pattern DRIVE_FILE = Pattern.compile("https?://drive\\.google\\.com/file/d/([A-Za-z0-9_-]+).*");
    private static final Pattern DRIVE_OPEN = Pattern.compile("https?://drive\\.google\\.com/(?:open|uc)\\?(?:.*&)?id=([A-Za-z0-9_-]+).*");
    private static final Pattern GITHUB_BLOB = Pattern.compile("https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)");

    private LinkFixer() {}

    public static String fix(String url) {
        String u = url == null ? "" : url.trim();
        Matcher m = DRIVE_FILE.matcher(u);
        if (m.matches()) return "https://drive.google.com/uc?export=download&id=" + m.group(1);
        m = DRIVE_OPEN.matcher(u);
        if (m.matches()) return "https://drive.google.com/uc?export=download&id=" + m.group(1);
        m = GITHUB_BLOB.matcher(u);
        if (m.matches()) return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
        String host = host(u);
        if (host.endsWith("dropbox.com")) {
            if (u.contains("dl=0")) return u.replace("dl=0", "dl=1");
            if (!u.contains("dl=1") && !u.contains("raw=1")) return u + (u.contains("?") ? "&" : "?") + "dl=1";
        }
        return u;
    }

    public static boolean isYoutube(String url) {
        String h = host(url);
        return h.endsWith("youtube.com") || h.endsWith("youtu.be");
    }

    public static boolean isHttp(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    static String host(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int s = u.indexOf("://");
        if (s < 0) return "";
        u = u.substring(s + 3);
        int e = u.length();
        for (char c : new char[]{'/', '?', '#', ':'}) {
            int i = u.indexOf(c);
            if (i >= 0 && i < e) e = i;
        }
        return u.substring(0, e);
    }
}
