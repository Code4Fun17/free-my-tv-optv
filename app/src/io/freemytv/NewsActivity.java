package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A story from the home screen's News card. The page loads hidden; its cookie banner is answered "reject" (or "only
 * necessary") and the story's text is laid out for the TV in two columns that turn like pages (assets/reader). A page
 * that doesn't look like an article shows as it is, fitted to the screen. Back always goes home: the story came through
 * a news.google.com link that would only forward to it again.
 */
public final class NewsActivity extends Activity {
    static final String EXTRA_URL = "url";
    /** When the hidden page is read: early ones catch a page whose ads never finish loading. */
    private static final long[] TRIES = {2500, 5000, 8000};
    private static final long GIVE_UP = 12_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView loading;
    private String cookies, extract, template;
    private boolean reader, shown;
    private String article;   // the story's address, once news.google.com has forwarded to it

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);   // news.google.com's links forward to the story with a script
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);      // a page shown as it is: fitted to the TV's width
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);   // no ad and tracking cookies
        web.setBackgroundColor(0xFF121214);
        web.setVisibility(View.INVISIBLE);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (reader || !isStory(url)) return;
                article = url;
                main.removeCallbacksAndMessages(null);
                for (long at : TRIES) main.postDelayed(() -> tryReader(false), at);
                main.postDelayed(() -> tryReader(true), GIVE_UP);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (reader) {
                    show();
                } else if (isStory(url)) {
                    main.postDelayed(() -> tryReader(false), 800);
                }
            }
        });

        loading = new TextView(this);
        loading.setText("Loading the story…");
        loading.setTextColor(0xFFB0B0B8);
        loading.setTextSize(22);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(0xFF121214);
        FrameLayout root = new FrameLayout(this);
        root.addView(web);
        root.addView(loading);
        setContentView(root);
        web.requestFocus();
        web.loadUrl(url);
    }

    private static boolean isStory(String url) {
        String host = url == null ? null : Uri.parse(url).getHost();
        return host != null && url.startsWith("https://") && !host.equals("news.google.com") && !host.endsWith("consent.google.com");
    }

    /** Answers the cookie banner, then reads the story; `last`: when it isn't an article, show the page as it is. */
    private void tryReader(boolean last) {
        if (reader || shown || web == null || cookies == null) return;
        web.evaluateJavascript(cookies, null);
        web.evaluateJavascript(extract, value -> {
            if (reader || shown) return;
            String html = value == null || value.equals("null") || value.equals("\"\"") ? null : build(value);
            if (html != null) {
                reader = true;
                main.removeCallbacksAndMessages(null);
                web.loadDataWithBaseURL(article, html, "text/html", "utf-8", null);
            } else if (last) {
                web.evaluateJavascript(cookies, null);
                show();
            }
        });
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
        main.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
