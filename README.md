# Murmur

A personal Wispr Flow-style voice-to-text app for your phone. Tap the mic and talk. Murmur transcribes what you said, has Claude clean it up (no "um"s or false starts, real punctuation, your corrections applied), and copies it to your clipboard so you can paste it into any app.

It's a web app you add to your home screen. It runs on iPhone and Android, needs no App Store, and has no server of its own: your API keys stay on your phone and go straight to the providers.

## How it works

```
 mic ──► speech-to-text ──► Claude cleanup ──► clipboard
         (OpenAI or Groq)   (removes filler,    (paste anywhere)
                             fixes punctuation,
                             applies "no wait, I mean...")
```

- **Tap** the mic to start, tap again to finish. Or **hold** it and let go when you're done (push-to-talk).
- **Styles:** *Clean* (your words, tidied up), *Message* (casual text), *Email* (paragraphs), *Notes* (bullet points), *Raw* (no cleanup).
- **Redo in style** rewrites the last dictation in whichever style is selected, without recording again.
- **Original** shows exactly what the transcriber heard.
- **Personal dictionary** (in Settings): names, brands and jargon that should always be spelled your way.
- **History** keeps your last 100 dictations on the phone.

## Set it up (about 10 minutes)

### 1. Get API keys

You need a speech-to-text key. A Claude key is optional but strongly recommended, because the cleanup is what makes this feel like Wispr Flow.

| What | Where | Notes |
|---|---|---|
| Speech-to-text: **OpenAI** (default) | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) | Uses `gpt-4o-transcribe`; you can change the model in Settings. |
| Speech-to-text: **Groq** (alternative) | [console.groq.com/keys](https://console.groq.com/keys) | Uses `whisper-large-v3-turbo`. Very fast and cheap. |
| Cleanup: **Anthropic** | [console.anthropic.com](https://console.anthropic.com/settings/keys) | Claude Opus 5.5 by default. Switch to Claude Haiku 5.5 in Settings if you want it faster and cheaper. |

Set a monthly spending limit in each provider's console. Personal use costs very little, but a limit means a lost phone can't run up a bill.

### 2. Put it online

Microphones only work on secure (`https://`) pages, so the app needs a real URL. The easiest option is [Vercel](https://vercel.com) (free for personal use):

1. Sign in to Vercel with GitHub.
2. **Add New → Project**, pick this repository, then **Deploy**. Vercel recognizes the setup automatically (Vite). You don't need to change any settings.
3. You get a URL like `https://murmur-yourname.vercel.app`.

Any static host works too (Netlify, Cloudflare Pages, GitHub Pages). Build command: `npm run build`. Output folder: `dist`.

### 3. Install it on your phone

- **iPhone:** open the URL in **Safari**, tap **Share → Add to Home Screen**.
- **Android:** open the URL in **Chrome**, tap **⋮ → Add to Home screen** (or **Install app**).

Open Murmur from the home screen. It asks for your keys the first time. Allow microphone access when prompted.

## Daily use

1. Open Murmur. Keep it in your dock so it's one tap away.
2. Tap the mic and talk naturally. Say "new paragraph" or "bullet point" if you want formatting. Correct yourself out loud ("at 3, no wait, 4") and only the correction is kept.
3. Tap again. A second or two later it says **Copied**.
4. Switch to any app and paste.

## Privacy

- Your keys, settings and history are saved only in this app's storage on your phone.
- Audio goes to the speech-to-text provider you picked. The transcript goes to Anthropic for cleanup. Nothing else is sent anywhere.
- Delete history any time (History → Clear all).

## Troubleshooting

- **"Microphone access is blocked":** iPhone: Settings → Safari → Microphone (or tap **aA** in Safari's address bar → Website Settings). Android: tap the lock icon next to the URL → Permissions.
- **"Couldn't reach OpenAI/Groq":** check your connection. If you're online and it still fails, the browser may be blocking the direct call. When deployed on Vercel, Murmur automatically retries through `api/transcribe.js`, a small relay that only passes your request along and stores nothing. The relay handles uploads up to about 4.5 MB (roughly 5 minutes of speech).
- **"...rejected the API key":** re-paste the key in Settings. Keys often get cut off when copied on a phone.
- **Text comes back raw (not cleaned up):** the status line says why, usually a missing or invalid Anthropic key.
- **iPhone asks for the microphone every time:** this is an iOS quirk with home-screen apps. Updating iOS usually reduces it.
- Recordings stop automatically at 10 minutes to stay under the providers' 25 MB upload limit.

## Develop

```bash
npm install
npm run dev      # http://localhost:5173 (the mic works on localhost)
npm run build    # production build in dist/
```

The code is plain JavaScript with no framework:

| File | What it does |
|---|---|
| `src/main.js` | Screen logic: mic button, styles, history, settings |
| `src/recorder.js` | Microphone recording and the live level ring |
| `src/transcribe.js` | Speech-to-text (any OpenAI-compatible endpoint) |
| `src/polish.js` | Claude cleanup: the prompt and the styles live here |
| `src/clipboard.js` | Copying that works on iPhone after a delay |
| `src/storage.js` | Settings and history on the device |
| `public/sw.js` | Offline app shell |
| `api/transcribe.js` | Optional Vercel relay for speech-to-text |

To change how text is cleaned up, edit `BASE_PROMPT` and `STYLES` in `src/polish.js`.

## Ideas for later

- **System-wide keyboard:** a native Android keyboard (IME) can record and type straight into any app. On iPhone a custom keyboard can't use the microphone itself, so it has to hand off to the main app, which is how Wispr Flow does it. Both need native code (Kotlin or Swift).
- **Voice commands on selected text:** "make this shorter", "translate to Spanish".
- **Per-app styles:** remember Email style for one context and Message style for another.
