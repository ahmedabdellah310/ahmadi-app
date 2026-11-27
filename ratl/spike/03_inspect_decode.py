"""Print decoded transliterations from the parity run, for correctness eyeballing."""
import json
import pathlib

d = json.loads(
    (pathlib.Path(__file__).parent / "results" / "02_parity.json").read_text(
        encoding="utf-8"
    )
)
for c in d["clips"]:
    print(f"--- {c['clip']}  {c['reciter'][:26]:<26} {c['duration_s']}s")
    print(f"  fp32: {c['text_fp32'][:200]}")
    print(f"  int8: {c['text_int8'][:200]}")
    print()
