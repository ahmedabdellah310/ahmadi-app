"""
RATL Rung 3 — Experiment 1: whisper-tiny (Quran-adapted) encoder + CTC head.

Hypothesis H1: CTC on a continuous-recitation-adapted mel-input encoder, with an
explicit '|' word-separator token, materially improves word-boundary accuracy
over the measured baseline (word-end 44.0%, G2 56.11%).

Init: tarteel-ai/whisper-tiny-ar-quran encoder (Apache-2.0), positional
embeddings sliced from 30 s to a 15 s window.

$0: local RTX 4060. THROWAWAY SPIKE CODE. Not RATL Core.
"""

import json
import os
import pathlib
import time

HERE = pathlib.Path(__file__).parent
os.environ.setdefault("HF_HOME", str(HERE / ".cache"))

import numpy as np  # noqa: E402
import torch  # noqa: E402
import torch.nn as nn  # noqa: E402

AUDIO = HERE / ".cache" / "audio"
CORPUS = HERE / "corpus"
FEATS = HERE / ".cache" / "feats"
CKPT = HERE / "models" / "exp1"
RESULTS = HERE / "results"
for p in (FEATS, CKPT, RESULTS):
    p.mkdir(parents=True, exist_ok=True)

SR = 16000
WINDOW_S = 15.0
N_MELS = 80
MEL_FRAMES = int(WINDOW_S * 100)      # 1500 @ 100 fps
ENC_FRAMES = MEL_FRAMES // 2          # 750 @ 50 fps -> 20 ms/frame
BASE = "tarteel-ai/whisper-tiny-ar-quran"
SEED = 1447
EPOCHS = 30
BATCH = 12
LR = 3e-4
VAL_FRAC = 0.05


def build_vocab(clips):
    chars = set()
    for c in clips:
        for w in c["words"]:
            chars.update(w.lower())
    chars.discard("")
    itos = ["<blank>", "|"] + sorted(chars)
    stoi = {c: i for i, c in enumerate(itos)}
    return itos, stoi


def encode(words, stoi):
    parts = []
    for i, w in enumerate(words):
        if i:
            parts.append(stoi["|"])
        parts.extend(stoi[ch] for ch in w.lower() if ch in stoi)
    return parts


def precompute(clips, fe):
    """Cache log-mel features (float16) once."""
    import librosa

    path = FEATS / f"exp1_{len(clips)}_{MEL_FRAMES}.npy"
    meta = FEATS / f"exp1_{len(clips)}_{MEL_FRAMES}.json"
    if path.exists() and meta.exists():
        print(f"[feats] reusing {path.name}")
        return np.load(path, mmap_mode="r"), json.loads(meta.read_text())
    print(f"[feats] computing {len(clips)} x ({N_MELS},{MEL_FRAMES}) ...")
    arr = np.lib.format.open_memmap(
        path, mode="w+", dtype=np.float16, shape=(len(clips), N_MELS, MEL_FRAMES))
    lens = []
    t0 = time.time()
    for i, c in enumerate(clips):
        a, _ = librosa.load(str(AUDIO / c["audio"]), sr=SR, mono=True)
        n = min(len(a), int(WINDOW_S * SR))
        f = fe(a[:n], sampling_rate=SR, return_tensors="np",
               padding="max_length", max_length=int(WINDOW_S * SR))
        m = f["input_features"][0][:, :MEL_FRAMES]
        if m.shape[1] < MEL_FRAMES:
            m = np.pad(m, ((0, 0), (0, MEL_FRAMES - m.shape[1])), constant_values=m.min())
        arr[i] = m.astype(np.float16)
        lens.append(int(min(n / SR * 50, ENC_FRAMES)))  # valid encoder frames
        if (i + 1) % 250 == 0:
            print(f"        {i+1}/{len(clips)}  {time.time()-t0:.0f}s")
    arr.flush()
    meta.write_text(json.dumps({"lens": lens}))
    return np.load(path, mmap_mode="r"), {"lens": lens}


class EncCTC(nn.Module):
    def __init__(self, vocab):
        super().__init__()
        from transformers import WhisperConfig, WhisperModel

        cfg = WhisperConfig.from_pretrained(BASE)
        cfg.max_source_positions = ENC_FRAMES
        enc = WhisperModel(cfg).encoder

        full = WhisperModel.from_pretrained(BASE)
        sd = full.encoder.state_dict()
        sd["embed_positions.weight"] = sd["embed_positions.weight"][:ENC_FRAMES]
        missing, unexpected = enc.load_state_dict(sd, strict=False)
        print(f"[init] loaded pretrained encoder  missing={len(missing)} unexpected={len(unexpected)}")
        del full

        self.enc = enc
        self.head = nn.Linear(cfg.d_model, vocab)

    def forward(self, feats):
        return self.head(self.enc(feats).last_hidden_state)


def main():
    torch.manual_seed(SEED)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[dev] {dev}  {torch.cuda.get_device_name(0) if dev=='cuda' else ''}")

    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(BASE)
    fe.chunk_length = int(WINDOW_S)
    fe.n_samples = int(WINDOW_S * SR)
    fe.nb_max_frames = MEL_FRAMES

    data = json.loads((CORPUS / "exp1-train.json").read_text(encoding="utf-8"))
    clips = data["clips"]
    print(f"[data] {len(clips)} clips  {data['total_hours']} h")

    itos, stoi = build_vocab(clips)
    print(f"[vocab] {len(itos)}: {''.join(itos[1:])}")
    (CKPT / "vocab.json").write_text(json.dumps(itos, ensure_ascii=False), encoding="utf-8")

    feats, meta = precompute(clips, fe)
    lens = meta["lens"]
    targets = [encode(c["words"], stoi) for c in clips]

    idx = np.arange(len(clips))
    rng = np.random.default_rng(SEED)
    rng.shuffle(idx)
    n_val = max(16, int(len(idx) * VAL_FRAC))
    val_idx, tr_idx = idx[:n_val], idx[n_val:]
    print(f"[split] train={len(tr_idx)} val={len(val_idx)}")

    model = EncCTC(len(itos)).to(dev)
    n_par = sum(p.numel() for p in model.parameters())
    print(f"[model] {n_par/1e6:.2f}M params")

    opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=0.01)
    steps = EPOCHS * (len(tr_idx) // BATCH)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=steps,
                                                pct_start=0.1)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    scaler = torch.amp.GradScaler(dev) if dev == "cuda" else None

    def batches(ids, bs, shuffle=True):
        ids = ids.copy()
        if shuffle:
            rng.shuffle(ids)
        for i in range(0, len(ids) - bs + 1, bs):
            yield ids[i:i + bs]

    log = []
    best = 1e9
    t0 = time.time()
    for ep in range(1, EPOCHS + 1):
        model.train()
        tot = n = 0
        for b in batches(tr_idx, BATCH):
            x = torch.from_numpy(np.asarray(feats[b], dtype=np.float32)).to(dev)
            tg = [targets[i] for i in b]
            tl = torch.tensor([len(t) for t in tg], dtype=torch.long)
            flat = torch.tensor([c for t in tg for c in t], dtype=torch.long)
            il = torch.tensor([lens[i] for i in b], dtype=torch.long)
            opt.zero_grad(set_to_none=True)
            with torch.autocast(dev, dtype=torch.bfloat16, enabled=(dev == "cuda")):
                logits = model(x)
            lp = torch.log_softmax(logits.float(), dim=-1).transpose(0, 1)
            loss = ctc(lp, flat, il, tl)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            if sched.last_epoch < steps - 1:
                sched.step()
            tot += loss.item()
            n += 1
        tr_loss = tot / max(1, n)

        model.eval()
        vt = vn = 0
        with torch.no_grad():
            for b in batches(val_idx, min(BATCH, len(val_idx)), shuffle=False):
                x = torch.from_numpy(np.asarray(feats[b], dtype=np.float32)).to(dev)
                tg = [targets[i] for i in b]
                tl = torch.tensor([len(t) for t in tg], dtype=torch.long)
                flat = torch.tensor([c for t in tg for c in t], dtype=torch.long)
                il = torch.tensor([lens[i] for i in b], dtype=torch.long)
                with torch.autocast(dev, dtype=torch.bfloat16, enabled=(dev == "cuda")):
                    logits = model(x)
                lp = torch.log_softmax(logits.float(), dim=-1).transpose(0, 1)
                vt += ctc(lp, flat, il, tl).item()
                vn += 1
        va_loss = vt / max(1, vn)
        log.append({"epoch": ep, "train_ctc": round(tr_loss, 4),
                    "val_ctc": round(va_loss, 4), "sec": round(time.time() - t0)})
        print(f"  ep {ep:>3}  train {tr_loss:.4f}  val {va_loss:.4f}  "
              f"({time.time()-t0:.0f}s)")
        if va_loss < best:
            best = va_loss
            torch.save({"model": model.state_dict(), "vocab": itos,
                        "enc_frames": ENC_FRAMES, "mel_frames": MEL_FRAMES,
                        "epoch": ep, "val_ctc": va_loss}, CKPT / "best.pt")

    (RESULTS / "10_train_exp1.json").write_text(json.dumps(
        {"base": BASE, "params": n_par, "vocab_size": len(itos), "vocab": itos,
         "epochs": EPOCHS, "batch": BATCH, "lr": LR, "window_s": WINDOW_S,
         "enc_frames": ENC_FRAMES, "train_clips": len(tr_idx), "val_clips": len(val_idx),
         "hours": data["total_hours"], "best_val_ctc": round(best, 4),
         "device": torch.cuda.get_device_name(0) if dev == "cuda" else "cpu",
         "log": log}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nbest val CTC {best:.4f} -> {CKPT/'best.pt'}")


if __name__ == "__main__":
    main()
