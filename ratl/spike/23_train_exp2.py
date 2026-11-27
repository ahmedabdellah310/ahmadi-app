"""
RATL Experiment 2 - auxiliary frame-level word-boundary head.

Pre-registered in RUNG3_PLAN.md section 5. Deferred twice; both deferrals are
now closed by measurement:
  - data does not help   (Exp 1b: 5x data, 56% better CTC, worse gate)
  - decode does not close it (M1/M1b: six boundary rules exhausted, 0.8395)

THE PROBLEM (M1B_RESULTS.md section 3): accuracy degrades monotonically with
word duration for EVERY reciter -- 0.9495 under 500 ms, 0.4906 over 4000 ms.
CTC is a transcription objective; it never receives temporal supervision, so
boundary error scales with word length. Mujawwad fails because its words are
longest (p50 1780 ms vs 1060 ms), not because it is stylistically distinct.

THE FIX: supervise boundaries DIRECTLY. A second head predicts, per 20 ms frame,
P(a word starts here) and P(a word ends here), trained on Gaussian-smoothed
targets built from the same quran-align ground truth already on disk.

Encoder and CTC head are unchanged and initialised identically, so any gain is
attributable to the added supervision.

  loss = CTC + LAMBDA * BCE(boundary)

NO new data. NO downloads. Features reused from the cached exp1b tensor.
RATL-Bench-v0 and the 0.85 threshold are untouched by this script.

THROWAWAY SPIKE CODE. Not RATL Core.
"""

import importlib.util
import json
import pathlib
import sys
import time

import numpy as np
import torch
import torch.nn as nn

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("tr", HERE / "10_train_exp1.py")
tr = importlib.util.module_from_spec(spec)
sys.modules["tr"] = tr
spec.loader.exec_module(tr)

CORPUS = HERE / "corpus" / "exp1b-train.json"
FEATS = HERE / ".cache" / "feats"
CKPT = HERE / "models" / "exp2"
RESULTS = HERE / "results"
for p in (CKPT, RESULTS):
    p.mkdir(parents=True, exist_ok=True)

TAG = "exp1b"                 # reuse the cached feature tensor
EPOCHS = 12
LAMBDA = 1.0                  # boundary-loss weight
SIGMA = 1.5                   # frames (30 ms) Gaussian half-width on targets
POS_WEIGHT = 15.0
VAL_FRAC = 0.05
MS_PER_FRAME = 20.0


class EncCTCBoundary(nn.Module):
    """Exp 1 architecture plus a 2-channel per-frame boundary head."""

    def __init__(self, vocab):
        super().__init__()
        from transformers import WhisperConfig, WhisperModel

        cfg = WhisperConfig.from_pretrained(tr.BASE)
        cfg.max_source_positions = tr.ENC_FRAMES
        enc = WhisperModel(cfg).encoder
        full = WhisperModel.from_pretrained(tr.BASE)
        sd = full.encoder.state_dict()
        sd["embed_positions.weight"] = sd["embed_positions.weight"][:tr.ENC_FRAMES]
        enc.load_state_dict(sd, strict=False)
        del full
        self.enc = enc
        self.head = nn.Linear(cfg.d_model, vocab)
        self.bhead = nn.Linear(cfg.d_model, 2)      # [start, end] logits

    def forward(self, feats):
        h = self.enc(feats).last_hidden_state
        return self.head(h), self.bhead(h)


def boundary_targets(clips, n_frames):
    """Gaussian bumps at every ground-truth word start and end."""
    tgt = np.zeros((len(clips), n_frames, 2), dtype=np.float16)
    rad = int(np.ceil(3 * SIGMA))
    ker = np.exp(-0.5 * (np.arange(-rad, rad + 1) / SIGMA) ** 2)
    for i, c in enumerate(clips):
        for sg in c["segments"]:
            for ch, ms in ((0, sg[2]), (1, sg[3])):
                f = int(round(ms / MS_PER_FRAME))
                lo, hi = max(0, f - rad), min(n_frames, f + rad + 1)
                if lo >= hi:
                    continue
                k = ker[lo - (f - rad): (rad + 1) + (hi - f - 1)]
                cur = tgt[i, lo:hi, ch].astype(np.float32)
                tgt[i, lo:hi, ch] = np.maximum(cur, k[:hi - lo]).astype(np.float16)
    return tgt


def main():
    torch.manual_seed(tr.SEED)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev}  {torch.cuda.get_device_name(0) if dev == 'cuda' else ''}")

    data = json.loads(CORPUS.read_text(encoding="utf-8"))
    clips = data["clips"]
    itos, stoi = tr.build_vocab(clips)
    print(f"[data] {len(clips)} clips  {data['total_hours']} h  vocab {len(itos)}")

    feats = np.load(FEATS / f"{TAG}_{len(clips)}_{tr.MEL_FRAMES}.npy", mmap_mode="r")
    meta = json.loads((FEATS / f"{TAG}_{len(clips)}_{tr.MEL_FRAMES}.json").read_text())
    lens, bad = meta["lens"], set(meta.get("bad", []))
    print(f"[feats] reused {feats.shape}  ({len(bad)} unreadable excluded)")

    targets = [tr.encode(c["words"], stoi) for c in clips]
    print("[targets] building boundary supervision ...")
    btgt = boundary_targets(clips, tr.ENC_FRAMES)
    dens = float(btgt.astype(np.float32).mean())
    print(f"[targets] boundary target density {dens:.4f}")

    idx = np.array([i for i in range(len(clips)) if i not in bad])
    rng = np.random.default_rng(tr.SEED)
    rng.shuffle(idx)
    n_val = max(16, int(len(idx) * VAL_FRAC))
    val_idx, tr_idx = idx[:n_val], idx[n_val:]
    print(f"[split] train={len(tr_idx)} val={len(val_idx)}")

    model = EncCTCBoundary(len(itos)).to(dev)
    print(f"[model] {sum(p.numel() for p in model.parameters())/1e6:.2f}M params")

    opt = torch.optim.AdamW(model.parameters(), lr=tr.LR, weight_decay=0.01)
    steps = EPOCHS * (len(tr_idx) // tr.BATCH)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=tr.LR,
                                                total_steps=steps, pct_start=0.1)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    bce = nn.BCEWithLogitsLoss(
        pos_weight=torch.tensor([POS_WEIGHT, POS_WEIGHT], device=dev), reduction="none")

    def batches(ids, bs, shuffle=True):
        ids = ids.copy()
        if shuffle:
            rng.shuffle(ids)
        for i in range(0, len(ids) - bs + 1, bs):
            yield ids[i:i + bs]

    def run(b, train):
        x = torch.from_numpy(np.asarray(feats[b], dtype=np.float32)).to(dev)
        tg = [targets[i] for i in b]
        tl = torch.tensor([len(t) for t in tg], dtype=torch.long)
        flat = torch.tensor([c for t in tg for c in t], dtype=torch.long)
        il = torch.tensor([lens[i] for i in b], dtype=torch.long)
        bt = torch.from_numpy(np.asarray(btgt[b], dtype=np.float32)).to(dev)
        with torch.autocast(dev, dtype=torch.bfloat16, enabled=(dev == "cuda")):
            logits, blog = model(x)
        lp = torch.log_softmax(logits.float(), dim=-1).transpose(0, 1)
        l_ctc = ctc(lp, flat, il, tl)
        m = (torch.arange(tr.ENC_FRAMES, device=dev)[None, :] <
             il.to(dev)[:, None]).float()[:, :, None]
        l_bnd = (bce(blog.float(), bt) * m).sum() / m.sum().clamp(min=1) / 2
        loss = l_ctc + LAMBDA * l_bnd
        if train:
            loss.backward()
        return loss, l_ctc, l_bnd

    log, best, t0 = [], 1e9, time.time()
    for ep in range(1, EPOCHS + 1):
        model.train()
        a = b_ = c_ = n = 0
        for bi in batches(tr_idx, tr.BATCH):
            opt.zero_grad(set_to_none=True)
            loss, lc, lb = run(bi, True)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            if sched.last_epoch < steps - 1:
                sched.step()
            a += loss.item(); b_ += lc.item(); c_ += lb.item(); n += 1
        model.eval()
        va = vc = vb = vn = 0
        with torch.no_grad():
            for bi in batches(val_idx, min(tr.BATCH, len(val_idx)), shuffle=False):
                loss, lc, lb = run(bi, False)
                va += loss.item(); vc += lc.item(); vb += lb.item(); vn += 1
        rec = {"epoch": ep, "train": round(a / n, 4), "train_ctc": round(b_ / n, 4),
               "train_bnd": round(c_ / n, 4), "val": round(va / vn, 4),
               "val_ctc": round(vc / vn, 4), "val_bnd": round(vb / vn, 4),
               "sec": round(time.time() - t0)}
        log.append(rec)
        print(f"  ep {ep:>3}  train {rec['train']:.4f} (ctc {rec['train_ctc']:.4f} "
              f"bnd {rec['train_bnd']:.4f})  val {rec['val']:.4f} "
              f"(ctc {rec['val_ctc']:.4f} bnd {rec['val_bnd']:.4f})  {rec['sec']}s",
              flush=True)
        if rec["val"] < best:
            best = rec["val"]
            torch.save({"model": model.state_dict(), "vocab": itos,
                        "enc_frames": tr.ENC_FRAMES, "mel_frames": tr.MEL_FRAMES,
                        "epoch": ep, "val": rec["val"], "val_ctc": rec["val_ctc"],
                        "val_bnd": rec["val_bnd"]}, CKPT / "best.pt")

    (RESULTS / "23_train_exp2.json").write_text(json.dumps(
        {"experiment": "2_boundary_head", "base": tr.BASE, "lambda": LAMBDA,
         "sigma_frames": SIGMA, "pos_weight": POS_WEIGHT, "epochs": EPOCHS,
         "hours": data["total_hours"], "target_density": round(dens, 5),
         "best_val_total": round(best, 4), "log": log}, indent=2), encoding="utf-8")
    print(f"\nbest val {best:.4f} -> {CKPT/'best.pt'}")


if __name__ == "__main__":
    main()
