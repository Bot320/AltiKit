#!/usr/bin/env python3
"""Trains AltiKit's offline forecast network and compares it with Zambretti and climatology.

Reads the per-location .npz files from fetch.py, builds exactly the features of
app/src/main/java/app/altikit/core/forecast/Features.kt, trains a small MLP in plain numpy and writes

    app/src/main/assets/forecast_model.json       the network for the watch
    app/src/test/resources/forecast_golden.json   test vectors for the Kotlin port
    tools/forecast/RESULTS.md                     evaluation table

    python3 train.py [--data DIR]
"""
import argparse
import json
import os
import sys
from datetime import date, datetime
from zoneinfo import ZoneInfo

import numpy as np

from locations import CORE, HELD_OUT, LOCATIONS

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
DEFAULT_DATA = "/mnt/wwn-0x5002538f00000000-part1/altikit/forecast-data"

CLASSES = ["FAIR", "CLOUDY", "RAIN", "STORM"]
FEATURES = ["msl", "d1", "d3", "d6", "d12", "d24", "m6", "m12", "m24",
            "alt_km", "hour_sin", "hour_cos", "doy_sin", "doy_cos"]
LAGS = [1, 3, 6, 12, 24]
# Forecast horizons in hours; the network has one softmax head per horizon. The app defaults to 12.
HORIZONS = [3, 6, 12, 24]
MAIN = HORIZONS.index(12)

# Label thresholds over the next H hours (worst condition wins).
STORM_GUST_KMH = 60.0
STORM_RAIN3H_MM = 8.0
RAIN_MM = 1.0
CLOUDY_PCT = 70.0

TRAIN_YEARS = range(2019, 2023)
VAL_YEAR = 2023
TEST_YEAR = 2024
CORE_WEIGHT = 2.0

# Same ISA constants as core/Atmosphere.kt.
K, EXP = 44330.77, 0.190263


def sea_level(p, h):
    return p / (1 - h / K) ** (1 / EXP)


# ---------------------------------------------------------------- Zambretti (mirror of Zambretti.kt)

Z_RISING = np.array([25, 25, 25, 24, 24, 19, 16, 12, 11, 9, 8, 6, 5, 2, 1, 1, 0, 0, 0, 0, 0, 0])
Z_STEADY = np.array([25, 25, 25, 25, 25, 25, 23, 23, 22, 18, 15, 13, 10, 4, 1, 1, 0, 0, 0, 0, 0, 0])
Z_FALLING = np.array([25, 25, 25, 25, 25, 25, 25, 25, 23, 23, 21, 20, 17, 14, 7, 3, 1, 1, 1, 0, 0, 0])
Z_TREND = 1.6
# Shipped letter → class reading (first letter of CLOUDY, RAIN, STORM): A–F fair, G–M changeable,
# N–X rain, Y–Z stormy. Matches Zambretti.CUTS, so the class always agrees with the forecast text.
Z_CUTS = (6, 13, 24)


def zambretti(msl, d3, month):
    """Forecast index 0 (A, settled fine) … 25 (Z, stormy). Vectorised; same arithmetic order as Kotlin."""
    msl, d3, month = np.broadcast_arrays(np.asarray(msl, float), np.asarray(d3, float), np.asarray(month))
    trend = np.where(d3 >= Z_TREND, 1, np.where(d3 <= -Z_TREND, -1, 0))
    rng = 1050.0 - 950.0
    p = msl + np.where((month >= 4) & (month <= 9), trend * 7.0 / 100 * rng, 0.0)
    p = np.where(p >= 1050.0, 1050.0 - 1, p)
    opt = np.clip(np.floor((p - 950.0) / (rng / 22)).astype(int), 0, 21)
    return np.where(trend == 1, Z_RISING[opt], np.where(trend == -1, Z_FALLING[opt], Z_STEADY[opt]))


def z_class(index, cuts):
    return np.searchsorted(np.asarray(cuts), index, side="right")


# ---------------------------------------------------------------- data


def local_time(t, tz):
    """Local hour fraction, 0-based day of year and month for unix seconds t."""
    zone = ZoneInfo(tz)
    # The UTC offset only changes at DST switches: evaluate it once per day and refine near switches.
    off = np.array([datetime.fromtimestamp(int(x), zone).utcoffset().total_seconds() for x in t[::24]])
    off = np.repeat(off, 24)[: len(t)]
    change = np.flatnonzero(np.diff(off) != 0)
    for i in change:
        for j in range(i, min(i + 26, len(t))):
            off[j] = datetime.fromtimestamp(int(t[j]), zone).utcoffset().total_seconds()
    local = t + off.astype(np.int64)
    hour = (local % 86400) / 3600.0
    days = local.astype("datetime64[s]").astype("datetime64[D]")
    doy = (days - days.astype("datetime64[Y]")).astype(int) + hour / 24
    month = days.astype("datetime64[M]").astype(int) % 12 + 1
    year = days.astype("datetime64[Y]").astype(int) + 1970
    return hour, doy, month, year


def time_features(hour, doy):
    a = 2 * np.pi * hour / 24
    b = 2 * np.pi * doy / 365.25
    return np.stack([np.sin(a), np.cos(a), np.sin(b), np.cos(b)], axis=-1)


def window(a, n, fn):
    from numpy.lib.stride_tricks import sliding_window_view
    return fn(sliding_window_view(a, n), axis=1)


def labels(pr, gust, cloud, r3, h):
    """Class of the next h hours for every hour i (aggregates over i+1 … i+h)."""
    rain = window(pr[1:], h, np.sum)
    m = len(rain)
    gmax = window(gust[1:], h, np.max)[:m]
    cmean = window(cloud[1:], h, np.mean)[:m]
    # Wettest 3 h block inside the window.
    r3max = window(r3, h - 2, np.max)[:m]
    storm = (gmax >= STORM_GUST_KMH) | (r3max >= STORM_RAIN3H_MM)
    return np.where(storm, 3, np.where(rain >= RAIN_MM, 2, np.where(cmean >= CLOUDY_PCT, 1, 0)))


def load(data_dir):
    """Per location: base arrays at every usable hour i (24 h of history before, 24 h after)."""
    out = []
    for name, lat, lon, region in LOCATIONS:
        f = os.path.join(data_dir, name + ".npz")
        if not os.path.exists(f):
            print(f"  missing {name}, skipped")
            continue
        d = np.load(f)
        elev = float(d["elevation"])
        msl = sea_level(d["surface_pressure"].astype(float), elev)
        pr = np.nan_to_num(d["precipitation"].astype(float))
        gust = np.nan_to_num(d["wind_gusts_10m"].astype(float))
        cloud = np.nan_to_num(d["cloud_cover"].astype(float))
        n = len(msl)
        idx = np.arange(24, n - max(HORIZONS))
        r3 = window(pr[1:], 3, np.sum)
        y = np.stack([labels(pr, gust, cloud, r3, h)[idx] for h in HORIZONS], axis=1)
        lagged = np.stack([msl[idx - h] for h in LAGS], axis=1)
        hour, doy, month, year = local_time(d["t"], str(d["timezone"]))
        ok = np.isfinite(msl[idx]) & np.all(np.isfinite(lagged), axis=1)
        out.append(dict(
            name=name, region=region, elev=elev,
            p0=msl[idx][ok], lagged=lagged[ok], y=y[ok],
            tf=time_features(hour[idx], doy[idx])[ok], month=month[idx][ok], year=year[idx][ok],
        ))
    return out


def subset(locs, names=None, years=None, exclude=()):
    parts = [l for l in locs if (names is None or l["name"] in names) and l["name"] not in exclude]
    if not parts:
        return None
    res = {k: [] for k in ("p0", "lagged", "y", "tf", "month", "alt", "w", "core")}
    for l in parts:
        m = np.isin(l["year"], list(years)) if years is not None else np.ones(len(l["y"]), bool)
        k = int(m.sum())
        res["p0"].append(l["p0"][m]); res["lagged"].append(l["lagged"][m]); res["y"].append(l["y"][m])
        res["tf"].append(l["tf"][m]); res["month"].append(l["month"][m])
        res["alt"].append(np.full(k, l["elev"]))
        core = l["region"] in CORE
        res["core"].append(np.full(k, core)); res["w"].append(np.full(k, CORE_WEIGHT if core else 1.0))
    return {k: np.concatenate(v) for k, v in res.items()}


# ---------------------------------------------------------------- features (mirror of Features.kt)


def make_features(s, rng=None, history=None):
    """Feature matrix. With rng: watch-like noise, calibration offset, AUTO drift and random history length.
    history: fixed hours of history for evaluation (default: full 24 h)."""
    n = len(s["y"])
    p0 = s["p0"].copy()
    lag = s["lagged"].copy()  # columns = LAGS
    alt = s["alt"].copy()
    if rng is not None:
        p0 += rng.normal(0, 0.15, n)
        lag += rng.normal(0, 0.15, lag.shape)
        drift = rng.random(n) < 0.3
        lag += drift[:, None] * rng.normal(0, 0.25, lag.shape) * np.sqrt(np.array(LAGS) / 3.0)
        offset = rng.normal(0, 3.0, n)
        alt = alt + rng.normal(0, 30.0, n)
        hist = rng.choice([3, 6, 12, 24], size=n, p=[0.1, 0.1, 0.15, 0.65])
    else:
        offset = np.zeros(n)
        hist = np.full(n, 24 if history is None else history)
    d = p0[:, None] - lag
    d3 = d[:, 1]
    cols = [p0 + offset, d[:, 0], d3]
    masks = []
    for j, h in enumerate(LAGS[2:], start=2):
        have = hist >= h
        cols.append(np.where(have, d[:, j], d3 * h / 3))
        masks.append(have.astype(float))
    x = np.column_stack(cols + masks + [alt / 1000.0, s["tf"]])
    return x


def extract_series(series, current, now_ms, alt_m, tz):
    """Python twin of Features.extract for a raw (t_ms, msl) series, used for the golden vectors."""
    H, SLACK = 3_600_000, 20 * 60_000
    s = [(t, v) for t, v in series if not np.isnan(v) and t < now_ms] + [(now_ms, current)]

    def value_at(t, max_gap=3 * H):
        if t < s[0][0] or t > s[-1][0]:
            return None
        lo, hi = 0, len(s) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if s[mid][0] <= t:
                lo = mid
            else:
                hi = mid
        (t0, v0), (t1, v1) = s[lo], s[hi]
        if t == t0:
            return v0
        if t == t1:
            return v1
        if t1 - t0 > max_gap:
            return None
        return v0 + (v1 - v0) * (t - t0) / (t1 - t0)

    def lag(h):
        t = now_ms - h * H
        v = value_at(t)
        if v is None and t < s[0][0] and s[0][0] - t <= SLACK:
            v = s[0][1]
        return v

    p3 = lag(3)
    if p3 is None:
        return None
    d3 = current - p3
    p1 = lag(1)
    d1 = current - p1 if p1 is not None else d3 / 3
    longer = []
    for h in (6, 12, 24):
        v = lag(h)
        longer.append((current - v, 1.0) if v is not None else (d3 * h / 3, 0.0))
    local = datetime.fromtimestamp(now_ms / 1000, ZoneInfo(tz))
    hour = local.hour + local.minute / 60.0
    doy = local.timetuple().tm_yday - 1 + hour / 24
    tf = time_features(np.array(hour), np.array(doy))
    return [current, d1, d3, longer[0][0], longer[1][0], longer[2][0],
            longer[0][1], longer[1][1], longer[2][1], alt_m / 1000.0] + [float(v) for v in tf]


# ---------------------------------------------------------------- model


def softmax(z):
    """Softmax per horizon: logits (n, horizons * 4) become probabilities (n, horizons, 4)."""
    z = z.reshape(len(z), -1, len(CLASSES))
    z = z - z.max(axis=-1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=-1, keepdims=True)


class MLP:
    def __init__(self, sizes, rng):
        self.W = [rng.normal(0, np.sqrt(2 / a), (b, a)) for a, b in zip(sizes[:-1], sizes[1:])]
        self.b = [np.zeros(b) for b in sizes[1:]]

    def forward(self, x):
        acts = [x]
        for l, (W, b) in enumerate(zip(self.W, self.b)):
            z = acts[-1] @ W.T + b
            acts.append(np.maximum(z, 0) if l < len(self.W) - 1 else z)
        return acts

    def probs(self, x):
        return softmax(self.forward(x)[-1])

    def grads(self, x, y, w):
        """y: (n, horizons) class labels. Loss = mean over heads of the weighted cross-entropy."""
        acts = self.forward(x)
        p = softmax(acts[-1])
        n, k = y.shape
        rows = np.arange(n)[:, None]
        heads = np.arange(k)[None, :]
        ws = w / w.sum() / k
        loss = -(ws[:, None] * np.log(p[rows, heads, y] + 1e-12)).sum()
        d = p.copy()
        d[rows, heads, y] -= 1
        d = (d * ws[:, None, None]).reshape(n, -1)
        gW, gb = [None] * len(self.W), [None] * len(self.W)
        for l in reversed(range(len(self.W))):
            gW[l] = d.T @ acts[l]
            gb[l] = d.sum(0)
            if l > 0:
                d = (d @ self.W[l]) * (acts[l] > 0)
        return loss, gW, gb

    def params(self):
        return self.W + self.b


def train(tr, va, hidden, seed=1, epochs=30, batch=1024, lr=2e-3, decay=1e-5):
    rng = np.random.default_rng(seed)
    x0 = make_features(tr, rng)
    mean, std = x0.mean(0), x0.std(0)
    std[std < 1e-6] = 1.0
    xv = (make_features(va, np.random.default_rng(99)) - mean) / std
    net = MLP([len(FEATURES), *hidden, len(CLASSES) * len(HORIZONS)], rng)
    m = [np.zeros_like(p) for p in net.params()]
    v = [np.zeros_like(p) for p in net.params()]
    step, best, best_params, patience = 0, np.inf, None, 0
    for ep in range(epochs):
        x = (make_features(tr, rng) - mean) / std  # fresh noise every epoch
        order = rng.permutation(len(x))
        a = lr * (0.5 ** (ep / 10))
        for i in range(0, len(order), batch):
            j = order[i:i + batch]
            _, gW, gb = net.grads(x[j], tr["y"][j], tr["w"][j])
            step += 1
            for k, (p, g) in enumerate(zip(net.params(), gW + gb)):
                g = g + decay * p
                m[k] = 0.9 * m[k] + 0.1 * g
                v[k] = 0.999 * v[k] + 0.001 * g * g
                p -= a * (m[k] / (1 - 0.9 ** step)) / (np.sqrt(v[k] / (1 - 0.999 ** step)) + 1e-8)
        pv = net.probs(xv)
        picked = pv[np.arange(len(pv))[:, None], np.arange(len(HORIZONS))[None, :], va["y"]]
        vloss = -(va["w"][:, None] * np.log(picked + 1e-12)).sum() / va["w"].sum() / len(HORIZONS)
        acc = " ".join(f"{h}h {np.mean(pv[:, i].argmax(1) == va['y'][:, i]):.3f}" for i, h in enumerate(HORIZONS))
        print(f"    epoch {ep + 1:2d}  val loss {vloss:.4f}  acc {acc}", flush=True)
        if vloss < best - 1e-4:
            best, best_params, patience = vloss, [p.copy() for p in net.params()], 0
        else:
            patience += 1
            if patience >= 4:
                break
    n = len(net.W)
    net.W, net.b = best_params[:n], best_params[n:]
    return net, mean, std, best


# ---------------------------------------------------------------- evaluation


def macro_f1(y, pred, k=4):
    f = []
    for c in range(k):
        tp = np.sum((pred == c) & (y == c))
        fp = np.sum((pred == c) & (y != c))
        fn = np.sum((pred != c) & (y == c))
        f.append(0.0 if tp == 0 else 2 * tp / (2 * tp + fp + fn))
    return float(np.mean(f))


def scores(y, pred, probs=None):
    wet, wet_p = y >= 2, pred >= 2
    hits = np.sum(wet & wet_p)
    r = dict(
        acc=float(np.mean(pred == y)),
        f1=macro_f1(y, pred),
        pod=float(hits / max(wet.sum(), 1)),  # share of wet periods that were forecast wet
        far=float(np.sum(~wet & wet_p) / max(wet_p.sum(), 1)),  # share of wet forecasts that stayed dry
        storm_pod=float(np.sum((y == 3) & (pred == 3)) / max(np.sum(y == 3), 1)),
    )
    if probs is not None:
        onehot = np.eye(4)[y]
        r["brier"] = float(np.mean(np.sum((probs - onehot) ** 2, axis=1)))
    return r


def tune_cuts(z_index, y):
    """Zambretti index → class cut points with the best macro-F1 (on training data)."""
    table = np.zeros((26, 4))
    np.add.at(table, (z_index, y), 1)
    best, best_cuts = -1, None
    for c1 in range(1, 24):
        for c2 in range(c1 + 1, 25):
            for c3 in range(c2 + 1, 26):
                conf = np.stack([table[:c1].sum(0), table[c1:c2].sum(0), table[c2:c3].sum(0), table[c3:].sum(0)])
                tp = np.diag(conf)
                f1 = np.mean(np.where(tp > 0, 2 * tp / np.maximum(conf.sum(0) + conf.sum(1), 1), 0))
                if f1 > best:
                    best, best_cuts = f1, (c1, c2, c3)
    return best_cuts


def evaluate(net, mean, std, tuned, prior, s, history=24, climatology=True):
    """{horizon: {source: scores}}. Zambretti has no horizon, so its one forecast is scored against each."""
    x = make_features_eval(s, history)
    p = net.probs((x - mean) / std)
    zi = zambretti(x[:, 0], x[:, 2], s["month"])
    out = {}
    for i, h in enumerate(HORIZONS):
        y = s["y"][:, i]
        res = {
            "AI network": scores(y, p[:, i].argmax(1), p[:, i]),
            "Zambretti": scores(y, z_class(zi, Z_CUTS)),
            "Zambretti, tuned cut points": scores(y, z_class(zi, tuned)),
        }
        if climatology:
            clim = prior[i][s["month"] - 1]
            res["Climatology"] = scores(y, clim.argmax(1), clim)
        out[h] = res
    return out


def make_features_eval(s, history=24):
    """Sensor noise only (0.15 hPa), no calibration offset: a calibrated watch at rest."""
    rng = np.random.default_rng(7)
    t = dict(s)
    t["p0"] = s["p0"] + rng.normal(0, 0.15, len(s["y"]))
    t["lagged"] = s["lagged"] + rng.normal(0, 0.15, s["lagged"].shape)
    return make_features(t, None, history)


# ---------------------------------------------------------------- export


def export(net, mean, std, cuts, metrics, path):
    def r(a):
        return [float(f"{v:.7g}") for v in np.asarray(a).ravel()]

    doc = {
        "version": 1,
        "trained": date.today().isoformat(),
        "horizons_h": HORIZONS,
        "classes": CLASSES,
        "features": FEATURES,
        "labels": {"storm_gust_kmh": STORM_GUST_KMH, "storm_rain3h_mm": STORM_RAIN3H_MM,
                   "rain_mm": RAIN_MM, "cloudy_pct": CLOUDY_PCT},
        "zambretti_cuts": list(map(int, cuts)),
        "mean": r(mean),
        "std": r(std),
        "layers": [{"w": [r(row) for row in W], "b": r(b)} for W, b in zip(net.W, net.b)],
        "metrics": metrics,
    }
    with open(path, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    return doc


def predict_json(doc, x):
    """Probabilities per horizon from the exported (rounded) weights, as the app computes them."""
    x = (np.asarray(x, float) - np.array(doc["mean"])) / np.array(doc["std"])
    for i, layer in enumerate(doc["layers"]):
        x = np.array(layer["w"]) @ x + np.array(layer["b"])
        if i < len(doc["layers"]) - 1:
            x = np.maximum(x, 0)
    return softmax(x[None, :])[0]


def golden(doc, cuts, path):
    """Synthetic watch logs (15 min samples) with the expected Kotlin results."""
    rng = np.random.default_rng(5)
    tz = "Europe/Berlin"
    now = int(datetime(2025, 7, 14, 16, 37, tzinfo=ZoneInfo(tz)).timestamp() * 1000)
    cases = []
    specs = [  # (hours of history, trend hPa/h, gap (start h, end h) or None, altitude m)
        (30, -0.9, None, 520.0), (4.5, 0.4, None, 1800.0), (7, -0.2, None, 35.0),
        (14, 1.1, None, 2950.0), (26, -0.05, (8, 10.5), 300.0), (2, -1.0, None, 100.0),
        (24, -2.5, None, 700.0),
    ]
    for k, (hours, slope, gap, alt) in enumerate(specs):
        case_now = now - k * 3_600_000 // 7  # varies the time-of-day features
        n = int(hours * 4)
        ts = [case_now - (n - i) * 900_000 for i in range(n)]
        base = 1012.0 + rng.normal(0, 4)
        series = []
        for t in ts:
            ago = (case_now - t) / 3_600_000
            if gap and gap[0] <= ago <= gap[1]:
                continue
            series.append((t, round(base - slope * ago + rng.normal(0, 0.1) + 0.6 * np.sin(ago / 3), 2)))
        current = round(base + rng.normal(0, 0.1), 2)
        x = extract_series(series, current, case_now, alt, tz)
        case = {"series": [[t, v] for t, v in series], "current": current,
                "now": case_now, "alt": alt, "zone": tz, "features": x}
        if x is not None:
            month = datetime.fromtimestamp(case["now"] / 1000, ZoneInfo(tz)).month
            zi = int(zambretti(x[0], x[2], month))
            case.update(probs=[[float(v) for v in head] for head in predict_json(doc, x)], zambretti=zi,
                        zambretti_class=CLASSES[int(z_class(zi, cuts))])
        cases.append(case)
    with open(path, "w") as f:
        json.dump({"cases": cases}, f, indent=1)


# ---------------------------------------------------------------- main


def table(results, horizon):
    lines = ["| Test set | Source | Accuracy | Macro-F1 | Wet hit rate | Wet false alarms | Storm hit rate | Brier |",
             "|---|---|---|---|---|---|---|---|"]
    for name, by_h in results.items():
        for src, r in by_h[horizon].items():
            brier = f"{r['brier']:.3f}" if "brier" in r else "–"
            lines.append(f"| {name} | {src} | {r['acc']:.1%} | {r['f1']:.3f} | {r['pod']:.1%} | {r['far']:.1%} "
                         f"| {r['storm_pod']:.1%} | {brier} |")
    return "\n".join(lines)


def summary(results):
    """One line per horizon for the app README: Germany + Alps 2024, AI vs Zambretti vs climatology."""
    lines = ["| Horizon | AI accuracy | Zambretti | Climatology | AI wet hit rate | AI wet false alarms |",
             "|---|---|---|---|---|---|"]
    for h, r in results["2024, Germany + Alps"].items():
        ai = r["AI network"]
        lines.append(f"| {h} h | {ai['acc']:.0%} | {r['Zambretti']['acc']:.0%} | {r['Climatology']['acc']:.0%} "
                     f"| {ai['pod']:.0%} | {ai['far']:.0%} |")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--hidden", default="32,32")
    ap.add_argument("--epochs", type=int, default=30)
    args = ap.parse_args()
    hidden = [int(h) for h in args.hidden.split(",")]

    print("loading", args.data, flush=True)
    locs = load(args.data)
    names_core = {l[0] for l in LOCATIONS if l[3] in CORE}
    tr = subset(locs, years=TRAIN_YEARS, exclude=HELD_OUT)
    va = subset(locs, years=[VAL_YEAR], exclude=HELD_OUT)
    print(f"train {len(tr['y'])}  val {len(va['y'])}")
    for i, h in enumerate(HORIZONS):
        print(f"  {h:2d} h class mix {np.round(np.bincount(tr['y'][:, i], minlength=4) / len(tr['y']), 3)}")

    # Consistency: the vectorised features equal the series-based twin of Features.kt.
    l0 = locs[0]
    i = 5000
    t_ms = 1_600_000_000_000
    ser = [(t_ms - h * 3_600_000, float(v)) for h, v in zip(LAGS, l0["lagged"][i])]
    x_series = extract_series(sorted(ser), float(l0["p0"][i]), t_ms, l0["elev"], "UTC")
    x_vec = make_features({"p0": l0["p0"][i:i + 1], "lagged": l0["lagged"][i:i + 1], "alt": np.array([l0["elev"]]),
                           "tf": time_features(np.array([(t_ms / 1000 % 86400) / 3600]), np.array([0.0])),
                           "y": np.zeros((1, len(HORIZONS)), int)}, None, 24)[0]
    assert np.allclose(x_series[:10], x_vec[:10]), (x_series, x_vec)

    # Zambretti class cut points and monthly climatology from the training years.
    xt = make_features_eval(tr)
    tuned = tune_cuts(zambretti(xt[:, 0], xt[:, 2], tr["month"]), tr["y"][:, MAIN])
    print("zambretti cuts: shipped", Z_CUTS, "tuned on 12 h", tuned)
    prior = []
    for i in range(len(HORIZONS)):
        pr = np.stack([np.bincount(tr["y"][tr["month"] == mth, i], minlength=4) for mth in range(1, 13)]).astype(float)
        prior.append(pr / pr.sum(1, keepdims=True))

    print("training", hidden, flush=True)
    net, mean, std, vloss = train(tr, va, hidden, epochs=args.epochs)

    test_sets = {
        "2024, Germany + Alps": subset(locs, names=names_core, years=[TEST_YEAR], exclude=HELD_OUT),
        "2024, rest of area": subset(locs, names={l[0] for l in LOCATIONS} - names_core, years=[TEST_YEAR], exclude=HELD_OUT),
        "2024, unseen places": subset(locs, names=HELD_OUT, years=[TEST_YEAR]),
    }
    test_sets = {k: s for k, s in test_sets.items() if s is not None}
    results = {k: evaluate(net, mean, std, tuned, prior, s) for k, s in test_sets.items()}
    # Only 3 h of log (freshly installed or logging just switched on), Germany + Alps.
    results["2024, Germany + Alps, 3 h log"] = evaluate(
        net, mean, std, tuned, prior, test_sets["2024, Germany + Alps"], history=3, climatology=False)
    for h in HORIZONS:
        print(f"\n{h} h\n" + table(results, h))
    print("\n" + summary(results))

    head = results["2024, Germany + Alps"]
    metrics = {f"{h}h": {k: {m: round(v, 4) for m, v in r.items()} for k, r in head[h].items()} for h in HORIZONS}
    per_horizon = "\n".join(f"\n## {h} h\n\n{table(results, h)}\n" for h in HORIZONS)
    doc = export(net, mean, std, Z_CUTS, metrics, os.path.join(REPO, "app/src/main/assets/forecast_model.json"))
    golden(doc, Z_CUTS, os.path.join(REPO, "app/src/test/resources/forecast_golden.json"))

    n_params = sum(W.size + b.size for W, b in zip(net.W, net.b))
    with open(os.path.join(HERE, "RESULTS.md"), "w") as f:
        f.write(f"""# Forecast evaluation

Generated by `train.py` on {date.today().isoformat()}. Network {len(FEATURES)}→{'→'.join(map(str, hidden))}→{len(CLASSES)}×{len(HORIZONS)}
(one softmax per horizon), {n_params} weights; validation loss {vloss:.4f}.

Zambretti letters are read as A–F fair, G–M cloudy, N–X rain, Y–Z storm, as shipped in the app. For
reference, the row "tuned cut points" uses the boundaries that score best on the training years
({', '.join('ABCDEFGHIJKLMNOPQRSTUVWXYZ'[c] for c in tuned)}), which no longer agree with the letters' texts.

Task: weather in the next {', '.join(map(str, HORIZONS))} h at the watch's location, worst condition wins:
STORM (gusts ≥ {STORM_GUST_KMH:.0f} km/h or ≥ {STORM_RAIN3H_MM:.0f} mm in 3 h), RAIN (≥ {RAIN_MM} mm, snow included),
CLOUDY (mean cloud cover ≥ {CLOUDY_PCT:.0f} %), otherwise FAIR.

Data: hourly ERA5 reanalysis via the Open-Meteo archive, {len(locs)} places. Trained on {TRAIN_YEARS.start}–{TRAIN_YEARS.stop - 1},
early stopping on {VAL_YEAR}, tested on {TEST_YEAR}. The unseen places ({', '.join(sorted(HELD_OUT))}) were never trained on.
Test inputs carry 0.15 hPa of sensor noise. "Wet" means RAIN or STORM. Zambretti has no horizon of its own,
so its single forecast is scored against each horizon.

## Summary: Germany + Alps, 2024

{summary(results)}
{per_horizon}
Climatology always predicts the most common class for the month; it is the bar any forecaster has to clear.
""")
    print("wrote model, golden vectors and RESULTS.md")


if __name__ == "__main__":
    sys.exit(main())
