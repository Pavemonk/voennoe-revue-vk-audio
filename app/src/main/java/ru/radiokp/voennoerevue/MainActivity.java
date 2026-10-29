package ru.radiokp.voennoerevue;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements AudioPlaybackService.PlaybackStateListener {
    private static final String VK_LIVES_URL = "https://vkvideo.ru/@radiokp/lives";
    private TextView statusView;
    private Button playPauseButton;
    private WebView webView;
    private LinearLayout webViewContainer;
    private AudioPlaybackService playbackService;
    private boolean bound = false;
    private String currentPageUrl = VK_LIVES_URL;
    private boolean streamStarted = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((AudioPlaybackService.LocalBinder) service).getService();
            playbackService.setPlaybackStateListener(MainActivity.this);
            updateControls();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (playbackService != null) playbackService.clearPlaybackStateListener();
            playbackService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermissionIfNeeded();
        buildUi();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 61);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 20, 26));
        root.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("РАДИО КП");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Прямая трансляция · 16:00–17:00 МСК");
        subtitle.setTextColor(Color.rgb(174, 183, 197));
        subtitle.setTextSize(14);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(4);
        subtitleParams.bottomMargin = dp(14);
        root.addView(subtitle, subtitleParams);

        statusView = new TextView(this);
        statusView.setText("Нажмите «Найти эфир», чтобы подключиться к прямой трансляции VK. После подключения страница будет закрыта.");
        statusView.setTextColor(Color.rgb(220, 225, 233));
        statusView.setTextSize(14);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackgroundColor(Color.rgb(30, 35, 43));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(10), 0, dp(10));
        Button openButton = new Button(this);
        openButton.setText("НАЙТИ ЭФИР");
        openButton.setOnClickListener(v -> openVkLives());
        controls.addView(openButton, new LinearLayout.LayoutParams(0, dp(48), 1));

        playPauseButton = new Button(this);
        playPauseButton.setText("Пауза / Play");
        playPauseButton.setOnClickListener(v -> togglePlayback());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        playParams.leftMargin = dp(6);
        controls.addView(playPauseButton, playParams);

        Button stopButton = new Button(this);
        stopButton.setText("Стоп");
        stopButton.setOnClickListener(v -> stopPlayback());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 0.7f);
        stopParams.leftMargin = dp(6);
        controls.addView(stopButton, stopParams);
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        webViewContainer = new LinearLayout(this);
        webViewContainer.setOrientation(LinearLayout.VERTICAL);
        webViewContainer.setBackgroundColor(Color.rgb(17, 20, 26));
        webViewContainer.setVisibility(View.GONE);
        root.addView(webViewContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView footer = new TextView(this);
        footer.setText("VK Видео · только звук · страница закрывается после подключения");
        footer.setTextColor(Color.rgb(135, 145, 160));
        footer.setTextSize(12);
        footer.setPadding(0, dp(8), 0, 0);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void createWebViewIfNeeded() {
        if (webView != null) return;
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(17, 20, 26));
        webViewContainer.addView(webView, new LinearLayout.LayoutParams(-1, -1));
        configureWebView();
    }

    /** Fully dispose of the embedded browser once the audio URL has been resolved. */
    private void releaseWebView() {
        WebView old = webView;
        webView = null;
        if (old != null) {
            try { old.stopLoading(); } catch (Exception ignored) { }
            try { old.removeJavascriptInterface("AudioBridge"); } catch (Exception ignored) { }
            try { old.setWebViewClient(null); } catch (Exception ignored) { }
            try { old.setWebChromeClient(null); } catch (Exception ignored) { }
            try {
                if (old.getParent() instanceof ViewGroup) {
                    ((ViewGroup) old.getParent()).removeView(old);
                }
            } catch (Exception ignored) { }
            try { old.destroy(); } catch (Exception ignored) { }
        }
        if (webViewContainer != null) webViewContainer.setVisibility(View.GONE);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new VkAudioBridge(), "AudioBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host != null && isVkHost(host)) return false;
                return false;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentPageUrl = url;
                if (isVkPage(url)) {
                    statusView.setText("Открыта VK Видео. Нажмите на текущую прямую трансляцию.");
                } else if (url.contains("login.vk.ru") || url.contains("login.vk.com")) {
                    statusView.setText("VK просит войти. Выполните вход в открытой странице, затем вернитесь к эфирам.");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                currentPageUrl = url;
                if (isVkPage(url)) {
                    view.evaluateJavascript(INSTALL_HOOK_SCRIPT, null);
                    if (url.contains("/@radiokp/lives")) view.evaluateJavascript(AUTO_OPEN_LIVE_SCRIPT, null);
                }
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String method = request.getMethod();
                // The public VK player currently exposes a signed HLS URL in its video-discovery response.
                // Re-fetch GET discovery requests ourselves so the same JSON can be passed to WebView and inspected.
                if ("GET".equalsIgnoreCase(method) && url.contains("video.getVideoDiscover") && isVkHost(request.getUrl().getHost())) {
                    WebResourceResponse intercepted = fetchAndInspectVkDiscovery(url, request.getRequestHeaders());
                    if (intercepted != null) return intercepted;
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    private WebResourceResponse fetchAndInspectVkDiscovery(String url, Map<String, String> originalHeaders) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            if (originalHeaders != null) {
                for (String name : new String[]{"User-Agent", "Referer", "Origin", "Accept", "Accept-Language"}) {
                    String value = findHeaderIgnoreCase(originalHeaders, name);
                    if (value != null) connection.setRequestProperty(name, value);
                }
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie == null || cookie.isEmpty()) cookie = findHeaderIgnoreCase(originalHeaders, "Cookie");
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = stream == null ? new byte[0] : readAll(stream, 4 * 1024 * 1024);
            String body = new String(bytes, StandardCharsets.UTF_8);
            String resolved = extractHlsUrl(body);
            if (resolved != null) handleStreamResolved(resolved);
            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put("Access-Control-Allow-Origin", "*");
            responseHeaders.put("Cache-Control", "no-store");
            String mime = "application/json";
            return new WebResourceResponse(mime, "UTF-8", code > 0 ? code : 200,
                    code >= 400 ? "VK response" : "OK", responseHeaders, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String findHeaderIgnoreCase(Map<String, String> headers, String target) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(target)) return entry.getValue();
        }
        return null;
    }

    private static byte[] readAll(InputStream input, int maxBytes) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > maxBytes) throw new IllegalStateException("VK response too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static final String INSTALL_HOOK_SCRIPT = "(function(){try{if(window.__vrAudioHook)return;window.__vrAudioHook=true;" +
            "function hideVideo(){try{document.querySelectorAll('video').forEach(function(v){v.style.setProperty('position','fixed','important');v.style.setProperty('left','-5px','important');v.style.setProperty('top','-5px','important');v.style.setProperty('width','1px','important');v.style.setProperty('height','1px','important');v.style.setProperty('opacity','0.001','important');v.style.setProperty('pointer-events','none','important');v.controls=false;})}catch(e){}}" +
            "hideVideo();try{new MutationObserver(hideVideo).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}" +
            "function report(t){try{if(typeof t==='string'&&t.indexOf('hls_live')!==-1&&window.AudioBridge)window.AudioBridge.onPossibleStreamText(t)}catch(e){}}" +
            "var f=window.fetch;if(f)window.fetch=function(){return f.apply(this,arguments).then(function(r){try{r.clone().text().then(report)}catch(e){}return r})};" +
            "var op=XMLHttpRequest.prototype.open, se=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){this.__vrUrl=String(u||'');return op.apply(this,arguments)};" +
            "XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{report(this.responseText)}catch(e){}});return se.apply(this,arguments)};" +
            "}catch(e){}})();";

    private static final String AUTO_OPEN_LIVE_SCRIPT = "(function(){try{setTimeout(function(){" +
            "var els=[].slice.call(document.querySelectorAll('a[href]'));" +
            "for(var i=0;i<els.length;i++){var a=els[i];var parent=a.closest('article,[data-testid],[class*=VideoCard],[class*=video-card]')||a;" +
            "var t=((a.innerText||'')+' '+(parent.innerText||'')).toLowerCase();" +
            "if((/прям.{0,8}эфир|сейчас в эфире|live now|live broadcast|прямой эфир/.test(t))&&(/video-|live-/.test(a.href))){a.click();return;}}" +
            "},1200)}catch(e){}})();";

    public final class VkAudioBridge {
        @JavascriptInterface
        public void onPossibleStreamText(String text) {
            if (text == null || text.length() > 4 * 1024 * 1024 || !isVkPage(currentPageUrl)) return;
            String stream = extractHlsUrl(text);
            if (stream != null) handleStreamResolved(stream);
        }
    }

    private static String extractHlsUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            Object parsed = text.trim().startsWith("{") ? new JSONObject(text) :
                    (text.trim().startsWith("[") ? new JSONArray(text) : null);
            if (parsed != null) {
                String value = findKey(parsed, "hls_live");
                if (!isLikelyHlsUrl(value)) value = findKey(parsed, "hls_live_playback");
                if (isLikelyHlsUrl(value)) return value.replace("\\u0026", "&").replace("\\/", "/");
            }
        } catch (Exception ignored) { }
        // Some browser responses are wrapped or escaped; accept only the known live-HLS field names.
        Pattern p = Pattern.compile("[\\\"']hls_live(?:_playback)?[\\\"']\\s*:\\s*[\\\"'](https?:[^\\\"']+?\\.m3u8[^\\\"']*)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            String value = m.group(1).replace("\\u0026", "&").replace("\\/", "/");
            if (isLikelyHlsUrl(value)) return value;
        }
        return null;
    }

    private static String findKey(Object node, String sought) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(sought)) {
                String candidate = object.optString(sought, null);
                if (isLikelyHlsUrl(candidate)) return candidate;
            }
            // Arrays/objects are traversed recursively, but avoid scanning arbitrary URL properties first.
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i);
                    if (name.equals(sought)) continue;
                    String found = findKey(object.opt(name), sought);
                    if (found != null) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findKey(array.opt(i), sought);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isLikelyHlsUrl(String url) {
        if (url == null || !url.startsWith("https://") || !url.toLowerCase(Locale.ROOT).contains(".m3u8")) return false;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.endsWith(".okcdn.ru") || host.endsWith(".vkuservideo.net")
                    || host.endsWith(".userapi.com") || host.endsWith(".vkvideo.ru")
                    || host.endsWith(".vk.com");
        } catch (Exception e) { return false; }
    }

    private void handleStreamResolved(String url) {
        if (url == null || streamStarted) return;
        streamStarted = true;
        runOnUiThread(() -> {
            statusView.setText("Поток VK найден. Запускаю аудио…");
            Runnable startNativePlayback = () -> {
                // Destroy the whole WebView, not merely hide it: VK's page and JavaScript can use substantial RAM.
                releaseWebView();
                Intent intent = new Intent(this, AudioPlaybackService.class)
                        .setAction(AudioPlaybackService.ACTION_PLAY)
                        .putExtra(AudioPlaybackService.EXTRA_URL, url)
                        .putExtra(AudioPlaybackService.EXTRA_REFERER, "https://vkvideo.ru/");
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
                updateControls();
            };
            if (webView != null) {
                // Stop the VK player first so it cannot continue audio/video alongside Media3.
                webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){try{v.muted=true;v.pause()}catch(e){}})",
                        value -> runOnUiThread(startNativePlayback));
            } else {
                startNativePlayback.run();
            }
        });
    }

    private void openVkLives() {
        streamStarted = false;
        statusView.setText("Открываю список прямых эфиров VK. Выберите текущий эфир, если он не запустится автоматически.");
        createWebViewIfNeeded();
        webViewContainer.setVisibility(View.VISIBLE);
        webView.loadUrl(VK_LIVES_URL);
    }

    private void togglePlayback() {
        if (playbackService == null || !playbackService.hasStream()) {
            Toast.makeText(this, "Сначала откройте текущий эфир VK", Toast.LENGTH_SHORT).show();
            openVkLives();
            return;
        }
        playbackService.togglePlayback();
        updateControls();
    }

    private void stopPlayback() {
        if (playbackService != null) playbackService.stopPlayback();
        streamStarted = false;
        releaseWebView();
        statusView.setText("Остановлено. Для следующего эфира нажмите «Найти эфир».");
        updateControls();
    }

    private void updateControls() {
        if (playPauseButton == null) return;
        boolean playing = playbackService != null && playbackService.isPlaying();
        playPauseButton.setText(playing ? "Пауза" : "Play");
    }

    @Override
    public void onPlaybackStateChanged(boolean playing, String message) {
        runOnUiThread(() -> {
            statusView.setText(message);
            updateControls();
        });
    }

    private boolean isVkPage(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host != null && (host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru")
                    || host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru") || host.endsWith(".vk.ru"));
        } catch (Exception e) { return false; }
    }

    private static boolean isVkHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com")
                || host.endsWith(".vk.com") || host.equals("vk.ru") || host.endsWith(".vk.ru")
                || host.equals("api.vkvideo.ru") || host.endsWith(".api.vkvideo.ru");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onStart() {
        super.onStart();
        Intent service = new Intent(this, AudioPlaybackService.class);
        bound = bindService(service, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        // If the user leaves while still browsing VK and no stream has been found,
        // release the page to avoid leaving a heavy WebView running in the background.
        if (webView != null && !streamStarted && (playbackService == null || !playbackService.hasStream())) {
            releaseWebView();
        }
        if (bound) {
            if (playbackService != null) playbackService.clearPlaybackStateListener();
            unbindService(serviceConnection);
            bound = false;
            playbackService = null;
        }
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (webView != null && webViewContainer.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (webView != null) {
            releaseWebView();
            statusView.setText("Страница VK закрыта. Аудио продолжит играть, если эфир уже запущен.");
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        releaseWebView();
        super.onDestroy();
    }
}
