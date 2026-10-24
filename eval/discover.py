"""Prints how candidate speech datasets on Hugging Face are laid out, so the
speech test knows where to find accented and mixed-language recordings."""

import pyarrow.parquet as pq
from huggingface_hub import HfApi, hf_hub_download

api = HfApi()

REPOS = {
    "ASLP-lab/UrduSpeech": None,
    "dianavdavidson/MUCS-Hinglish": None,
    "dianavdavidson/indic-voices-hinglish-nospeakeroverlap-spon3.3-acronyms-fixed2": None,
    "naradatum/hinglish-code-switched-conversations-v1": None,
    "agarwalayushi/hinglish": None,
    "google/fleurs": r"parquet-data/ur_pk/test",
}
SEARCHES = ["roman urdu", "urdu asr", "urdu speech"]


def card(repo):
    try:
        path = hf_hub_download(repo, "README.md", repo_type="dataset")
        text = open(path, encoding="utf-8").read()
        print("\n".join(text.splitlines()[:120]))
    except Exception as error:  # noqa: BLE001 - discovery should keep going
        print(f"  (no card: {error})")


def peek_parquet(repo, path):
    try:
        local = hf_hub_download(repo, path, repo_type="dataset")
        table = pq.ParquetFile(local)
        print(f"  schema of {path}: {table.schema_arrow}")
        batch = next(table.iter_batches(batch_size=3))
        frame = batch.to_pandas()
        for column in frame.columns:
            values = frame[column].tolist()
            shown = [str(v)[:120] if not isinstance(v, (bytes, dict)) else f"<{type(v).__name__} keys={list(v)[:4] if isinstance(v, dict) else len(v)}>" for v in values]
            print(f"    {column}: {shown}")
    except Exception as error:  # noqa: BLE001
        print(f"  (couldn't read {path}: {error})")


def main():
    import re

    for repo, pattern in REPOS.items():
        print(f"\n===== {repo} =====")
        try:
            files = api.list_repo_files(repo, repo_type="dataset")
        except Exception as error:  # noqa: BLE001
            print(f"  (can't list: {error})")
            continue
        print(f"  {len(files)} files")
        shown = [f for f in files if not pattern or re.search(pattern, f, re.I)]
        folders = sorted({f.rsplit("/", 1)[0] if "/" in f else "." for f in shown})
        print(f"  folders: {folders[:60]}")
        for f in [f for f in shown if not f.endswith((".wav", ".flac", ".mp3"))][:60]:
            print("   ", f)
        card(repo)
        parquets = [f for f in shown if f.endswith(".parquet")]
        if parquets:
            peek_parquet(repo, sorted(parquets, key=len)[0])

    print("\n===== search =====")
    seen = set()
    for query in SEARCHES:
        for info in api.list_datasets(search=query, sort="downloads", limit=12):
            if info.id in seen:
                continue
            seen.add(info.id)
            print(f"  [{query}] {info.id}  downloads={info.downloads} tags={[t for t in (info.tags or []) if t.startswith(('task_categories', 'language:', 'modality'))][:8]}")


if __name__ == "__main__":
    main()
