package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A story from the home screen's News card. The story's address comes straight from Google (GoogleNews), not through
 * its forwarding page; the story's page loads hidden with only what has text in it (no pictures, fonts, styles, video,
 * ads or trackers), its cookie banner is answered "reject" (or "only necessary"), and its text is laid out for the TV
 * in columns that turn like pages (assets/reader). A page that doesn't look like an article shows as it is. Nothing of
 * it is kept: no cache, no history, no site storage. Back always goes home.
 */
public final class NewsActivity extends Activity {
    static final String EXTRA_URL = "url";
    /** How often the hidden page is read, and when it is shown as it is instead. */
    private static final long POLL = 400, GIVE_UP = 10_000;

    private static final Pattern NOT_TEXT = Pattern.compile(
            ".*\\.(jpe?g|png|gif|webp|avif|svg|ico|bmp|woff2?|ttf|otf|eot|css|mp4|webm|m3u8|mpd|mp3|m4a|ts)(\\?.*)?$");
    /** Ads, trackers and widgets: never needed for a story's text, and they are most of a news page's loading. */
    private static final String[] BLOCKED = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.", "imasdk.googleapis.com",
        "amazon-adsystem.com", "taboola.com", "outbrain.com", "criteo.", "facebook.net", "facebook.com/tr",
        "scorecardresearch.com", "chartbeat.", "hotjar.", "moatads.com", "adnxs.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "casalemedia.com", "quantserve.com", "quantcount.com", "newrelic.com",
        "nr-data.net", "izooto.com", "onesignal.com", "clarity.ms", "bat.bing.com", "clmbtech.com",
        "colombiaonline.com", "vdo.ai", "jwplayer.com", "jwpcdn.com", "brightcove.", "youtube.com/embed",
        "twitter.com/widgets", "platform.twitter.com", "instagram.com/embed", "sharethis.com", "addthis.com",
        "disqus.com", "cdn.pushengage.com", "sentry.io", "segment.io", "mixpanel.com", "branch.io",
        "permutive.", "lotame.com", "bluekai.com", "demdex.net", "omtrdc.net", "krxd.net", "teads.tv",
        "sharethrough.com", "media.net", "yieldmo.com", "smartadserver.com", "adsrvr.org", "3lift.com"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView loading;
    private String cookies, extract, template;
    private volatile boolean reader;
    private boolean shown, polling, destroyed;
    private String article;   // the story's address
    private String lastRead = "";
    private int reads;   // readings in a row that found a story
    private long t0;   // when the story was asked for (for the log: how long it took)

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        t0 = SystemClock.elapsedRealtime();
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || !url.startsWith("https://")) {
            finish();
            return;
        }
        try {
            cookies = asset("reader/cookies.js");
            extract = asset("reader/extract.js");
            template = asset("reader/reader.html");
        } catch (IOException e) {
            AppLog.w("news: " + e.getMessage());
        }

        loading = new TextView(this);
        loading.setText("Loading the story…");
        loading.setTextColor(0xFFB0B0B8);
        loading.setTextSize(22);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(0xFF121214);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web);
        root.addView(loading);
        setContentView(root);
        setUp(web);

        // The real address: known already (the card asked while the headline was chosen), or asked now, in parallel.
        String known = GoogleNews.known(url);
        if (known != null) {
            open(known);
        } else if (GoogleNews.isGoogle(url)) {
            new Thread(() -> {
                String story;
                try {
                    story = GoogleNews.resolve(url);
                } catch (Exception e) {
                    AppLog.w("news: address via Google's page (" + e.getMessage() + ")");
                    story = url;   // the old way: news.google.com's page forwards to it
                }
                String to = story;
                main.post(() -> { if (!destroyed) open(to); });
            }, "news-address").start();
        } else {
            open(url);
        }
        main.postDelayed(this::giveUp, GIVE_UP);
    }

    private void setUp(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);    // many sites write their stories with scripts
        s.setDomStorageEnabled(true);    // some break without it; it's wiped when the story closes
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);      // a page shown as it is: fitted to the TV's width
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);   // no ad and tracking cookies
        w.setBackgroundColor(0xFF121214);
        w.setVisibility(View.INVISIBLE);
        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return blocked(request) ? empty() : null;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (reader || !isStory(url)) return;
                article = url;
                lastRead = "";
                reads = 0;
                if (!polling) {
                    polling = true;
                    main.postDelayed(NewsActivity.this::poll, POLL);
                }
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                if (reader) show();   // the text is drawn: the story's picture can come in after
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (reader) show();
            }
        });
    }

    private void open(String url) {
        web.loadUrl(url);
    }

    /**
     * While the hidden page reads: what's only for the eye (pictures, fonts, styles, video) and ads and trackers are
     * answered empty. The reader page, after, loads its one picture.
     */
    private boolean blocked(WebResourceRequest r) {
        if (reader || r.isForMainFrame()) return false;
        String url = r.getUrl().toString().toLowerCase(Locale.ROOT);
        for (String host : BLOCKED) if (url.contains(host)) return true;
        if (NOT_TEXT.matcher(r.getUrl().getPath() == null ? "" : r.getUrl().getPath().toLowerCase(Locale.ROOT)).matches()) return true;
        Map<String, String> h = r.getRequestHeaders();
        String accept = h == null ? null : h.get("Accept");
        return accept != null && (accept.startsWith("image/") || accept.startsWith("text/css") || accept.startsWith("video/")
                || accept.startsWith("audio/") || accept.startsWith("font/"));
    }

    private static WebResourceResponse empty() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(204, "No Content");
        return r;
    }

    private static boolean isStory(String url) {
        String host = url == null ? null : Uri.parse(url).getHost();
        return host != null && url.startsWith("https://") && !host.equals("news.google.com") && !host.endsWith("consent.google.com");
    }

    /**
     * Every POLL ms: answers the cookie banner and reads the story. Taken once the page is parsed and its text read the
     * same twice in a row (or the page has finished): a page that writes its story with a script is read whole.
     */
    private void poll() {
        if (reader || shown || destroyed || cookies == null) {
            polling = false;
            return;
        }
        web.evaluateJavascript(cookies, null);
        web.evaluateJavascript("document.readyState", state -> web.evaluateJavascript(extract, value -> {
            if (reader || shown || destroyed) return;
            boolean some = value != null && !value.equals("null") && !value.equals("\"\"");
            boolean complete = "\"complete\"".equals(state), parsed = complete || "\"interactive\"".equals(state);
            reads = some ? reads + 1 : 0;
            // A page whose text keeps changing (a live ticker, "updated 5 s ago") is taken at the third reading.
            if (some && parsed && (value.equals(lastRead) || complete || reads >= 3)) {
                String html = build(value);
                if (html != null) {
                    reader = true;
                    polling = false;
                    web.stopLoading();
                    web.loadDataWithBaseURL(article, html, "text/html", "utf-8", null);
                    return;
                }
            }
            lastRead = some ? value : "";
            main.postDelayed(this::poll, POLL);
        }));
    }

    /** Not an article (or nothing found in time): the page as it is. */
    private void giveUp() {
        if (reader || shown || destroyed) return;
        if (cookies != null) web.evaluateJavascript(cookies, null);
        show();
    }

    /** The reader page from extract.js's answer (a JSON string inside a JS string), or null. */
    private String build(String value) {
        try {
            JSONObject o = new JSONObject(new JSONArray("[" + value + "]").getString(0));
            StringBuilder b = new StringBuilder();
            b.append("<div class=\"site\">").append(TextUtils.htmlEncode(o.optString("site"))).append("</div>");
            b.append("<h1>").append(TextUtils.htmlEncode(o.optString("title"))).append("</h1>");
            String image = o.optString("image");
            if (image.startsWith("https://")) b.append("<img src=\"").append(TextUtils.htmlEncode(image)).append("\" alt=\"\">");
            JSONArray blocks = o.getJSONArray("blocks");
            for (int i = 0; i < blocks.length(); i++) {
                JSONArray block = blocks.getJSONArray(i);
                String tag = block.getString(0);
                if (!tag.equals("h2") && !tag.equals("h3") && !tag.equals("blockquote")) tag = "p";
                b.append('<').append(tag).append('>').append(TextUtils.htmlEncode(block.getString(1))).append("</").append(tag).append('>');
            }
            return template.replace("{{content}}", b.toString());
        } catch (Exception e) {
            AppLog.w("news reader: " + e.getMessage());
            return null;
        }
    }

    private void show() {
        if (shown) return;
        shown = true;
        main.removeCallbacksAndMessages(null);
        AppLog.w("news: " + (reader ? "story" : "page as it is") + " shown in " + (SystemClock.elapsedRealtime() - t0) + " ms"
                + (article == null ? "" : " (" + Uri.parse(article).getHost() + ")"));
        web.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
        web.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (reader && shown) {
            int code = e.getKeyCode();
            boolean next = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN;
            boolean prev = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_PAGE_UP;
            if (next || prev) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) web.evaluateJavascript(next ? "frm.next()" : "frm.prev()", null);
                return true;
            }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private String asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            // Nothing of the story stays on the TV.
            web.stopLoading();
            web.clearCache(true);
            web.clearHistory();
            web.clearFormData();
            WebStorage.getInstance().deleteAllData();
            web.destroy();
        }
        super.onDestroy();
    }
}
