package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A news.google.com/rss/articles/… link's real story address, asked of Google the way its own forwarding page does
 * (two requests, ~1 s), without loading that page in a WebView (3 to 4 s on a TV). Answers are kept only in memory, a
 * few at a time: the addresses, never the news.
 */
final class GoogleNews {
    private GoogleNews() {}

    private static final Pattern ID = Pattern.compile("^https://news\\.google\\.com/(?:rss/)?articles/([^?/#]+)");
    private static final Pattern SIGNATURE = Pattern.compile("data-n-a-sg=\"([^\"]+)\"");
    private static final Pattern TIMESTAMP = Pattern.compile("data-n-a-ts=\"([0-9]+)\"");
    private static final int KEEP = 8;
    private static final Map<String, String> RESOLVED = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > KEEP; }
    };
    private static final ExecutorService AHEAD = Executors.newSingleThreadExecutor();

    static boolean isGoogle(String link) {
        return link != null && ID.matcher(link).find();
    }

    /** The address already found for `link`, or null. */
    static String known(String link) {
        synchronized (RESOLVED) {
            return RESOLVED.get(link);
        }
    }

    /** Finds the address in the background (the headline the remote rests on), so opening it skips this step. */
    static void ahead(String link) {
        if (!isGoogle(link) || known(link) != null) return;
        AHEAD.execute(() -> {
            try {
                resolve(link);
            } catch (Exception ignored) {
                // the story's page finds it again when it opens
            }
        });
    }

    /** The story's https address. Slow (network): never on the main thread. */
    static String resolve(String link) throws Exception {
        String known = known(link);
        if (known != null) return known;
        Matcher m = ID.matcher(link);
        if (!m.find()) throw new IOException("not a Google News link");
        String id = m.group(1);
        String page = request("https://news.google.com/rss/articles/" + id, null);
        Matcher sg = SIGNATURE.matcher(page), ts = TIMESTAMP.matcher(page);
        if (!sg.find() || !ts.find()) throw new IOException("no signature");
        String inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1],"
                + "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0]," + JSONObject.quote(id) + "," + ts.group(1) + ","
                + JSONObject.quote(sg.group(1)) + "]";
        String body = "f.req=" + URLEncoder.encode("[[[\"Fbv4je\"," + JSONObject.quote(inner) + ",null,\"generic\"]]]", "UTF-8");
        String answer = request("https://news.google.com/_/DotsSplashUi/data/batchexecute", body);
        // The answer: an anti-JSON prefix, then lines of JSON; ours is [["wrb.fr","Fbv4je","[\"garturlres\",\"<url>\",…]",…]].
        for (String line : answer.split("\n")) {
            if (!line.startsWith("[[\"wrb.fr\"")) continue;
            JSONArray reply = new JSONArray(line).getJSONArray(0);
            String url = new JSONArray(reply.getString(2)).getString(1);
            if (!url.startsWith("https://")) throw new IOException("not https");
            synchronized (RESOLVED) {
                RESOLVED.put(link, url);
            }
            return url;
        }
        throw new IOException("no address in the answer");
    }

    private static String request(String url, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        try {
            if (form != null) {
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
