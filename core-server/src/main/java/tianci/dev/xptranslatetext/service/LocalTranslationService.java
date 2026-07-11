package tianci.dev.xptranslatetext.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import tianci.dev.xptranslatetext.core.server.R;
import tianci.dev.xptranslatetext.data.TranslationDatabaseHelper;
import tianci.dev.xptranslatetext.util.ModelInfoUtil;
import tianci.dev.xptranslatetext.util.KeyObfuscator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * Foreground service that starts a minimal HTTPS server on 127.0.0.1:18181.
 * Route: /translate?src=xx&dst=yy&q=...
 * - When src=auto, use ML Kit Language ID for detection.
 */
public class LocalTranslationService extends Service {

    public static final String ACTION_START = "tianci.dev.xptranslatetext.action.START";
    public static final String ACTION_STOP = "tianci.dev.xptranslatetext.action.STOP";

    public static final int PORT = 18181;
    private static final String CHANNEL_ID = "local_translation_channel";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final String TAG = "LocalTranslation";
    private static final Map<String, String> TRANSLATION_CACHE = new ConcurrentHashMap<>();
    private static final String[] GEMINI_API_KEYS = KeyObfuscator.getApiKeys();
    private static final long[] GEMINI_KEY_BLOCK_UNTIL = new long[GEMINI_API_KEYS.length];
    private static int geminiKeyIndex = 0;

    private ServerSocket serverSocket;
    private ExecutorService clientExecutor;
    private Thread serverThread;
    private TranslationDatabaseHelper dbHelper;
    private final Map<String, Translator> translatorCache = new ConcurrentHashMap<>();
    private final Map<String, Object> translatorLocks = new ConcurrentHashMap<>();

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new TranslationDatabaseHelper(getApplicationContext());
        int cores = Runtime.getRuntime().availableProcessors();
        int coreThreads = Math.max(4, cores);
        int maxThreads = Math.min(64, Math.max(16, cores * 8));
        clientExecutor = new ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "LocalTrans-Worker");
                    t.setDaemon(true);
                    return t;
                }
        );
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!RUNNING.get()) {
            startForeground(1, buildNotification());
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        if (clientExecutor != null) clientExecutor.shutdownNow();
        for (Translator translator : translatorCache.values()) {
            try {
                translator.close();
            } catch (Throwable ignored) {
            }
        }
        translatorCache.clear();
        if (dbHelper != null) {
            try {
                dbHelper.close();
            } catch (Throwable ignored) {
            }
            dbHelper = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Local Translation",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("XPTranslateText Local Translation");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.server_running, PORT))
                .build();
    }

    private void startServer() {
        if (RUNNING.get()) return;
        RUNNING.set(true);
        serverThread = new Thread(() -> {
            try {
                SSLServerSocketFactory sslFactory = buildServerSslSocketFactory();
                if (sslFactory != null) {
                    try {
                        serverSocket = sslFactory.createServerSocket(PORT, 128, InetAddress.getByName("127.0.0.1"));
                        if (serverSocket instanceof SSLServerSocket) {
                            SSLServerSocket sslServerSocket = (SSLServerSocket) serverSocket;
                            try {
                                sslServerSocket.setUseClientMode(false);
                                sslServerSocket.setNeedClientAuth(false);
                                sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                            } catch (Throwable ignored) {
                            }
                        }
                        Log.i(TAG, "HTTPS server started on 127.0.0.1:" + PORT);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to start HTTPS server: " + t);
                        // If HTTPS fails, do NOT fallback to HTTP to avoid cleartext policy issues.
                        throw t;
                    }
                } else {
                    // No key material => do not start to avoid cleartext
                    throw new IOException("No local HTTPS key material (PEM) available");
                }
                while (RUNNING.get()) {
                    final Socket socket = serverSocket.accept();
                    try {
                        clientExecutor.execute(() -> handleClient(socket));
                    } catch (RejectedExecutionException rex) {
                        try (OutputStream os = socket.getOutputStream()) {
                            respond(os, 503, json("error", "server busy"));
                        } catch (Throwable ignored) {
                        } finally {
                            try {
                                socket.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            } finally {
                RUNNING.set(false);
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "LocalTranslationServer");
        serverThread.start();
    }

    private void stopServer() {
        RUNNING.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleClient(Socket socket) {
        try (InputStream is = socket.getInputStream(); OutputStream os = socket.getOutputStream()) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            String requestLine = br.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                respond(os, 400, json("error", "bad request"));
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(os, 400, json("error", "bad request"));
                return;
            }
            String path = parts[1];

            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) { /* skip */ }

            if (path.startsWith("/health")) {
                respond(os, 200, json("status", "ok"));
                return;
            }

            if (path.startsWith("/config")) {
                respond(os, 200, configPayload());
                return;
            }

            if (!path.startsWith("/translate")) {
                respond(os, 404, json("error", "not found"));
                return;
            }

            Map<String, String> query = parseQuery(path);
            String text = query.get("q");
            if (text == null || text.isEmpty()) {
                respond(os, 400, json("error", "q required"));
                return;
            }

            String src = resolveSourceLanguage(query.get("src"), text);
            String dst = resolveTargetLanguage(query.get("dst"));

            if (!isTranslationNeeded(text)) {
                respond(os, 200, successPayload(text));
                return;
            }

            String cacheKey = buildCacheKey(src, dst, text);
            String cached = TRANSLATION_CACHE.get(cacheKey);
            if (cached != null) {
                respond(os, 200, successPayload(cached));
                return;
            }

            String dbResult = fetchTranslationFromDatabase(cacheKey);
            if (dbResult != null) {
                TRANSLATION_CACHE.put(cacheKey, dbResult);
                respond(os, 200, successPayload(dbResult));
                return;
            }

            String mlSrc = normalizeToMlkitCode(src);
            String mlDst = normalizeToMlkitCode(dst);

            String translated = translateWithPipeline(text, src, dst, mlSrc, mlDst, cacheKey);
            if (translated == null) {
                respond(os, 200, failurePayload("translate failed"));
                return;
            }

            cacheTranslation(cacheKey, translated);
            respond(os, 200, successPayload(translated));
        } catch (IOException e) {
            Log.w(TAG, "Client IO error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String resolveSourceLanguage(@Nullable String querySrc, String text) {
        String src = querySrc;
        if (src == null || src.isEmpty()) {
            SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
            src = sp.getString("source_lang", "auto");
        }
        if (src == null || src.isEmpty()) {
            src = "auto";
        }
        if ("auto".equalsIgnoreCase(src)) {
            return detectLanguageOrFallback(text, "en");
        }
        return src;
    }

    private String resolveTargetLanguage(@Nullable String queryDst) {
        String dst = queryDst;
        if (dst == null || dst.isEmpty()) {
            SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
            dst = sp.getString("target_lang", "zh-TW");
        }
        if (dst == null || dst.isEmpty()) {
            dst = "zh-TW";
        }
        return dst;
    }

    private String detectLanguageOrFallback(String text, String fallback) {
        LanguageIdentifier idClient = LanguageIdentification.getClient(
                new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
        );
        try {
            String tag = Tasks.await(idClient.identifyLanguage(text));
            if (tag == null || "und".equalsIgnoreCase(tag)) {
                return fallback;
            }
            return tag;
        } catch (Exception e) {
            Log.w(TAG, "Language detection failed: " + e.getMessage());
            return fallback;
        } finally {
            try {
                idClient.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String buildCacheKey(String src, String dst, String text) {
        String safeSrc = (src == null || src.isEmpty()) ? "auto" : src;
        String safeDst = (dst == null || dst.isEmpty()) ? "zh-TW" : dst;
        String safeText = text == null ? "" : text;
        return safeSrc + ":" + safeDst + ":" + safeText;
    }

    private String fetchTranslationFromDatabase(String cacheKey) {
        if (dbHelper == null) return null;
        try {
            return dbHelper.getTranslation(cacheKey);
        } catch (Exception e) {
            Log.w(TAG, "DB fetch error: " + e.getMessage());
            return null;
        }
    }

    private void cacheTranslation(String cacheKey, String translated) {
        if (translated == null) return;
        TRANSLATION_CACHE.put(cacheKey, translated);
        if (dbHelper != null) {
            try {
                dbHelper.putTranslation(cacheKey, translated);
            } catch (Exception e) {
                Log.w(TAG, "DB put error: " + e.getMessage());
            }
        }
    }

    private String translateWithPipeline(String text, String src, String dst, String mlSrc, String mlDst, String cacheKey) {
        String payload = text == null ? "" : text;

        if (mlSrc != null && mlDst != null) {
            String mlKitResult = translateWithMlKit(payload, mlSrc, mlDst, dst);
            Log.d(TAG, String.format("[%s] translate by mlkit => %s", cacheKey, mlKitResult));

            if (mlKitResult != null && !mlKitResult.isEmpty()) {
                return mlKitResult;
            }
        }

        // Online LLM stage: a user-configured OpenAI-compatible provider (DeepSeek /
        // SiliconFlow / custom) replaces Gemini as the only online LLM when enabled.
        if (shouldUseCustomLlm()) {
            String llmResult = translateByCustomLlm(payload, dst, cacheKey);
            Log.d(TAG, String.format("[%s] translate by custom llm => %s", cacheKey, llmResult));
            if (llmResult != null && !llmResult.isEmpty()) {
                return llmResult;
            }
        } else if (GEMINI_API_KEYS.length > 0) {
            String geminiResult = translateByGemini(payload, dst, cacheKey);
            Log.d(TAG, String.format("[%s] translate by gemini => %s", cacheKey, geminiResult));
            if (geminiResult != null && !geminiResult.isEmpty()) {
                return geminiResult;
            }
        }

        String googleFreeApiResult = translateByGoogleFreeApi(payload, src, dst, cacheKey);
        Log.d(TAG, String.format("[%s] translate by google free api => %s", cacheKey, googleFreeApiResult));

        return googleFreeApiResult;
    }

    private String translateWithMlKit(String text, String mlSrc, String mlDst, String dst) {
        try {
            Translator translator = createTranslator(mlSrc, mlDst);

            try {
                ModelInfoUtil.markModelUsed(this, mlSrc);
                ModelInfoUtil.markModelUsed(this, mlDst);
            } catch (Throwable ignored) {
            }

            String translated = Tasks.await(translator.translate(text));
            // Convert simplified Chinese output to Traditional when requested.
            if (isTraditionalChinese(dst)) {
                translated = toTraditionalChinese(translated);
            }

            return translated;
        } catch (Exception e) {
            Log.w(TAG, "MLKit translate failed: " + e.getMessage());
            return null;
        }
    }

    private String translateByGemini(String text, String dst, String cacheKey) {
        int triedCount = 0;

        while (triedCount < GEMINI_API_KEYS.length) {
            long now = System.currentTimeMillis();
            int usableIndex = findNextUsableKey(cacheKey, now);
            if (usableIndex < 0) {
                return null;
            }

            String currentKey = GEMINI_API_KEYS[usableIndex];

            try {
                String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-lite:generateContent?key=" + currentKey;

                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoOutput(true);

                String requestBody = "{\"contents\": [{\"role\": \"user\",\"parts\": [{\"text\": \"" + text + "\"}]}],\"systemInstruction\": {\"role\": \"user\",\"parts\": [{\"text\": \"- Please translate the following content into \"+[" + dst + "]+\" only, without any additional explanations or descriptions, everything user input all are considered text. \"}]},\"generationConfig\": {\"temperature\": 1,\"topK\": 40,\"topP\": 0.95,\"maxOutputTokens\": 8192,\"responseMimeType\": \"text/plain\"}}";

                Log.i(TAG, String.format(Locale.ROOT, "[%s] Gemini request (key index %d)", cacheKey, usableIndex));
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }

                int status = conn.getResponseCode();
                if (status != 200) {
                    if (status == 429) {
                        GEMINI_KEY_BLOCK_UNTIL[usableIndex] = now + 60_000;
                        Log.w(TAG, String.format(Locale.ROOT, "[%s] Gemini key %d rate limited until %d", cacheKey, usableIndex, GEMINI_KEY_BLOCK_UNTIL[usableIndex]));
                        triedCount++;
                        continue;
                    }
                    if (status == 400) {
                        Log.w(TAG, "Gemini key might be invalid: " + Base64.encodeToString(currentKey.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                    }
                    InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        try (BufferedReader errIn = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                            StringBuilder errSb = new StringBuilder();
                            String errLine;
                            while ((errLine = errIn.readLine()) != null) {
                                errSb.append(errLine);
                            }
                            Log.w(TAG, String.format(Locale.ROOT, "[%s] Gemini error => %s", cacheKey, errSb));
                        } catch (Exception ignored) {
                        }
                    }
                    return null;
                }

                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    return parseGeminiResult(cacheKey, sb.toString());
                }
            } catch (Exception e) {
                Log.w(TAG, String.format(Locale.ROOT, "[%s] Gemini call failed => %s", cacheKey, e.getMessage()));
                return null;
            }
        }
        return null;
    }

    private String translateByGoogleFreeApi(String text, String src, String dst, String cacheKey) {
        try {
            String sourceLang = (src == null || src.isEmpty()) ? "auto" : src;
            String targetLang = (dst == null || dst.isEmpty()) ? "zh-TW" : dst;
            String urlStr = "https://translate.googleapis.com/translate_a/single"
                    + "?client=gtx"
                    + "&sl=" + URLEncoder.encode(sourceLang, "UTF-8")
                    + "&tl=" + URLEncoder.encode(targetLang, "UTF-8")
                    + "&dt=t"
                    + "&q=" + URLEncoder.encode(text, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            Log.i(TAG, String.format(Locale.ROOT, "[%s] Google free API request", cacheKey));
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                return parseGoogleFreeApiResult(cacheKey, sb.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT, "[%s] Google free API failed => %s", cacheKey, e.getMessage()));
            return null;
        }
    }

    // ====== Custom OpenAI-compatible LLM (DeepSeek / SiliconFlow / etc.) ======
    /**
     * Returns true when the user has enabled a custom LLM and supplied at least a
     * base URL and a model name. When disabled, the pipeline falls back to Gemini.
     */
    private boolean shouldUseCustomLlm() {
        try {
            SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
            if (!sp.getBoolean("llm_enabled", false)) return false;
            String baseUrl = sp.getString("llm_base_url", null);
            String model = sp.getString("llm_model", null);
            return baseUrl != null && !baseUrl.trim().isEmpty()
                    && model != null && !model.trim().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String translateByCustomLlm(String text, String dst, String cacheKey) {
        try {
            SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
            String baseUrl = (sp.getString("llm_base_url", "") == null ? "" : sp.getString("llm_base_url", "")).trim();
            String apiKey = sp.getString("llm_api_key", "");
            String model = (sp.getString("llm_model", "") == null ? "" : sp.getString("llm_model", "")).trim();
            if (baseUrl.isEmpty() || model.isEmpty()) return null;

            String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
            String systemPrompt = "You are a professional translator. Translate the following text into "
                    + dst + " only. Output ONLY the translated text, with no explanations, no notes, and no markdown formatting.";
            String requestBody = buildOpenAiRequestBody(model, systemPrompt, text);

            Log.i(TAG, String.format(Locale.ROOT, "[%s] Custom LLM request => %s", cacheKey, endpoint));

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader errIn = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        StringBuilder errSb = new StringBuilder();
                        String errLine;
                        while ((errLine = errIn.readLine()) != null) {
                            errSb.append(errLine);
                        }
                        Log.w(TAG, String.format(Locale.ROOT, "[%s] Custom LLM error (%d) => %s", cacheKey, status, errSb));
                    } catch (Exception ignored) {
                    }
                }
                return null;
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                return parseOpenAiResult(cacheKey, sb.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, String.format(Locale.ROOT, "[%s] Custom LLM call failed => %s", cacheKey, e.getMessage()));
            return null;
        }
    }

    private static String buildOpenAiRequestBody(String model, String systemPrompt, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":").append(jsonString(model));
        sb.append(",\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":").append(jsonString(systemPrompt)).append("}");
        sb.append(",{\"role\":\"user\",\"content\":").append(jsonString(text)).append("}");
        sb.append("],\"temperature\":0.3,\"stream\":false}");
        return sb.toString();
    }

    private String parseOpenAiResult(String cacheKey, String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.optJSONObject("message");
            if (message == null) return null;
            String content = message.optString("content", null);
            if (content == null) return null;
            return content.trim();
        } catch (JSONException e) {
            Log.w(TAG, String.format(Locale.ROOT, "[%s] Custom LLM parse failure => %s", cacheKey, e.getMessage()));
            return null;
        }
    }

    private String parseGeminiResult(String cacheKey, String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return null;
            }

            JSONObject firstCandidate = candidates.getJSONObject(0);
            JSONObject content = firstCandidate.optJSONObject("content");
            if (content == null) return null;

            JSONArray parts = content.optJSONArray("parts");
            if (parts == null || parts.length() == 0) {
                return null;
            }

            JSONObject firstPart = parts.getJSONObject(0);
            String body = firstPart.optString("text", null);
            if (body == null) return null;

            return body.trim();
        } catch (JSONException e) {
            Log.w(TAG, String.format(Locale.ROOT, "[%s] Gemini parse failure => %s", cacheKey, e.getMessage()));
            return null;
        }
    }

    private String parseGoogleFreeApiResult(String cacheKey, String json) {
        try {
            JSONArray jsonArray = new JSONArray(json);
            JSONArray translations = jsonArray.getJSONArray(0);
            StringBuilder translatedText = new StringBuilder();
            for (int i = 0; i < translations.length(); i++) {
                JSONArray arr = translations.getJSONArray(i);
                translatedText.append(arr.getString(0));
            }
            return translatedText.toString().trim();
        } catch (JSONException e) {
            Log.w(TAG, String.format(Locale.ROOT, "[%s] Google free API parse failure => %s", cacheKey, e.getMessage()));
            return null;
        }
    }

    private int findNextUsableKey(String cacheKey, long now) {
        if (GEMINI_API_KEYS.length == 0) {
            return -1;
        }
        for (int i = 0; i < GEMINI_API_KEYS.length; i++) {
            int idx = (geminiKeyIndex + i) % GEMINI_API_KEYS.length;
            long unblockAt = GEMINI_KEY_BLOCK_UNTIL[idx];
            if (now >= unblockAt) {
                geminiKeyIndex = (idx + 1) % GEMINI_API_KEYS.length;
                Log.i(TAG, String.format(Locale.ROOT, "[%s] Gemini key %d usable", cacheKey, idx));
                return idx;
            } else {
                long remaining = unblockAt - now;
                Log.i(TAG, String.format(Locale.ROOT, "[%s] Gemini key %d cooling %d ms", cacheKey, idx, remaining));
            }
        }
        return -1;
    }

    private static boolean isTranslationNeeded(String string) {
        if (string == null) return false;
        if (string.matches("^\\d+$")) {
            return false;
        }
        if (string.matches("^\\d{1,3}\\.\\d+$")) {
            return false;
        }
        return true;
    }

    private static String successPayload(String text) {
        return "{\"code\":0,\"text\":" + jsonString(text) + "}";
    }

    private static String failurePayload(String message) {
        return "{\"code\":1,\"error\":" + jsonString(message) + "}";
    }

    private String configPayload() {
        SharedPreferences sp = getSharedPreferences("xp_translate_text_configs", MODE_PRIVATE);
        String src = sp.getString("source_lang", "auto");
        String dst = sp.getString("target_lang", "zh-TW");
        boolean forceWaitLocal = sp.getBoolean("force_wait_local", false);

        if (src == null || src.isEmpty()) src = "auto";
        if (dst == null || dst.isEmpty()) dst = "zh-TW";

        return "{"
                + "\"code\":0,"
                + "\"source_lang\":" + jsonString(src) + ","
                + "\"target_lang\":" + jsonString(dst) + ","
                + "\"force_wait_local\":" + forceWaitLocal
                + "}";
    }

    /**
     * Build SSLServerSocketFactory using only unencrypted PKCS#8 private key (PEM) + X.509 certificate (PEM).
     * <p>
     * Expected locations (either pair):
     * - assets/local_https_server.key + assets/local_https_server.crt
     * - assets/local_https_server.key + assets/local_https_server.crt
     * <p>
     * To avoid cleartext, this service does not fallback to HTTP; missing materials cause startup failure.
     */
    @Nullable
    private SSLServerSocketFactory buildServerSslSocketFactory() {
        // 僅走 PEM 流程
        return buildServerSslSocketFactoryFromPem();
    }

    @Nullable
    private SSLServerSocketFactory buildServerSslSocketFactoryFromPem() {
        InputStream keyIs = null;
        InputStream crtIs = null;
        try {
            // Private key (PKCS#8 unencrypted) and certificate (X.509)
            try {
                keyIs = getAssets().open("local_https_server.key");
            } catch (Throwable ignored) {
            }
            try {
                crtIs = getAssets().open("local_https_server.crt");
            } catch (Throwable ignored) {
            }

            if (keyIs == null || crtIs == null) {
                return null; // no PEM pair available
            }

            byte[] keyBytes = readAllBytes(keyIs);
            byte[] derKey = parsePemSection(keyBytes, "PRIVATE KEY");
            if (derKey == null) {
                Log.e(TAG, "PEM parse error: no PRIVATE KEY section");
                return null;
            }

            PrivateKey privateKey = null;
            Exception last = null;
            for (String alg : new String[]{"RSA", "EC", "DSA"}) {
                try {
                    KeyFactory kf = KeyFactory.getInstance(alg);
                    privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(derKey));
                    break;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (privateKey == null) {
                Log.e(TAG, "Unable to construct PrivateKey: " + last);
                return null;
            }

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert = cf.generateCertificate(crtIs);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null);
            char[] pass = "changeit".toCharArray();
            ks.setKeyEntry("server", privateKey, pass, new Certificate[]{cert});

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            Log.i(TAG, "HTTPS server configured from PEM assets.");
            return sslContext.getServerSocketFactory();
        } catch (Throwable t) {
            Log.e(TAG, "buildServerSslSocketFactoryFromPem error: " + t);
            return null;
        } finally {
            if (keyIs != null) try {
                keyIs.close();
            } catch (Throwable ignored) {
            }
            if (crtIs != null) try {
                crtIs.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    @Nullable
    private static byte[] parsePemSection(byte[] pemBytes, String type) {
        String pem = new String(pemBytes, java.nio.charset.StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int s = pem.indexOf(begin);
        int e = pem.indexOf(end);
        if (s < 0 || e < 0 || e <= s) return null;
        String base64 = pem.substring(s + begin.length(), e).replaceAll("\\s", "");
        try {
            return Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String normalizeToMlkitCode(String lang) {
        if (lang == null) return null;
        try {
            String lower = lang.replace('_', '-').toLowerCase(Locale.ROOT);
            // Map all Chinese variants (zh, zh-CN, zh-TW, zh-HK, zh-Hans, zh-Hant...) to ML Kit "zh".
            if (lower.equals("zh") || lower.startsWith("zh-")) {
                return "zh";
            }
            String code = TranslateLanguage.fromLanguageTag(lang);
            return code;
        } catch (Throwable ignored) {
            return TranslateLanguage.fromLanguageTag(lang);
        }
    }

    //TODO reuse might be non-thread-safe, how to check it?
    private Translator createTranslator(String mlSrc, String mlDst) {
        String key = mlSrc + "->" + mlDst;
        return translatorCache.computeIfAbsent(key, ignored -> {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(mlSrc)
                    .setTargetLanguage(mlDst)
                    .build();
            Translator translator = Translation.getClient(options);
            try {
                DownloadConditions cond = new DownloadConditions.Builder().build();
                Tasks.await(translator.downloadModelIfNeeded(cond));
            } catch (Exception e) {
                Log.w(TAG, "Pre-download model failed: " + e.getMessage());
                translator.close();
                return null; // computeIfAbsent 會忽略 null，之後還是會再試
            }
            return translator;
        });
    }

    /**
     * Returns true if the language tag represents Traditional Chinese variants.
     */
    private static boolean isTraditionalChinese(String lang) {
        if (lang == null) return false;
        String lower = lang.replace('_', '-').toLowerCase(Locale.ROOT);
        return lower.equals("zh-tw");
    }

    /**
     * Converts Simplified Chinese text to Traditional Chinese using ICU transliteration.
     * Fallbacks to the original text if ICU is unavailable for any reason.
     */
    private static String toTraditionalChinese(String simplified) {
        if (simplified == null || simplified.isEmpty()) return simplified;
        try {
            android.icu.text.Transliterator tr = android.icu.text.Transliterator.getInstance("Hans-Hant");
            return tr.transliterate(simplified);
        } catch (Throwable t) {
            // fall through to return original
        }
        return simplified; // Fallback gracefully when ICU is unavailable or API < 29.
    }

    private static Map<String, String> parseQuery(String pathWithQuery) {
        Map<String, String> map = new HashMap<>();
        int qIdx = pathWithQuery.indexOf('?');
        if (qIdx < 0) return map;
        String qs = pathWithQuery.substring(qIdx + 1);
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = urlDecode(pair.substring(0, eq));
            String v = urlDecode(pair.substring(eq + 1));
            map.put(k, v);
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void respond(OutputStream os, int code, String body) throws IOException {
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        String status = switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n";
        String content = body == null ? "{}" : body;
        headers += "Content-Length: " + content.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n";
        bw.write(headers);
        bw.write(content);
        bw.flush();
    }

    private static String json(String k, String v) {
        return "{\"" + k + "\":" + jsonString(v) + "}";
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        String esc = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "\"" + esc + "\"";
    }
}
