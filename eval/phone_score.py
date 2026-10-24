"""Scores phone transcripts of the benchmark script in eval/phone_benchmark.md.

Paste each system's transcript under its own heading in one text file:

    ## Samsung
    hey ali can you send me ...
    ## Murmur
    Hey Ali, can you send me ...

then run:  python eval/phone_score.py results.txt  [--details]

Scoring is lenient on things that aren't mistakes: punctuation, capitals,
"4:30" vs "four thirty", contractions, common Roman Urdu spellings (nahi,
nahin, nai), and fillers or a self-correction that a cleanup step removes.
"""

import argparse
import re
import sys
import unicodedata

# The script, one sentence per line. [Bracketed] words may be left out without
# penalty: fillers, and the part of sentence 1 that the speaker corrects.
SCRIPT = [
    ("en", "Hey Ali, can you send me the updated report by [Friday? Actually, make it] Thursday."),
    ("mix", "Yaar mujhe kal client ke saath meeting karni hai, so please remind me at four thirty."),
    ("en", "The candidate has seven years of experience in mortgage servicing and is based in Lahore."),
    ("mix", "Acha suno, woh proposal abhi tak finalize nahi hua, I'll follow up with Sarah tomorrow."),
    ("en", "Please book a call with Matt Doyle for next Tuesday at 11 am Eastern."),
    ("mix", "Mujhe lagta hai ke humein pricing pe dobara kaam karna chahiye, the numbers don't add up."),
    ("en", "Send the invoice to accounts at axiom talent dot com, and copy me on it."),
    ("mix", "Theek hai, main shaam ko call karta hoon, abhi main traffic mein phansa hua hoon."),
    ("en", "[Um,] [so] the main thing is, [uh,] we need three more candidates before the end of the month."),
    ("mix", "Bhai, ye wala resume bohat strong hai, let's move him to the interview stage."),
]

URDU = set(
    "yaar mujhe kal ke saath karni hai acha suno woh abhi tak nahi hua lagta humein pe dobara kaam "
    "karna chahiye theek main shaam ko karta hoon phansa bhai ye wala bohat".split()
)

# Common Roman Urdu spellings, mapped to the one used in the script.
SPELLINGS = {
    "yaar": ["yar"],
    "mujhe": ["mujhay", "mujhey", "mjhe", "mujhy"],
    "ke": ["kay", "k", "ky", "keh", "kai"],
    "saath": ["sath", "saat", "saath"],
    "hai": ["hay", "he", "hy", "hae", "hain"],
    "acha": ["achha", "accha", "achcha", "achchha", "achaa", "acchha"],
    "woh": ["wo", "voh", "vo", "wh"],
    "abhi": ["abi", "abhee"],
    "tak": ["tk"],
    "nahi": ["nahin", "nai", "nhi", "nahee", "nahee"],
    "hua": ["howa", "huwa", "hoa", "huaa"],
    "humein": ["hamein", "humen", "hamen", "humain", "hamain", "hume", "humme", "hame"],
    "pe": ["par", "pay", "pr", "pey", "py"],
    "dobara": ["dubara", "dobarah", "dobaara", "dubaara"],
    "kaam": ["kam"],
    "karna": ["krna"],
    "karni": ["krni"],
    "karta": ["krta"],
    "lagta": ["lgta"],
    "chahiye": ["chahie", "chahye", "chahiay", "chaiye", "chahiyay", "chahiyeh", "chahiyey", "chahiya"],
    "theek": ["thik", "teek", "theik", "thek", "thhik", "theeq"],
    "main": ["mein", "mai", "men", "mien", "mei"],
    "shaam": ["sham"],
    "hoon": ["hun", "hu", "hoo", "hon", "houn", "houn"],
    "phansa": ["phasa", "fansa", "fasa", "phansaa", "phnsa", "phasaa"],
    "bhai": ["bhae", "bhaai", "bhayi", "bhaee"],
    "ye": ["yeh", "yah"],
    "wala": ["waala", "vala", "waale", "wale"],
    "bohat": ["bohot", "bahut", "bht", "bohut", "buhat", "bahot", "boht", "bohat"],
    "suno": ["sunno", "suno"],
    "sarah": ["sara"],
    "matt": ["mat"],
    "eastern": ["et", "est", "edt"],
}
CANONICAL = {variant: word for word, variants in SPELLINGS.items() for variant in variants}

NUMBERS = {
    "zero": "0", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6",
    "seven": "7", "eight": "8", "nine": "9", "ten": "10", "eleven": "11", "twelve": "12",
    "thirty": "30", "fifteen": "15", "forty": "40", "fortyfive": "45",
}
CONTRACTIONS = {"i'll": "i will", "don't": "do not", "let's": "let us", "it's": "it is", "i'm": "i am"}
FILLERS = {"um", "umm", "uh", "uhh", "er", "erm", "ah", "hmm", "mm"}
COMPOUNDS = {"axiom talent": "axiomtalent"}

URDU_SCRIPT = re.compile(r"[؀-ۿ]")
DEVANAGARI = re.compile(r"[ऀ-ॿ]")


def words(text):
    """Lowercase words with spelling, numbers and contractions made comparable."""
    text = text.lower().replace("’", "'")
    text = re.sub(r"(\w)@(\w)", r"\1 at \2", text)
    text = re.sub(r"(\w)\.(com|org|net|pk)\b", r"\1 dot \2", text)
    text = re.sub(r"(\d):00\b", r"\1", text)
    text = re.sub(r"(\d)[:.](\d)", r"\1 \2", text)
    text = re.sub(r"\b(a|p)\.m\.?", r"\1m", text)
    for short, full in CONTRACTIONS.items():
        text = re.sub(rf"\b{re.escape(short)}\b", full, text)
    for split, joined in COMPOUNDS.items():
        text = text.replace(split, joined)
    text = re.sub(r"(\d)([a-z])", r"\1 \2", text)
    # Splitting on punctuation, not on \W, keeps Urdu and Hindi words whole.
    text = "".join(" " if unicodedata.category(c)[0] in "PS" else c for c in text.replace("'", ""))
    tokens = text.split()
    return [CANONICAL.get(NUMBERS.get(t, t), NUMBERS.get(t, t)) for t in tokens]


def reference():
    """Script words as (word, optional, kind), where kind is 'en' or 'ur'."""
    out = []
    for kind, sentence in SCRIPT:
        for part in re.split(r"(\[[^\]]*\])", sentence):
            optional = part.startswith("[")
            for word in words(part.strip("[]")):
                lang = "ur" if kind == "mix" and word in URDU else "en"
                out.append((word, optional or word in FILLERS, lang))
    return out


def clean_transcript(text):
    """Drops speaker labels and timestamp lines that transcript apps add."""
    lines = []
    for line in text.splitlines():
        if re.fullmatch(r"\s*((speaker|person)\s*\d+)?\s*[\[(]?\d{1,2}:\d{2}(:\d{2})?[\])]?\s*", line, re.I):
            continue
        if re.fullmatch(r"\s*(speaker|person)\s*\d+\s*:?\s*", line, re.I):
            continue
        lines.append(re.sub(r"^\s*(speaker|person)\s*\d+\s*:\s*", "", line, flags=re.I))
    return "\n".join(lines)


def align(ref, hyp):
    """Edit-distance alignment where leaving out an optional script word is free."""
    n, m = len(ref), len(hyp)
    cost = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        cost[i][0] = cost[i - 1][0] + (0 if ref[i - 1][1] else 1)
    for j in range(1, m + 1):
        cost[0][j] = j
    for i in range(1, n + 1):
        word, optional, _ = ref[i - 1]
        for j in range(1, m + 1):
            cost[i][j] = min(
                cost[i - 1][j - 1] + (0 if word == hyp[j - 1] else 1),
                cost[i - 1][j] + (0 if optional else 1),
                cost[i][j - 1] + 1,
            )
    ops, i, j = [], n, m
    while i or j:
        word, optional, lang = ref[i - 1] if i else (None, False, None)
        if i and j and cost[i][j] == cost[i - 1][j - 1] + (0 if word == hyp[j - 1] else 1):
            ops.append(("ok" if word == hyp[j - 1] else "sub", ref[i - 1], hyp[j - 1]))
            i, j = i - 1, j - 1
        elif i and cost[i][j] == cost[i - 1][j] + (0 if optional else 1):
            ops.append(("skip" if optional else "del", ref[i - 1], None))
            i -= 1
        else:
            ops.append(("ins", None, hyp[j - 1]))
            j -= 1
    return ops[::-1]


def score(transcript):
    text = clean_transcript(transcript)
    hyp = [w for w in words(text) if w not in FILLERS]
    ops = align(reference(), hyp)
    required = [r for r in reference() if not r[1]]
    errors = sum(op != "ok" and op != "skip" for op, _, _ in ops)
    kept = {"en": 0, "ur": 0}
    for op, ref_word, _ in ops:
        if op == "ok" and not ref_word[1]:
            kept[ref_word[2]] += 1
    total = {lang: sum(r[2] == lang for r in required) for lang in kept}
    lower = text.lower()
    plain = " ".join(words(text))
    checks = {
        "names": sum(bool(re.search(p, plain)) for p in [r"\bali\b", r"\bsarah\b", r"\bmatt doyle\b", r"\blahore\b"]),
        "numbers": sum(bool(re.search(p, plain)) for p in [r"\b4 30\b", r"\b7 years\b", r"\b11 am\b", r"\b3 more\b"]),
        "email": bool(re.search(r"accounts@axiom\s?talent\.com", lower)),
        "email_words": bool(re.search(r"accounts at axiomtalent dot com", plain)),
        "correction": "thursday" in plain and "friday" not in plain,
        "fillers": not re.search(r"\b(um+|uh+|erm)\b", lower),
        "urdu_script": bool(URDU_SCRIPT.search(text)),
        "hindi_script": bool(DEVANAGARI.search(text)),
    }
    return {
        "wer": errors / len(required),
        "en": kept["en"] / total["en"],
        "ur": kept["ur"] / total["ur"],
        "checks": checks,
        "ops": ops,
    }


def sections(paths):
    out = {}
    for path in paths:
        text = open(path, encoding="utf-8").read()
        parts = re.split(r"^##\s*(.+?)\s*$", text, flags=re.M)
        if len(parts) == 1:
            out[path] = text
        for name, body in zip(parts[1::2], parts[2::2]):
            out[name] = body
    return {name: body for name, body in out.items() if body.strip()}


def report(results):
    names = list(results)
    yes = lambda flag: "yes" if flag else "no"
    rows = [
        ("Word errors (lower is better)", lambda r: f"{r['wer']:.0%}"),
        ("English words right", lambda r: f"{r['en']:.0%}"),
        ("Urdu words right, in Roman letters", lambda r: f"{r['ur']:.0%}"),
        ("Wrote Urdu script", lambda r: yes(r["checks"]["urdu_script"])),
        ("Wrote Hindi script", lambda r: yes(r["checks"]["hindi_script"])),
        ("Names (Ali, Sarah, Matt Doyle, Lahore)", lambda r: f"{r['checks']['names']}/4"),
        ("Numbers and times", lambda r: f"{r['checks']['numbers']}/4"),
        (
            "Email address",
            lambda r: "written as an address" if r["checks"]["email"] else "spelled out" if r["checks"]["email_words"] else "wrong",
        ),
        ('Applied "actually, make it Thursday"', lambda r: yes(r["checks"]["correction"])),
        ("Removed um and uh", lambda r: yes(r["checks"]["fillers"])),
    ]
    lines = ["| | " + " | ".join(names) + " |", "|---|" + "---|" * len(names)]
    for label, cell in rows:
        lines.append(f"| {label} | " + " | ".join(cell(results[n]) for n in names) + " |")
    return "\n".join(lines)


def details(name, result):
    shown = []
    for op, ref_word, got in result["ops"]:
        if op == "ok":
            shown.append(ref_word[0])
        elif op == "sub":
            shown.append(f"[{ref_word[0]} → {got}]")
        elif op == "del":
            shown.append(f"[{ref_word[0]} → missing]")
        elif op == "ins":
            shown.append(f"[+{got}]")
    return f"### {name}\n" + " ".join(shown)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+")
    parser.add_argument("--details", action="store_true", help="show each system's mistakes word by word")
    args = parser.parse_args()
    found = sections(args.files)
    if not found:
        sys.exit("No transcripts found. Put each one under a heading like '## Samsung'.")
    results = {name: score(body) for name, body in found.items()}
    print(report(results))
    if args.details:
        for name, result in results.items():
            print()
            print(details(name, result))


if __name__ == "__main__":
    main()
