"""Inspect quran-align ground-truth schema and index base."""
import json
import pathlib

d = pathlib.Path(__file__).parent / ".cache" / "quran-align"
f = d / "Husary_64kbps.json"
data = json.loads(f.read_text(encoding="utf-8"))
print(f"type={type(data)}  len={len(data)}")
if isinstance(data, list):
    for row in data[:3]:
        print(json.dumps(row, ensure_ascii=False)[:400])
    # find 1:1 and 2:255
    for row in data:
        if row.get("surah") == 1 and row.get("ayah") == 1:
            print("\n1:1 ->", json.dumps(row, ensure_ascii=False))
        if row.get("surah") == 112 and row.get("ayah") == 1:
            print("\n112:1 ->", json.dumps(row, ensure_ascii=False))
print("\n--- README ---")
print((d / "README").read_text(encoding="utf-8")[:1500])
