# Murmur

A personal Wispr Flow-style voice keyboard. Tap the mic and talk the way you normally do: English with a Pakistani accent, Urdu, or both in the same sentence. Murmur writes it down cleanly (no "um"s or false starts, real punctuation, your "no wait, I mean..." corrections applied) and types it into whatever app you're using.

It comes in two forms:

- **Android keyboard** (`android/`): switch to it from any app, talk, and the text is typed in for you.
- **Web app** (`src/`): works on iPhone and Android from the home screen. It copies the text, and you paste it.

Both use the same prompts (`shared/prompts.json`) and send your audio straight to the services you choose. There's no Murmur server, and your keys stay on your phone.

## How it works

```
 mic ──► speech to text ──────────► cleanup ──────────────► typed in (keyboard)
         Gemini (best for            Claude, or Gemini        or copied (web app)
         Urdu + English),            removes filler, fixes
         OpenAI or Groq              accent mis-hearings,
                                     writes Urdu your way
```

### How you speak

Choose one in settings:

| Setting | You say | You get |
|---|---|---|
| **English + Urdu, Urdu in Roman letters** (default) | "yaar mujhe kal um the report bhejni hai, so please remind me" | Yaar, mujhe kal report bhejni hai, so please remind me. |
| English + Urdu, Urdu in Urdu script | the same | یار، مجھے کل report بھیجنی ہے، so please remind me. |
| English only | English, maybe with a few Urdu words | English, with any Urdu kept in Roman letters |
| Translate everything to English | any mix | I need to send the report tomorrow, so please remind me. |

The mix is never translated unless you pick the last option. The cleanup also knows the typical ways a Pakistani accent gets misheard ("vork" for "work", "tree" for "three", "iskool" for "school") and fixes them when the sentence calls for it.

Why Gemini is the default for transcription: in the [UrduSpeech study (2026)](https://arxiv.org/abs/2605.17846), Whisper's word error rate rose from 29% to 53% on mixed Urdu-English speech, while Gemini stayed near 3%. `eval/` re-runs that kind of test on real Pakistani recordings (see [Speech test](#speech-test)).

### Styles

*Clean* (your words, tidied up), *Message* (casual text), *Email* (paragraphs), *Notes* (bullet points), *Raw* (no cleanup). On the keyboard, tapping a style right after a dictation rewrites that dictation in place.

## Android keyboard

### Install

1. On your phone, open **[the latest release](https://github.com/wardox-cloud/BKK/releases/latest)** and tap **murmur-keyboard.apk**.
2. Android asks whether your browser may install apps. Allow it for this one install. The app isn't in the Play Store, so Android calls it an "unknown app".
3. Open **Murmur** from your app drawer and follow the three steps on screen:
   1. **Turn on the Murmur keyboard** (Android shows a standard warning that keyboards can see what you type; Murmur only sends audio to the services you choose).
   2. **Allow the microphone.**
   3. **Add your keys** (see [Keys](#keys)) and tap **Save**.
4. Try it in the **Try it here** box: tap it, switch to Murmur, and talk.

New builds install over the old one and keep your settings.

### Use it

- In any app, switch to Murmur with the keyboard button at the bottom of your screen or the globe key on your usual keyboard.
- **Tap** the mic, talk, tap again. Or **hold** it while you talk and let go when you're done.
- The text appears where your cursor is, with a space added if needed.
- The keyboard button switches back to your usual keyboard (long-press it to pick a keyboard). In settings you can make Murmur switch back automatically after each dictation.
- **Undo** removes the last dictation. Backspace and enter work as usual.
- If something fails, the message tells you why. Tap it to retry without talking again.

## Keys

| What | Where to get it | Notes |
|---|---|---|
| **Gemini** (recommended for transcription) | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | Default model `gemini-3.6-flash`; change it in settings. Gemini has a free tier, but on the free tier Google may use what you send to improve its products. Turn on billing if that matters to you. |
| **Anthropic** (cleanup) | [console.anthropic.com](https://console.anthropic.com/settings/keys) | Claude Opus 5.5 by default; Sonnet 5.5 and Haiku 5.5 are cheaper (see [costs](#what-it-costs)). Optional: without it you get the raw transcript, or pick Gemini for cleanup. |
| OpenAI (optional) | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) | `gpt-4o-transcribe`. Fine for English, weaker on mixed Urdu-English. |
| Groq (optional) | [console.groq.com/keys](https://console.groq.com/keys) | `whisper-large-v3-turbo`. Fast and very cheap; weakest on mixed speech. |

**API usage is billed separately from a Claude subscription.** The Anthropic key draws on prepaid API credits from console.anthropic.com. It doesn't use your Claude Pro or Max chat limits, and those don't cover it. Set a monthly spending limit in each console so a lost phone can't run up a bill.

## What it costs

Estimates for a typical 30-second dictation (about 75 words). Cleanup sends about 850 tokens to Claude (mostly the fixed instructions) and gets about 250 back. Gemini prices are Gemini 3 Flash's published rates; check current pricing.

| Setup | Per dictation | Per month at 30 dictations a day |
|---|---|---|
| Gemini + Claude Opus 5.5 (default) | ~1¢ | ~$9 |
| Gemini + Claude Sonnet 5.5 | ~0.6¢ | ~$5 |
| Gemini + Claude Haiku 5.5 | ~0.2¢ | ~$2 |
| Gemini alone, one step | ~0.2¢ | ~$2 |

Gemini's share is about 0.2¢ of each figure. Switch models any time in settings.

## Web app

### Put it online

Microphones only work on secure (`https://`) pages. The easiest host is [Vercel](https://vercel.com) (free for personal use): sign in with GitHub, **Add New → Project**, pick this repository, **Deploy**. Any static host works too: build with `npm run build` and serve `dist/`.

### Install on your phone

- **iPhone:** open the URL in **Safari**, tap **Share → Add to Home Screen**.
- **Android:** open it in **Chrome**, tap **⋮ → Add to Home screen**.

Open it, add your keys, tap the mic, talk, tap again. It says **Copied**, and you paste wherever you like. **Redo in style**, **Original**, and **History** (last 100 dictations, kept on the phone) work as their names say.

## Privacy

- Keys, settings and history stay on your phone.
- Audio goes to the speech-to-text service you picked. The transcript goes to the cleanup service. Nothing else is sent anywhere.
- This repository is public, so the code is visible to anyone, but it contains no keys.

## Troubleshooting

- **"...rejected the API key":** re-paste the key. Keys often get cut off when copied on a phone.
- **Text comes back raw:** the message says why, usually a missing or invalid cleanup key.
- **Urdu comes out in the wrong script, or gets translated:** check **How you speak**, and use Gemini for transcription.
- **A name keeps coming out wrong:** add it to the personal dictionary.
- **Android won't install an update:** the new APK must be signed with the same key as the installed one. Builds from this repository's releases always are.
- **Web app, "Couldn't reach OpenAI/Groq":** when deployed on Vercel, Murmur retries through `api/transcribe.js`, a relay that only passes the request along. It handles about 5 minutes of audio.
- Dictations stop automatically at 6 minutes.

## Speech test

Results so far, with Whisper only because no API keys are set yet (20 real clips per set, October 2026):

| | Pakistani-accented English | Mixed Urdu + English |
|---|---|---|
| Whisper, no hint (a basic Groq setup) | 18% word errors | kept 40% of English words; often translated the Urdu into English, sometimes wrote Hindi script |
| Whisper + Murmur's Roman Urdu hint | 18% word errors | kept 73% of English words, mostly Roman letters, but still translates some clips |

Whisper is fine for accented English; its slips ("newlywed **chai** bride" for "shy bride") are the kind the cleanup step fixes. For mixed Urdu and English it isn't good enough, which is why Gemini is the default. An earlier hint with an English example sentence sometimes got copied into transcripts (2 of 20 clips). The current hint avoids full English sentences, and that dropped to 0 of 20.

`eval/speech_eval.py` checks transcription on the manually corrected [UrduSpeech](https://huggingface.co/datasets/ASLP-lab/UrduSpeech) benchmark: real Pakistani speakers from podcasts, interviews, vlogs and shows, in two sets (Pakistani-accented English, and mixed Urdu-English). It always runs Whisper locally (the open model Groq serves). To include Gemini, OpenAI, Groq and the Claude cleanup, add `GEMINI_API_KEY`, `OPENAI_API_KEY`, `GROQ_API_KEY` and `ANTHROPIC_API_KEY` as repository secrets (**Settings → Secrets and variables → Actions**). Then run **Actions → Speech eval → Run workflow**. The report appears on the run's summary page. Only the keys you add are used. Each run costs a few cents.

## Develop

```bash
npm install && npm run dev                     # web app at http://localhost:5173
cd android && ./gradlew :core:test :app:assembleDebug   # keyboard (needs the Android SDK)
```

| Path | What it is |
|---|---|
| `shared/prompts.json` | Every prompt: transcription instructions, cleanup rules, languages, styles. Edit wording here. |
| `src/` | Web app. `prompts.js` builds prompts, `transcribe.js` and `gemini.js` do speech to text, `polish.js` does cleanup. |
| `android/core/` | The keyboard's logic in plain Kotlin: prompts, WAV, Gemini/OpenAI/Groq, Claude. Its tests check its prompts match the web app's exactly. |
| `android/app/` | The Android keyboard (`MurmurKeyboard.kt`) and setup screen (`SetupActivity.kt`). |
| `eval/` | The speech test. |
| `.github/workflows/` | `android.yml` builds and publishes the APK on every change; `speech-eval.yml` runs the speech test. |

**Signing key:** `android/keystore/murmur.jks` signs every build, so each new APK installs over the old one. It's in this public repository, which is fine for a personal app. To use a private key instead, add it as repository secrets and set `MURMUR_KEYSTORE`, `MURMUR_KEYSTORE_PASSWORD`, `MURMUR_KEY_ALIAS` and `MURMUR_KEY_PASSWORD` in the workflow. Switching keys means uninstalling once.

## Ideas for later

- Make the mic button on your usual keyboard open Murmur, by registering as Android's voice-input service.
- Voice commands on selected text: "make this shorter", "translate to English".
- Per-app styles, such as Email in Gmail and Message in WhatsApp.
- iPhone keyboard: needs a Mac and Xcode. iOS keyboards can't use the microphone, so it hands off to the app like Wispr Flow does.
