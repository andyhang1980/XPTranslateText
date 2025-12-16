package tianci.dev.xptranslatetext.translate;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.HostnameVerifier;

import java.net.URL;
import java.net.URLEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import tianci.dev.xptranslatetext.HookMain;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.SSLContext;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import tianci.dev.xptranslatetext.util.LocalCerts;

/**
 * Translate multiple segments with an in-memory cache backed by the local translation server.
 */
public class MultiSegmentTranslateTask {
    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newCachedThreadPool();
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();

    private static final int LOCAL_SERVER_PORT = 18181;
    private static final long CONFIG_TTL_MS = 1000L;
    private static final int CONFIG_CONNECT_TIMEOUT_MS = 150;
    private static final int CONFIG_READ_TIMEOUT_MS = 250;

    private static final Object CONFIG_LOCK = new Object();
    private static final AtomicBoolean CONFIG_REFRESH_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile ServerConfig CACHED_CONFIG;

    // Cached SSL objects for local HTTPS pinning
    private static volatile SSLSocketFactory LOCAL_PINNED_SSL_FACTORY;
    private static final HostnameVerifier LOCAL_HOSTNAME_VERIFIER = (hostname, session) ->
            "127.0.0.1".equals(hostname) || "localhost".equals(hostname) || "::1".equals(hostname);

    private static final int QUICK_LOCAL_CONNECT_TIMEOUT_MS = 150; // keep short to avoid UI jank
    private static final int QUICK_LOCAL_READ_TIMEOUT_MS = 250;    // keep short to avoid UI jank

    private static void log(String msg) {
        XposedBridge.log(msg);
    }

    private static final class ServerConfig {
        final String sourceLang;
        final String targetLang;
        final boolean forceWaitLocal;
        final long fetchedAtMs;

        private ServerConfig(String sourceLang, String targetLang, boolean forceWaitLocal, long fetchedAtMs) {
            this.sourceLang = sourceLang == null || sourceLang.isEmpty() ? "auto" : sourceLang;
            this.targetLang = targetLang == null || targetLang.isEmpty() ? "zh-TW" : targetLang;
            this.forceWaitLocal = forceWaitLocal;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    public static void prefetchServerConfigAsync() {
        refreshServerConfigAsync(false);
    }

    public static boolean getForceWaitLocalSnapshot() {
        ServerConfig config = getCachedServerConfigAndTriggerRefreshIfStale();
        return config != null && config.forceWaitLocal;
    }

    private static boolean isConfigFresh(ServerConfig config, long nowMs) {
        return config != null && nowMs - config.fetchedAtMs <= CONFIG_TTL_MS;
    }

    private static boolean equalsNullable(String a, String b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static void updateCachedConfig(ServerConfig newConfig) {
        ServerConfig old = CACHED_CONFIG;
        CACHED_CONFIG = newConfig;

        boolean languageChanged = old == null
                || !equalsNullable(old.sourceLang, newConfig.sourceLang)
                || !equalsNullable(old.targetLang, newConfig.targetLang);
        if (languageChanged) {
            translationCache.clear();
        }
    }

    private static ServerConfig getCachedServerConfigAndTriggerRefreshIfStale() {
        long now = System.currentTimeMillis();
        ServerConfig config = CACHED_CONFIG;
        if (isConfigFresh(config, now)) return config;

        refreshServerConfigAsync(false);
        return config;
    }

    private static ServerConfig getFreshServerConfigOrTriggerRefresh() {
        long now = System.currentTimeMillis();
        ServerConfig config = CACHED_CONFIG;
        if (isConfigFresh(config, now)) return config;

        refreshServerConfigAsync(false);
        return null;
    }

    private static ServerConfig getFreshServerConfigBlocking() {
        long now = System.currentTimeMillis();
        ServerConfig config = CACHED_CONFIG;
        if (isConfigFresh(config, now)) return config;

        synchronized (CONFIG_LOCK) {
            now = System.currentTimeMillis();
            config = CACHED_CONFIG;
            if (isConfigFresh(config, now)) return config;

            ServerConfig fetched = fetchServerConfig();
            if (fetched == null) return null;
            updateCachedConfig(fetched);
            return fetched;
        }
    }

    private static void refreshServerConfigAsync(boolean force) {
        long now = System.currentTimeMillis();
        ServerConfig config = CACHED_CONFIG;
        if (!force && isConfigFresh(config, now)) return;

        if (!CONFIG_REFRESH_IN_FLIGHT.compareAndSet(false, true)) return;
        TRANSLATION_EXECUTOR.submit(() -> {
            try {
                ServerConfig fetched = fetchServerConfig();
                if (fetched != null) {
                    updateCachedConfig(fetched);
                }
            } finally {
                CONFIG_REFRESH_IN_FLIGHT.set(false);
            }
        });
    }

    private static ServerConfig fetchServerConfig() {
        try {
            String urlStr = String.format("https://127.0.0.1:%d/config", LOCAL_SERVER_PORT);
            URL url = new URL(urlStr);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(getOrCreateLocalPinnedFactory());
            conn.setHostnameVerifier(LOCAL_HOSTNAME_VERIFIER);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONFIG_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONFIG_READ_TIMEOUT_MS);

            int status = conn.getResponseCode();
            if (status != 200) return null;

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }

                JSONObject obj = new JSONObject(sb.toString());
                if (obj.optInt("code", -1) != 0) return null;

                String src = obj.optString("source_lang", "auto");
                String dst = obj.optString("target_lang", "zh-TW");
                boolean forceWaitLocal = obj.optBoolean("force_wait_local", false);
                return new ServerConfig(src, dst, forceWaitLocal, System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildCacheKey(ServerConfig config, String text) {
        if (config == null) return null;
        return config.sourceLang + ":" + config.targetLang + ":" + (text == null ? "" : text);
    }

    public static void translateSegmentsAsync(
            final XC_MethodHook.MethodHookParam param,
            final int translationId,
            final List<Segment> segments
    ) {
        TRANSLATION_EXECUTOR.submit(() -> {
            doTranslateSegments(segments);

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    // Prefer AdditionalInstanceField to verify the same target
                    try {
                        Object storedId = XposedHelpers.getAdditionalInstanceField(param.thisObject, HookMain.TRANSLATION_ID_KEY);
                        if (storedId instanceof Integer) {
                            int currentId = (Integer) storedId;
                            if (currentId == translationId) {
                                HookMain.applyTranslatedSegments(param, segments);
                            } else {
                                log("MultiSegmentTranslateTask => expired by additional field. currentId=" + currentId + ", myId=" + translationId);
                            }
                            return; // handled verification path
                        }
                    } catch (Throwable ignored) {
                    }

                    // fallback to getTag() (if View)
                    try {
                        Method getTag = XposedHelpers.findMethodExactIfExists(param.thisObject.getClass(), "getTag");
                        if (getTag != null) {
                            Object tagObj = XposedHelpers.callMethod(param.thisObject, "getTag");
                            if (tagObj instanceof Integer && ((Integer) tagObj) == translationId) {
                                HookMain.applyTranslatedSegments(param, segments);
                            } else {
                                log("Tag mismatch => skip. tag=" + tagObj + ", myId=" + translationId);
                            }
                            return; // handled tag path
                        }
                    } catch (Throwable ignored) {
                    }

                    // If we cannot verify (non-View), conservatively apply
                    HookMain.applyTranslatedSegments(param, segments);
                } finally {
                    // Always clear in-progress marker regardless of apply outcome.
                    HookMain.clearInProgress(param.thisObject);
                }
            });
        });
    }

    /**
     * Try to fill segments from the in-memory cache, or mark as not needing translation.
     * No network, no blocking on remote calls.
     *
     * @return true if ALL segments are resolved (translatedText filled or no-need); false otherwise.
     */
    public static boolean fillSegmentsFromCacheOrDbOrNoNeed(List<Segment> segments) {
        ServerConfig config = getFreshServerConfigOrTriggerRefresh();
        boolean allResolved = true;
        for (Segment seg : segments) {
            final String text = seg.text;
            if (text == null || text.trim().isEmpty()) {
                seg.translatedText = text;
                continue;
            }
            if (!isTranslationNeeded(text)) {
                seg.translatedText = text;
                continue;
            }

            String cacheKey = buildCacheKey(config, text);

            // memory cache
            if (cacheKey != null) {
                String cached = translationCache.get(cacheKey);
                if (cached != null) {
                    seg.translatedText = cached;
                    continue;
                }
            }

            // Not resolved this time
            allResolved = false;
        }
        return allResolved;
    }

    /**
     * Perform *synchronous* quick local-service translations for unresolved segments.
     * Network I/O happens on background threads; UI thread just waits up to maxWaitMs.
     * When forceAwaitCompletion is true, waits until all worker tasks finish.
     *
     * @return true if after the quick phase ALL segments are resolved; false otherwise.
     */
    public static boolean quickTranslateUnresolvedSegmentsViaLocal(List<Segment> segments,
                                                                   long maxWaitMs,
                                                                   boolean forceAwaitCompletion) {
        // Collect unresolved segments
        final List<Segment> unresolved = new ArrayList<>();
        for (Segment seg : segments) {
            if (seg.translatedText == null) {
                // still unresolved and needs translation
                final String text = seg.text;
                if (text != null && !text.trim().isEmpty() && isTranslationNeeded(text)) {
                    unresolved.add(seg);
                } else {
                    // mark as no-need
                    seg.translatedText = seg.text;
                }
            }
        }
        if (unresolved.isEmpty()) {
            return true;
        }

        final CountDownLatch latch = new CountDownLatch(unresolved.size());

        for (Segment seg : unresolved) {
            TRANSLATION_EXECUTOR.submit(() -> {
                try {
                    ServerConfig config = getFreshServerConfigBlocking();
                    final String text = seg.text;
                    final String cacheKey = buildCacheKey(config, text);

                    // Double-check memory (race with other workers)
                    if (cacheKey != null) {
                        String cached = translationCache.get(cacheKey);
                        if (cached != null) {
                            seg.translatedText = cached;
                            latch.countDown();
                            return;
                        }
                    }

                    // Quick local-service call with small timeout
                    String result;
                    if (forceAwaitCompletion) {
                        result = translateByLocalService(text, cacheKey);
                    } else {
                        result = translateByLocalServiceQuick(text, cacheKey);
                    }
                    if (result != null) {
                        seg.translatedText = result;
                        if (cacheKey != null) {
                            translationCache.put(cacheKey, result);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (forceAwaitCompletion) {
                latch.await();
            } else {
                latch.await(Math.max(1, maxWaitMs), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Check if ALL segments are now resolved
        for (Segment seg : segments) {
            if (seg.translatedText == null && isTranslationNeeded(seg.text)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Background prefetch: perform network translation and populate memory/DB cache.
     * This does NOT attempt to apply UI changes directly.
     */
    public static void prefetchSegmentsAsync(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) return;
        // Copy texts to avoid mutating caller's segments
        List<Segment> copy = new ArrayList<>(segments.size());
        for (Segment s : segments) {
            Segment ns = new Segment(0, s.text == null ? 0 : s.text.length(), s.text == null ? "" : s.text);
            copy.add(ns);
        }
        TRANSLATION_EXECUTOR.submit(() -> doTranslateSegments(copy));
    }

    // -------------------------------------------------------------------------------
    private static void doTranslateSegments(List<Segment> mSegments) {
        ServerConfig config = getFreshServerConfigBlocking();
        // Translate segment by segment
        for (Segment seg : mSegments) {
            String text = seg.text;
            if (text == null || text.trim().isEmpty()) {
                seg.translatedText = text;
                continue;
            }

            String cacheKey = buildCacheKey(config, text);
            log(String.format("[%s] start translate", cacheKey));

            if (cacheKey != null) {
                log(String.format("[%s] checking cache", cacheKey));
                if (translationCache.containsKey(cacheKey)) {
                    seg.translatedText = translationCache.get(cacheKey);
                    log(String.format("[%s] hit from cache", cacheKey));
                    continue;
                }
            }

            if (!isTranslationNeeded(text)) {
                seg.translatedText = text;
                log(String.format("[%s] no translation needed", cacheKey));
                continue;
            }

            log(String.format("[%s] translate start by local service", cacheKey));
            String result = translateByLocalService(text, cacheKey);
            log(String.format("[%s] translate end by local service => %s", cacheKey, result));

            if (result == null) {
                seg.translatedText = text; // fallback to original on failure
            } else {
                seg.translatedText = result;
                if (cacheKey != null) {
                    translationCache.put(cacheKey, result);
                }
            }
        }
    }

    // ====== Local service (sync) ======
    private static String translateByLocalServiceQuick(String text, String cacheKey) {
        return translateByLocalServiceInternal(
                text,
                cacheKey,
                QUICK_LOCAL_CONNECT_TIMEOUT_MS,
                QUICK_LOCAL_READ_TIMEOUT_MS,
                false
        );
    }

    private static String translateByLocalService(String text, String cacheKey) {
        return translateByLocalServiceInternal(
                text,
                cacheKey,
                1000,
                3000,
                true
        );
    }

    private static String translateByLocalServiceInternal(
            String text,
            String cacheKey,
            int connectTimeoutMs,
            int readTimeoutMs,
            boolean verboseLog
    ) {
        try {
            String urlStr = String.format(
                    "https://127.0.0.1:%d/translate?q=%s",
                    LOCAL_SERVER_PORT,
                    URLEncoder.encode(text, "UTF-8")
            );

            if (verboseLog) {
                log(String.format("[%s] access local service => %s", cacheKey, urlStr));
            }

            URL url = new URL(urlStr);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setSSLSocketFactory(getOrCreateLocalPinnedFactory());
            conn.setHostnameVerifier(LOCAL_HOSTNAME_VERIFIER);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            int status = conn.getResponseCode();
            if (status != 200) {
                return null;
            }
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                String body = sb.toString();
                JSONObject obj = new JSONObject(body);
                if (obj.optInt("code", -1) != 0) return null;
                String result = obj.optString("text", null);
                return result == null ? null : result.trim();
            }
        } catch (Exception e) {
            if (verboseLog) {
                log(String.format("[%s] translate exception in local service => %s", cacheKey, e.getMessage()));
            }
            return null;
        }
    }

    private static SSLSocketFactory getOrCreateLocalPinnedFactory() throws Exception {
        if (LOCAL_PINNED_SSL_FACTORY != null) return LOCAL_PINNED_SSL_FACTORY;
        synchronized (MultiSegmentTranslateTask.class) {
            if (LOCAL_PINNED_SSL_FACTORY != null) return LOCAL_PINNED_SSL_FACTORY;

            // Always use built-in PEM to avoid package/asset lookup failures across users/profiles
            try (InputStream certStream = LocalCerts.openLocalHttpsServerCrt()) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(certStream);

                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null);
                ks.setCertificateEntry("local", cert);

                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);

                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, tmf.getTrustManagers(), new SecureRandom());
                LOCAL_PINNED_SSL_FACTORY = sc.getSocketFactory();
                return LOCAL_PINNED_SSL_FACTORY;
            }
        }
    }

    private static boolean isTranslationNeeded(String string) {
        // pure digits
        if (string == null) return false;
        if (string.matches("^\\d+$")) {
            return false;
        }
        // decimal coordinates-like
        if (string.matches("^\\d{1,3}\\.\\d+$")) {
            return false;
        }
        return true;
    }

    public static void translateFromJs(WebView webView, String requestId, String text, String srcLang, String tgtLang) {
        ServerConfig config = getFreshServerConfigBlocking();
        String cacheKey = buildCacheKey(config, text);
        log(String.format("[%s] start translate", cacheKey));

        // Do not cache WebView-triggered translations.
        log(String.format("[%s] translate start by local service", cacheKey));
        String result = translateByLocalService(text, cacheKey);
        log(String.format("[%s] translate end by local service => %s", cacheKey, result));

        if (result == null) {
            webView.post(() -> webView.evaluateJavascript(String.format("javascript:onXPTranslateCompleted('%s','%s')", requestId, text), null));
        } else {
            if (cacheKey != null) {
                translationCache.put(cacheKey, result);
            }
            String finalResult = result;
            webView.post(() -> webView.evaluateJavascript(String.format("javascript:onXPTranslateCompleted('%s','%s')", requestId, finalResult), null));
        }
    }
}
