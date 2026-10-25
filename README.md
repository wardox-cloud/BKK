# Murmur

A personal, free, Wispr Flow-style voice tool for your phone. Tap the floating mic in any app and talk the way you normally do: English, Urdu, or both in the same sentence. Murmur writes it down cleanly (no "um"s or false starts, real punctuation, your "no wait, I mean..." corrections applied) and types it into the box you're in.

It costs nothing to run: one free Gemini key from Google does the listening and the cleanup. There's no Murmur server, and your key stays on your phone.

It comes in three forms:

- **Floating mic** (Android, `android/`): a small mic button over every app while you type. Tap, talk, tap: the text goes straight into the text box.
- **Murmur keyboard** (Android): the same thing as a keyboard you switch to, for people who'd rather not turn on the floating mic.
- **Web app** (`src/`): works on iPhone and Android from the home screen. It copies the text, and you paste it.

All three use the same prompts (`shared/prompts.json`).

## How it works

```
 mic ──► Gemini (free) listens and cleans up in one step ──► typed into the text box
         removes filler, applies corrections,
         writes Urdu your way

         Optional: OpenAI or Groq for speech to text, Claude for cleanup (paid APIs)
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

*Clean* (your words, tidied up), *Message* (casual text), *Email* (paragraphs), *Notes* (bullet points), *Raw* (no cleanup). Pick one in the Murmur app (or on the keyboard, where tapping a style right after a dictation rewrites that dictation in place).

## Android: floating mic and keyboard

### Install

1. On your phone, open **[the latest release](https://github.com/wardox-cloud/BKK/releases/latest)** and tap **murmur-keyboard.apk**.
2. Android asks whether your browser may install apps. Allow it for this one install. The app isn't in the Play Store, so Android calls it an "unknown app".
3. Open **Murmur** from your app drawer and follow the steps on screen:
   1. **Allow the microphone.**
   2. **Add your free Gemini key** (the button opens [aistudio.google.com/apikey](https://aistudio.google.com/apikey)) and tap **Save**.
   3. **Turn on the floating mic:** tap **Open Accessibility**, choose **Murmur mic bubble** and turn it on. If Android says it's a *restricted setting* (Android 13 and newer do this for apps not installed from the Play Store), tap **App info** in Murmur, then **⋮** in the top corner, then **Allow restricted settings**, and try again.
4. Try it in the **Try it here** box.

New builds install over the old one and keep your settings.

### Use it

- When you're typing in any app, the mic bubble appears at the edge of the screen. Drag it anywhere; it remembers where you put it. (In settings you can keep it on screen all the time.)
- **Tap** the bubble, talk, **tap** again. A moment later your words are typed at the cursor, with a space added if needed. If no text box is selected, they're copied so you can paste them.
- On some phones Android won't let a bubble use the microphone. Murmur notices the first time and opens a small *Listening…* panel at the bottom instead, and keeps using it from then on. Tap its button (or the bubble) when you're done.
- If something fails, a message says why.

### Transcribe a recording

Share any recording to Murmur, such as a Voice Recorder file or a WhatsApp voice note: tap **Share** and choose **Transcribe with Murmur**. It writes it down with your usual settings, shows it, and copies it.

Recordings of any length work. Murmur splits long ones into parts of 4 to 6 minutes, cutting each in a pause, and transcribes them one after another. You can leave the screen: a notification shows the progress and tells you when it's done. Each part is one Gemini request, so an hour-long meeting uses about 12 of your free daily requests. If they run out partway, Murmur keeps what's done, and **Continue** picks up from there later.

### The keyboard (optional)

If you'd rather not turn on the floating mic, Murmur also works as a keyboard. Turn it on from the bottom of the Murmur app, then switch to it with the keyboard button at the bottom of your screen or the globe key. Tap the mic (or hold it while you talk). Tapping a style right after a dictation rewrites it in place. **Undo** removes the last dictation.

### Why it needs Accessibility

Android only lets an app draw a button over other apps and type into their text boxes through Accessibility. Murmur uses it only to see which text box is selected (to show the bubble and put your words there). It doesn't read the screen otherwise, and it doesn't send anything except your recording, which goes to the speech service you chose.

## Keys

**You need one key, and it's free:** a Gemini key from [aistudio.google.com/apikey](https://aistudio.google.com/apikey). Any Google account works; you don't need a paid plan. Murmur uses Gemini for both steps, listening and cleanup, in a single request.

| Optional extras | Where | Notes |
|---|---|---|
| Claude for cleanup | [console.anthropic.com](https://console.anthropic.com/settings/keys) | Paid per use. A Claude Pro or Max subscription doesn't cover it: subscriptions are for Anthropic's own apps, and an app you build uses the separately billed API. |
| OpenAI for speech to text | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) | Paid. Fine for English, weaker on mixed Urdu-English. |
| Groq for speech to text | [console.groq.com/keys](https://console.groq.com/keys) | Free tier available. Fast, but weakest on mixed speech. |

## What it costs

**Nothing, with the default setup.** Gemini's free tier covers personal dictation, with two catches:

- **Daily limits.** Google gives each Gemini model its own allowance of free requests per day and per minute, and changes the numbers from time to time (reports range from about 20 a day to 1,500 for the newest Flash models, and about 500 for Flash-Lite). One dictation is one request; a long recording is one request per 4 to 6 minutes. When a model's free requests run out, Murmur moves to the next Flash model your key can use (newer before older, then Flash-Lite), and remembers, so later dictations go straight there. The Murmur app's setup screen shows which models are used up and when they come back: midnight in California, which is noon or 1 pm in Pakistan. To see your exact limits, open [Google AI Studio](https://aistudio.google.com) with the account your key belongs to and look at its usage and rate limits.
- **Privacy.** On the free tier, Google may use what you send to improve its products. If you'd rather it didn't, add billing to your Google AI Studio project; then you pay per use, roughly 0.2¢ per 30-second dictation (about $1.50 a month at 30 dictations a day).

Claude cleanup, if you add it, is extra: about $0.20 a month with Claude Haiku 5.5, or about $7.50 with Claude Opus 5.5, at 30 dictations a day.

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
- Dictations with the mic stop automatically at 6 minutes. For anything longer, record it (in Voice Recorder, say) and share the recording to Murmur.

## Speech test

Results so far, with Whisper only because no API keys are set yet (20 real clips per set, October 2026):

| | Pakistani-accented English | Mixed Urdu + English |
|---|---|---|
| Whisper, no hint (a basic Groq setup) | 18% word errors | kept 40% of English words; often translated the Urdu into English, sometimes wrote Hindi script |
| Whisper + Murmur's Roman Urdu hint | 18% word errors | kept 73% of English words, mostly Roman letters, but still translates some clips |

Whisper is fine for accented English; its slips ("newlywed **chai** bride" for "shy bride") are the kind the cleanup step fixes. For mixed Urdu and English it isn't good enough, which is why Gemini is the default. An earlier hint with an English example sentence sometimes got copied into transcripts (2 of 20 clips). The current hint avoids full English sentences, and that dropped to 0 of 20.

To compare Murmur with your phone's own transcription (Samsung Voice Recorder, Gboard, Live Transcribe) on your own voice, follow [eval/phone_benchmark.md](eval/phone_benchmark.md): read a short script, run the same recording through each, and score the results with `eval/phone_score.py`.

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
| `android/core/` | The keyboard's logic in plain Kotlin: prompts, audio (WAV, resampling, splitting long recordings at pauses), Gemini/OpenAI/Groq, Claude. Its tests check its prompts match the web app's exactly. |
| `android/app/` | The floating mic (`BubbleService.kt`, with `MicPanelActivity.kt` as its fallback), the keyboard (`MurmurKeyboard.kt`), the setup screen (`SetupActivity.kt`), and shared recordings (`TranscribeActivity.kt`, run in the background by `TranscribeService.kt`). |
| `eval/` | The speech test, and the phone benchmark (`phone_benchmark.md`, `phone_score.py`). |
| `.github/workflows/` | `android.yml` builds and publishes the APK on every change; `speech-eval.yml` runs the speech test. |

**Signing key:** `android/keystore/murmur.jks` signs every build, so each new APK installs over the old one. It's in this public repository, which is fine for a personal app. To use a private key instead, add it as repository secrets and set `MURMUR_KEYSTORE`, `MURMUR_KEYSTORE_PASSWORD`, `MURMUR_KEY_ALIAS` and `MURMUR_KEY_PASSWORD` in the workflow. Switching keys means uninstalling once.

## Ideas for later

- Fully offline mode: run Whisper on the phone itself for English-only dictation without internet (mixed Urdu-English needs Gemini; see the speech test).
- Voice commands on selected text: "make this shorter", "translate to English".
- Per-app styles, such as Email in Gmail and Message in WhatsApp.
- iPhone: needs a Mac and Xcode, and iOS keyboards can't use the microphone, so it would hand off to the app like Wispr Flow does.
