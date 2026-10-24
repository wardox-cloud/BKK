"""How well do Murmur's transcription options handle Pakistani-accented English
and mixed Urdu/English speech?

Uses the manually corrected UrduSpeech benchmark (real Pakistani speakers from
podcasts, interviews, vlogs and more):
  - US-benchmark-EngPk: Pakistani-accented English
  - US-benchmark-CS:    Urdu-English code-switching

Always runs Whisper large-v3-turbo locally (the open model Groq serves), with
and without the Roman Urdu primer Murmur sends. Runs Gemini, OpenAI, Groq and
the Claude cleanup step when their API keys are set as environment variables
(repository secrets in GitHub Actions). Writes a Markdown report.
"""

import argparse
import base64
import io
import json
import os
import random
import re
import subprocess
import time
import unicodedata
import wave
from collections import defaultdict
from pathlib import Path

import imageio_ffmpeg
import jiwer
import numpy as np
import requests
from faster_whisper import WhisperModel
from huggingface_hub import HfApi, hf_hub_download

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "eval-results"
REPO = "ASLP-lab/UrduSpeech"
SOURCES = {
    "engpk": ("Pakistani-accented English", "benchmark/US-benchmark-EngPk/short/"),
    "cs": ("Mixed Urdu and English", "benchmark/US-benchmark-CS/short/"),
}
# Conversation-like categories are closest to dictation.
PREFERRED = ("PODCAST", "INTERVIEWS", "VLOG", "YOUTUBEREVIEWS", "ROADSIDE", "COMEDYSHOW", "DRAMA")
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.6-flash")
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "claude-opus-5-5")


# ---------- Prompts: the same strings as src/prompts.js ----------

PROMPTS = json.loads((ROOT / "shared" / "prompts.json").read_text(encoding="utf-8"))


def _lines(value):
    return "\n".join(value) if isinstance(value, list) else value


def _fill(text, values):
    return re.sub(r"\{(\w+)\}", lambda m: values.get(m.group(1), ""), text)


def transcription_instruction(language, vocabulary=()):
    terms = ", ".join(vocabulary)
    text = _fill(_lines(PROMPTS["transcribe"]["instruction"]), {
        "speaker": _lines(PROMPTS["speaker"]),
        "language": PROMPTS["languages"][language]["transcribe"],
        "vocabulary": _fill(PROMPTS["transcribe"]["vocabulary"], {"terms": terms}) if terms else "",
    })
    return "\n".join(line for line in text.split("\n") if line.strip())


def transcription_primer(language, vocabulary=()):
    terms = ", ".join(vocabulary)
    parts = [PROMPTS["languages"][language]["primer"]]
    if terms:
        parts.append(_fill(PROMPTS["transcribe"]["primerVocabulary"], {"terms": terms}))
    return " ".join(parts)


def cleanup_prompt(language, style, vocabulary=(), from_audio=False):
    cleanup = PROMPTS["cleanup"]
    terms = ", ".join(vocabulary)
    parts = [_lines(cleanup["base"]), _lines(cleanup["accent"]),
             PROMPTS["languages"][language]["cleanup"], PROMPTS["styles"][style].get("cleanup", "")]
    if terms:
        parts.append(_fill(cleanup["vocabulary"], {"terms": terms}))
    if from_audio:
        parts.append(_fill(cleanup["oneStep"], {"speaker": _lines(PROMPTS["speaker"])}))
    parts.append(cleanup["finish"])
    return "\n\n".join(parts)


def check_prompt_parity():
    """Fail fast if this port drifts from the web app (fixtures come from src/prompts.js)."""
    fixtures = json.loads((ROOT / "android/core/src/test/resources/prompt-fixtures.json").read_text(encoding="utf-8"))
    for case in fixtures["cases"]:
        kind, language, vocabulary = case["kind"], case["language"], case["vocabulary"]
        actual = (transcription_instruction(language, vocabulary) if kind == "instruction"
                  else transcription_primer(language, vocabulary) if kind == "primer"
                  else cleanup_prompt(language, case["style"], vocabulary, case["fromAudio"]))
        assert actual == case["expected"], f"prompt drift: {case}"


# ---------- Data ----------

def pick_clips(api, prefix, count, seed=7):
    """Reads the benchmark's transcription files and picks conversational clips."""
    files = [f for f in api.list_repo_files(REPO, repo_type="dataset") if f.startswith(prefix)]
    jsonls = [f for f in files if f.endswith(".jsonl") and "paralinguistic" not in f]
    audio_by_name = {f.rsplit("/", 1)[1].lower(): f for f in files if f.lower().endswith(".wav")}
    print(f"{prefix}: {len(jsonls)} transcription files, {len(audio_by_name)} audio files")

    rows = []
    for path in jsonls:
        local = hf_hub_download(REPO, path, repo_type="dataset")
        for line in open(local, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            audio = audio_by_name.get(str(row.get("Audio_Clip", "")).lower())
            text = row.get("Transcription") or row.get("transcription") or row.get("text")
            if audio and text and 2.5 <= float(row.get("Duration_seconds", 5)) <= 10:
                rows.append({"audio": audio, "reference": text.strip(), "category": row.get("Audio_category", "")})
    if not rows and jsonls:
        print("  no usable rows; first line of first file:", open(hf_hub_download(REPO, jsonls[0], repo_type="dataset"), encoding="utf-8").readline()[:500])

    # Round-robin across categories so one show doesn't dominate.
    random.Random(seed).shuffle(rows)
    by_category = defaultdict(list)
    for row in rows:
        by_category[row["category"].upper()].append(row)
    order = sorted(by_category, key=lambda c: (c not in PREFERRED, PREFERRED.index(c) if c in PREFERRED else 0, c))
    picked = []
    while len(picked) < count and any(by_category.values()):
        for category in order:
            if by_category[category] and len(picked) < count:
                picked.append(by_category[category].pop())
    for clip in picked:
        clip["path"] = hf_hub_download(REPO, clip["audio"], repo_type="dataset")
        clip["pcm"] = decode(clip["path"])
        clip["seconds"] = round(len(clip["pcm"]) / 16000, 1)
    return picked


def decode(path):
    """Any audio file to 16 kHz mono float32, via a bundled ffmpeg."""
    raw = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-v", "error", "-i", path, "-ac", "1", "-ar", "16000", "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, "<i2").astype(np.float32) / 32768


def wav_bytes(pcm):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(16000)
        out.writeframes((np.clip(pcm, -1, 1) * 32767).astype("<i2").tobytes())
    return buffer.getvalue()


# ---------- Systems under test ----------

def whisper_local(model, primer):
    def run(clip):
        segments, info = model.transcribe(clip["pcm"], beam_size=5, initial_prompt=primer, condition_on_previous_text=False)
        return " ".join(s.text.strip() for s in segments).strip(), {"detected": info.language}
    return run


def gemini(language):
    key = os.environ["GEMINI_API_KEY"]

    def run(clip):
        body = {
            "systemInstruction": {"parts": [{"text": transcription_instruction(language)}]},
            "contents": [{"role": "user", "parts": [
                {"inlineData": {"mimeType": "audio/wav", "data": base64.b64encode(wav_bytes(clip["pcm"])).decode()}},
                {"text": "Transcribe this recording."},
            ]}],
        }
        if GEMINI_MODEL.startswith("gemini-3"):
            body["generationConfig"] = {"thinkingConfig": {"thinkingLevel": "low"}}
        reply = requests.post(f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent",
                              headers={"x-goog-api-key": key}, json=body, timeout=120)
        reply.raise_for_status()
        parts = reply.json()["candidates"][0]["content"].get("parts", [])
        return "".join(p.get("text", "") for p in parts if not p.get("thought")).strip(), {}
    return run


def openai_compatible(base_url, key_name, model, language):
    key = os.environ[key_name]

    def run(clip):
        reply = requests.post(f"{base_url}/audio/transcriptions", headers={"Authorization": f"Bearer {key}"},
                              files={"file": ("dictation.wav", wav_bytes(clip["pcm"]), "audio/wav")},
                              data={"model": model, "response_format": "json", "prompt": transcription_primer(language)},
                              timeout=120)
        reply.raise_for_status()
        return reply.json().get("text", "").strip(), {}
    return run


def claude_cleanup(raw):
    import anthropic

    client = anthropic.Anthropic()
    message = client.beta.messages.create(
        model=CLAUDE_MODEL,
        max_tokens=16000,
        output_config={"effort": "low"},
        system=cleanup_prompt("roman", "clean"),
        messages=[{"role": "user", "content": f"<transcript>\n{raw}\n</transcript>"}],
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
    )
    if message.stop_reason == "refusal":
        return "(declined)"
    return "".join(b.text for b in message.content if b.type == "text").strip()


# ---------- Scoring ----------

def normalize(text):
    text = unicodedata.normalize("NFKC", text).lower()
    text = re.sub(r"[ً-ٰٟ]", "", text)  # Urdu diacritics
    text = re.sub(r"[^\w\s']|_", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def wer(reference, hypothesis):
    reference, hypothesis = normalize(reference), normalize(hypothesis)
    return jiwer.wer(reference, hypothesis) if reference else float("nan")


def latin_share(text):
    letters = [c for c in text if c.isalpha()]
    return sum("LATIN" in unicodedata.name(c, "") for c in letters) / len(letters) if letters else float("nan")


def english_kept(reference, hypothesis):
    """Share of the English words in the reference that survive, in English, in the output."""
    english = [w for w in normalize(reference).split() if re.fullmatch(r"[a-z']{3,}", w)]
    if not english:
        return float("nan")
    output = set(normalize(hypothesis).split())
    return sum(w in output for w in english) / len(english)


def mean(values):
    values = [v for v in values if v == v]
    return sum(values) / len(values) if values else float("nan")


# ---------- Main ----------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--clips", type=int, default=8)
    args = parser.parse_args()

    check_prompt_parity()
    OUT.mkdir(exist_ok=True)
    api = HfApi()

    print("Loading Whisper large-v3-turbo (CPU)...")
    model = WhisperModel("large-v3-turbo", device="cpu", compute_type="int8")

    systems = {
        "Whisper turbo (plain, what a basic Groq setup gives)": whisper_local(model, None),
        "Whisper turbo + Murmur's Roman Urdu primer": whisper_local(model, transcription_primer("roman")),
    }
    if os.environ.get("GROQ_API_KEY"):
        systems["Groq (Murmur, Roman Urdu)"] = openai_compatible("https://api.groq.com/openai/v1", "GROQ_API_KEY", "whisper-large-v3-turbo", "roman")
    if os.environ.get("OPENAI_API_KEY"):
        systems["OpenAI gpt-4o-transcribe (Murmur, Roman Urdu)"] = openai_compatible("https://api.openai.com/v1", "OPENAI_API_KEY", "gpt-4o-transcribe", "roman")
    if os.environ.get("GEMINI_API_KEY"):
        systems["Gemini (Murmur, Urdu script)"] = gemini("urdu")
        systems["Gemini (Murmur, Roman Urdu)"] = gemini("roman")
    cleanup_after = "Gemini (Murmur, Roman Urdu)" if "Gemini (Murmur, Roman Urdu)" in systems else "Whisper turbo + Murmur's Roman Urdu primer"
    use_claude = bool(os.environ.get("ANTHROPIC_API_KEY"))
    skipped = [name for name, key in [("Gemini", "GEMINI_API_KEY"), ("OpenAI", "OPENAI_API_KEY"), ("Groq", "GROQ_API_KEY"), ("Claude cleanup", "ANTHROPIC_API_KEY")] if not os.environ.get(key)]

    report = ["# Murmur speech test", "",
              f"Clips: {args.clips} per set, 2.5-10 s each, from the manually corrected [UrduSpeech](https://huggingface.co/datasets/{REPO}) benchmark (real Pakistani speakers).", ""]
    if skipped:
        report += [f"Not run (no API key set): {', '.join(skipped)}.", ""]
    results = {}

    for source, (title, prefix) in SOURCES.items():
        clips = pick_clips(api, prefix, args.clips)
        rows = []
        for clip in clips:
            row = {"category": clip["category"], "seconds": clip["seconds"], "reference": clip["reference"], "outputs": {}}
            for name, run in systems.items():
                started = time.time()
                try:
                    text, extra = run(clip)
                except Exception as error:  # noqa: BLE001 - report and keep going
                    text, extra = f"(error: {error})", {}
                row["outputs"][name] = {"text": text, "seconds": round(time.time() - started, 1), **extra}
            if use_claude:
                raw = row["outputs"][cleanup_after]["text"]
                started = time.time()
                try:
                    cleaned = claude_cleanup(raw)
                except Exception as error:  # noqa: BLE001
                    cleaned = f"(error: {error})"
                row["outputs"][f"Claude cleanup of {cleanup_after.split(' (')[0]} (Roman Urdu)"] = {"text": cleaned, "seconds": round(time.time() - started, 1)}
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False)[:1500])
        results[source] = rows

        names = list(rows[0]["outputs"]) if rows else []
        report += [f"## {title}", ""]
        if source == "engpk":
            report += ["Word error rate against the human transcript (lower is better).", "",
                       "| System | Word error rate | Avg time |", "|---|---|---|"]
            for name in names:
                report.append(f"| {name} | {mean(wer(r['reference'], r['outputs'][name]['text']) for r in rows):.0%} | "
                              f"{mean(r['outputs'][name]['seconds'] for r in rows):.1f}s |")
        else:
            report += ["The reference is Urdu script with English words in English. *English kept* = share of the speaker's English words that come out as English (Whisper tends to translate or rewrite them). "
                       "*Roman letters* = share of output letters in the Latin alphabet (should be near 100% for Roman Urdu). *Word error rate* only makes sense for output in Urdu script.", "",
                       "| System | English kept | Roman letters | Word error rate (Urdu-script output) | Avg time |", "|---|---|---|---|---|"]
            for name in names:
                texts = [(r["reference"], r["outputs"][name]["text"]) for r in rows]
                latin = mean(latin_share(h) for _, h in texts)
                error_rate = f"{mean(wer(ref, h) for ref, h in texts):.0%}" if latin < 0.5 else "n/a"
                report.append(f"| {name} | {mean(english_kept(ref, h) for ref, h in texts):.0%} | {latin:.0%} | {error_rate} | "
                              f"{mean(r['outputs'][name]['seconds'] for r in rows):.1f}s |")
            detected = defaultdict(int)
            for r in rows:
                detected[r["outputs"]["Whisper turbo (plain, what a basic Groq setup gives)"].get("detected")] += 1
            report += ["", f"Languages plain Whisper thought it heard: {dict(detected)}"]

        report += ["", "<details><summary>Every clip</summary>", ""]
        for i, r in enumerate(rows, 1):
            report += [f"**{i}. {r['category'].title()}, {r['seconds']}s**", "", f"- Human transcript: {r['reference']}"]
            report += [f"- {name}: {o['text']}" for name, o in r["outputs"].items()]
            report.append("")
        report += ["</details>", ""]

    (OUT / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    text = "\n".join(report)
    (OUT / "report.md").write_text(text, encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        Path(summary).write_text(text, encoding="utf-8")
    print("\n" + text)


if __name__ == "__main__":
    main()
