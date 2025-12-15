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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import tianci.dev.xptranslatetext.HookMain;
import tianci.dev.xptranslatetext.service.LocalTranslationService;

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
 * Translate multiple segments with an in-memory cache backed by LocalTranslationService.
 */
public class MultiSegmentTranslateTask {
    private static final ExecutorService TRANSLATION_EXECUTOR = Executors.newCachedThreadPool();
    private static final Map<String, String> translationCache = new ConcurrentHashMap<>();

    // Cached SSL objects for local HTTPS pinning
    private static volatile SSLSocketFactory LOCAL_PINNED_SSL_FACTORY;
    private static final HostnameVerifier LOCAL_HOSTNAME_VERIFIER = (hostname, session) ->
            "127.0.0.1".equals(hostname) || "localhost".equals(hostname) || "::1".equals(hostname);

    private static final int QUICK_LOCAL_CONNECT_TIMEOUT_MS = 150; // keep short to avoid UI jank
    private static final int QUICK_LOCAL_READ_TIMEOUT_MS = 250;    // keep short to avoid UI jank

    private static void log(String msg) {
        XposedBridge.log(msg);
    }

    public static void translateSegmentsAsync(
            final XC_MethodHook.MethodHookParam param,
            final int translationId,
            final List<Segment> segments,
            final String srcLang,
            final String tgtLang
    ) {
        TRANSLATION_EXECUTOR.submit(() -> {
            doTranslateSegments(segments, srcLang, tgtLang);

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
    public static boolean fillSegmentsFromCacheOrDbOrNoNeed(List<Segment> segments, String srcLang, String tgtLang) {
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

            String cacheKey = srcLang + ":" + tgtLang + ":" + text;

            // memory cache
            String cached = translationCache.get(cacheKey);
            if (cached != null) {
                seg.translatedText = cached;
                continue;
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
                                                                   String srcLang,
                                                                   String tgtLang,
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
                    final String text = seg.text;
                    final String cacheKey = srcLang + ":" + tgtLang + ":" + text;

                    // Double-check memory (race with other workers)
                    String cached = translationCache.get(cacheKey);
                    if (cached != null) {
                        seg.translatedText = cached;
                        latch.countDown();
                        return;
                    }

                    // Quick local-service call with small timeout
                    String result;
                    if (forceAwaitCompletion) {
                        result = translateByLocalService(text, srcLang, tgtLang, cacheKey);
                    } else {
                        result = translateByLocalServiceQuick(text, srcLang, tgtLang, cacheKey);
                    }
                    if (result != null) {
                        seg.translatedText = result;
                        translationCache.put(cacheKey, result);
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
    public static void prefetchSegmentsAsync(List<Segment> segments, String srcLang, String tgtLang) {
        if (segments == null || segments.isEmpty()) return;
        // Copy texts to avoid mutating caller's segments
        List<Segment> copy = new ArrayList<>(segments.size());
        for (Segment s : segments) {
            Segment ns = new Segment(0, s.text == null ? 0 : s.text.length(), s.text == null ? "" : s.text);
            copy.add(ns);
        }
        TRANSLATION_EXECUTOR.submit(() -> doTranslateSegments(copy, srcLang, tgtLang));
    }

    // -------------------------------------------------------------------------------
    private static void doTranslateSegments(List<Segment> mSegments, String srcLang, String tgtLang) {
        // Translate segment by segment
        for (Segment seg : mSegments) {
            String text = seg.text;
            if (text == null || text.trim().isEmpty()) {
                seg.translatedText = text;
                continue;
            }

            String cacheKey = srcLang + ":" + tgtLang + ":" + text;
            log(String.format("[%s] start translate", cacheKey));

            log(String.format("[%s] checking cache", cacheKey));
            if (translationCache.containsKey(cacheKey)) {
                seg.translatedText = translationCache.get(cacheKey);
                log(String.format("[%s] hit from cache", cacheKey));
                continue;
            }

            if (!isTranslationNeeded(text)) {
                seg.translatedText = text;
                log(String.format("[%s] no translation needed", cacheKey));
                continue;
            }

            log(String.format("[%s] translate start by local service", cacheKey));
            String result = translateByLocalService(text, srcLang, tgtLang, cacheKey);
            log(String.format("[%s] translate end by local service => %s", cacheKey, result));

            if (result == null) {
                seg.translatedText = text; // fallback to original on failure
            } else {
                seg.translatedText = result;
                translationCache.put(cacheKey, result);
            }
        }
    }

    // ====== Local service (sync) ======
    private static String translateByLocalServiceQuick(String text, String src, String dst, String cacheKey) {
        return translateByLocalServiceInternal(
                text,
                src,
                dst,
                cacheKey,
                QUICK_LOCAL_CONNECT_TIMEOUT_MS,
                QUICK_LOCAL_READ_TIMEOUT_MS,
                false
        );
    }

    private static String translateByLocalService(String text, String src, String dst, String cacheKey) {
        return translateByLocalServiceInternal(
                text,
                src,
                dst,
                cacheKey,
                1000,
                3000,
                true
        );
    }

    private static String translateByLocalServiceInternal(
            String text,
            String src,
            String dst,
            String cacheKey,
            int connectTimeoutMs,
            int readTimeoutMs,
            boolean verboseLog
    ) {
        try {
            String urlStr = String.format(
                    "https://127.0.0.1:%d/translate?src=%s&dst=%s&q=%s",
                    LocalTranslationService.PORT,
                    URLEncoder.encode(src == null ? "auto" : src, "UTF-8"),
                    URLEncoder.encode(dst == null ? "zh-TW" : dst, "UTF-8"),
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
        String cacheKey = srcLang + ":" + tgtLang + ":" + text;
        log(String.format("[%s] start translate", cacheKey));

        // Do not cache WebView-triggered translations.
        log(String.format("[%s] translate start by local service", cacheKey));
        String result = translateByLocalService(text, srcLang, tgtLang, cacheKey);
        log(String.format("[%s] translate end by local service => %s", cacheKey, result));

        if (result == null) {
            webView.post(() -> webView.evaluateJavascript(String.format("javascript:onXPTranslateCompleted('%s','%s')", requestId, text), null));
        } else {
            translationCache.put(cacheKey, result);
            String finalResult = result;
            webView.post(() -> webView.evaluateJavascript(String.format("javascript:onXPTranslateCompleted('%s','%s')", requestId, finalResult), null));
        }
    }
}
