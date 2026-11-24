#!/usr/bin/env python3
"""Downloads hourly ERA5 history from the Open-Meteo archive API for the forecast training set.

One compressed .npz per location is written to the output directory. Locations that are
already cached are skipped, so the script can be stopped and resumed (e.g. the next day if the
free daily API budget runs out).

    python3 fetch.py [--out DIR] [--pause SECONDS]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

import numpy as np

from locations import LOCATIONS

DEFAULT_OUT = "/mnt/wwn-0x5002538f00000000-part1/altikit/forecast-data"
START, END = "2019-01-01", "2024-12-31"
HOURLY = ["surface_pressure", "pressure_msl", "precipitation", "cloud_cover", "wind_gusts_10m", "weather_code"]
URL = (
    "https://archive-api.open-meteo.com/v1/archive?latitude={lat}&longitude={lon}"
    "&start_date={start}&end_date={end}&hourly={hourly}&timezone=auto&timeformat=unixtime"
)


def fetch(lat, lon):
    url = URL.format(lat=lat, lon=lon, start=START, end=END, hourly=",".join(HOURLY))
    for attempt in range(8):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")[:200]
            if e.code == 429:
                # Over the free minutely/hourly/daily budget: wait and try again.
                print(f"    429 rate limited ({body}); sleeping 15 min", flush=True)
                time.sleep(900)
                continue
            print(f"    HTTP {e.code}: {body}", flush=True)
        except Exception as e:  # network hiccups
            print(f"    {type(e).__name__}: {e}", flush=True)
        time.sleep(30 * (attempt + 1))
    raise RuntimeError("giving up")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--pause", type=float, default=90.0, help="seconds between locations (stays under 5000 calls/h)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    todo = [l for l in LOCATIONS if not os.path.exists(os.path.join(args.out, l[0] + ".npz"))]
    print(f"{len(LOCATIONS) - len(todo)} cached, {len(todo)} to fetch -> {args.out}", flush=True)
    for i, (name, lat, lon, region) in enumerate(todo):
        print(f"[{i + 1}/{len(todo)}] {name} ({lat}, {lon}) {region}", flush=True)
        d = fetch(lat, lon)
        h = d["hourly"]
        arr = {k: np.array([np.nan if v is None else v for v in h[k]], dtype=np.float32) for k in HOURLY}
        tmp = os.path.join(args.out, name + ".tmp.npz")
        np.savez_compressed(
            tmp,
            t=np.array(h["time"], dtype=np.int64),
            elevation=np.float32(d["elevation"]),
            lat=np.float32(d["latitude"]),
            lon=np.float32(d["longitude"]),
            timezone=np.array(d["timezone"]),
            region=np.array(region),
            **arr,
        )
        os.replace(tmp, os.path.join(args.out, name + ".npz"))
        print(f"    ok: {len(h['time'])} h, elevation {d['elevation']} m, tz {d['timezone']}", flush=True)
        if i + 1 < len(todo):
            time.sleep(args.pause)
    print("done", flush=True)


if __name__ == "__main__":
    sys.exit(main())
