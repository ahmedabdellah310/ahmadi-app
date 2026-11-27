"""Precompute + cache log-mel features for Experiment 1. Device-independent."""
import importlib.util
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("t", HERE / "10_train_exp1.py")
t = importlib.util.module_from_spec(spec)
sys.modules["t"] = t
spec.loader.exec_module(t)

from transformers import WhisperFeatureExtractor  # noqa: E402

fe = WhisperFeatureExtractor.from_pretrained(t.BASE)
fe.chunk_length = int(t.WINDOW_S)
fe.n_samples = int(t.WINDOW_S * t.SR)
fe.nb_max_frames = t.MEL_FRAMES

data = json.loads((t.CORPUS / "exp1-train.json").read_text(encoding="utf-8"))
clips = data["clips"]
print(f"[data] {len(clips)} clips  {data['total_hours']} h")

itos, stoi = t.build_vocab(clips)
print(f"[vocab] {len(itos)} tokens: {''.join(itos[1:])}")

feats, meta = t.precompute(clips, fe)
print(f"[feats] shape={feats.shape} dtype={feats.dtype}")
print(f"[feats] valid-frame len p50={sorted(meta['lens'])[len(meta['lens'])//2]}")
