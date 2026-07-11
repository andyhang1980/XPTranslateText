# Xposed Translate Text

Auto-translate app text with a local-first pipeline: local cache → on-device
[ML Kit (local server)](https://developers.google.com/ml-kit/language/translation) → [Gemini API](https://ai.google.dev/gemini-api/docs/pricing?hl=zh-tw#gemini-2.0-flash-lite) → [Free Google API](https://github.com/ssut/py-googletrans/issues/268).

## What's New

### 3.0

- Translation speed improvements by shifting language-pair handling to the local server and reducing hook-side work.
- Refactored into a multi-module, multi-APK setup: full / hook-only / server-only.
- Supports unroot environments (tested with NPatch v0.7.3): patch target app with hook-only + install server-only.

### 2.0

- Prioritize on-device translation via a built-in local server using **Google ML Kit**.
- Added translation support for **android.text.StaticLayout$Builder** to reduce UI jank by replacing text synchronously when possible.

## Installation & Setup

### Root (LSPosed / Xposed)

Requirements:
- [LSPosed](https://github.com/LSPosed/LSPosed) (or another Xposed framework) installed and enabled.

Steps:
1. Install the full APK (`app`).
2. Open the LSPosed manager, enable **XPTranslateText**, and select your target apps.
3. Open the app, enable the "Local Translation Server" switch, and configure settings.
4. Force stop the target app and relaunch.

### Unroot (NPatch / LSPatch)

Tested with:
- NPatch v0.7.3

Steps:
1. Patch the target app with `app-hook` (hook-only APK).
2. Install `app-server` (server-only APK).
3. Open `app-server` and start the local translation server (manual start).
4. Launch the patched target app.

## Compatibility
- Android 13 with LSPosed (v1.9.2-it(7024))
- Android 15 with LSPosed (v1.9.2-it(7024))
- Android 16 with LSPosed (v1.9.2-it(7412))
- Unroot: NPatch v0.7.3 (hook-only + server-only)

## Live Demo (Reddit Translation)

<img src="images/translate-reddit.gif" width="300" alt="Live Reddit translation via on-device ML Kit" />

## Before & After Comparison

| Before                                    | After                                    |
|-------------------------------------------|------------------------------------------|
| <img src="images/before.png" width="300"> | <img src="images/after.png" width="300"> |

## Project Structure

```text
XPTranslateText/
├── app/                     # full APK (hook + server)
├── app-hook/                # hook-only APK (for NPatch/LSPatch patching)
├── app-server/              # server-only APK (manual start)
├── core-hook/               # hook entry + client (no UI)
├── core-server/             # local server + UI + resources
├── app/libs/                # Xposed/Android API stubs (compileOnly)
├── gradlew
├── gradlew.bat
├── keystore.properties      # Local-only, not committed
└── settings.gradle
```

## Multiple APKs (full / hook-only / server-only)

- `app` (full): For normal root / LSPosed usage (one APK includes hook + local server + UI).
- `app-hook` (hook-only): For patchers like NPatch/LSPatch to embed the hook into a target app (no UI / no server).
- `app-server` (server-only): Standalone local translation server app (manually start/stop).

### Build Commands (Gradle Wrapper)

- full Debug: `./gradlew :app:assembleDebug`
- hook-only Debug: `./gradlew :app-hook:assembleDebug`
- server-only Debug: `./gradlew :app-server:assembleDebug`
- Requires Java 17 (macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`)

## Local ML Kit Server

- Runs as a foreground service on `127.0.0.1:18181`.
- Endpoint: `/translate?q=...` (`src`/`dst` are optional)
  - If `src`/`dst` are omitted, the server uses saved settings from `xp_translate_text_configs`.
  - `src=auto` enables automatic language detection (ML Kit Language ID).
- Endpoint: `/config`
  - Returns JSON: `{"code":0,"source_lang":"auto","target_lang":"zh-TW","force_wait_local":false}`
  - `core-hook` reads this to avoid `XSharedPreferences` (works for both root and unroot / NPatch scenarios).
  - Hook-side config cache TTL: **1s**; when language pair changes, the in-memory translation cache is cleared.
- Models are downloaded on-demand and kept on-device; last-used times are tracked to help with maintenance.

## Hook Methods & Translation Workflow
- **android.widget.TextView & Custom Components:**
  - Automatically translates text set via the `setText()` method.
  - Translation pipeline:
    1. **Local Translation Cache** (memory + SQLite) to avoid repeated work.
    2. **Local ML Kit Server** (on-device, 127.0.0.1:18181) with short timeouts to keep UI responsive.
    3. **Gemini API (gemini-2.0-flash-lite)** as network fallback.
    4. **Free Google API** as the final fallback.

- **android.text.StaticLayout$Builder:**
  - Intercepts `StaticLayout.Builder.build()` and attempts a synchronous replacement when translations are readily available (cache/DB) or quickly obtained from the local ML Kit server.
  - If not immediately available, it returns the original layout and prefetches translations asynchronously for subsequent renders.
  - Spans and formatting are preserved.

- **android.webkit.WebView:**
  - Performs real-time translation of visible webpage text via a JS bridge.
  - Pipeline: **Local ML Kit Server → Gemini API → Free Google API** (no caching for WebView).

## Custom LLM (DeepSeek / SiliconFlow / OpenAI-compatible)

You can replace Gemini with any OpenAI-compatible chat API (DeepSeek, SiliconFlow/硅基流动, OpenAI,
local LLMs like Ollama, etc.) as the online translation backend.

1. Open the app → **Custom LLM** card → **Configure custom LLM**.
2. Toggle **Enable custom LLM**.
3. Pick a provider preset (DeepSeek / SiliconFlow) or **Custom** and fill in:
   - **API Base URL** — e.g. `https://api.deepseek.com/v1` or `https://api.siliconflow.cn/v1`.
   - **API Key** — leave empty for keyless/local endpoints.
   - **Model** — e.g. `deepseek-chat`, `Qwen/Qwen2.5-7B-Instruct`.
4. Save. Changes apply immediately (no server restart needed).

The request is a standard `POST {base_url}/chat/completions` call:

```json
{
  "model": "<model>",
  "messages": [
    {"role": "system", "content": "Translate the following text into <target_lang> only..."},
    {"role": "user", "content": "<text>"}
  ],
  "temperature": 0.3,
  "stream": false
}
```

Resulting translation pipeline (when custom LLM is enabled):

```text
Local ML Kit Server → Custom LLM (OpenAI-compatible) → Free Google API (final fallback)
```

When the custom LLM is disabled, the original **Gemini API → Free Google API** pipeline is used.


## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=tianci-sh/XPTranslateText&type=Date)](https://www.star-history.com/#tianci-sh/XPTranslateText&Date)
