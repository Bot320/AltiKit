# Offline forecast: training

The watch predicts the weather of the next 3, 6, 12 or 24 hours from its own pressure log, with no
internet. Two forecasters run side by side:

- **AI network**: a small neural network trained here, with one softmax head per horizon, so a single
  pass gives all four. About 2,000 weights, 25 KB of JSON.
- **Zambretti**: the classic 1915 barometer rules, as a well-known baseline.

[RESULTS.md](RESULTS.md) has the latest evaluation of both against held-out data.

## Steps

Python 3 with numpy, nothing else.

```sh
python3 fetch.py            # ~1.5 h, throttled to stay inside Open-Meteo's free limits; resumable
python3 train.py            # a few minutes; writes the model, test vectors and RESULTS.md
```

Both default to `/mnt/wwn-0x5002538f00000000-part1/altikit/forecast-data` for the downloaded data
(about 25 MB). Pass `--out` / `--data` to use another folder.

`train.py` writes:

| File | Used by |
|---|---|
| `app/src/main/assets/forecast_model.json` | the app (`NeuralForecast`) |
| `app/src/test/resources/forecast_golden.json` | `ForecastTest`, which checks that the Kotlin port gives the same features, probabilities and Zambretti letters as Python |
| `tools/forecast/RESULTS.md` | you |

Zambretti's letters are read as A–F fair, G–M cloudy, N–X rain and Y–Z storm (`Z_CUTS` in `train.py`,
`Zambretti.CUTS` in the app; `ForecastTest` fails if they differ). RESULTS.md also lists the cut points
that would score best on the data, for reference.

## Data

Hourly ERA5 reanalysis from the [Open-Meteo archive API](https://open-meteo.com/en/docs/historical-weather-api),
2019–2024, at 55 places in [locations.py](locations.py): Germany (coast to Zugspitze) and the Alps make
up the core and count double in training; the rest covers Poland, Czechia, Austria, Italy, Spain and
eastern France. Three places are never trained on, to check that the model works somewhere new.

Sea-level pressure is computed from surface pressure and elevation with the same ISA formula as the app,
not taken from ERA5, so training and watch inputs match.

## Inputs and labels

Inputs (`Features.kt` mirrors `features()` here):

| Feature | Meaning |
|---|---|
| `msl` | current sea-level weather pressure |
| `d1` … `d24` | change over 1, 3, 6, 12 and 24 h |
| `m6`, `m12`, `m24` | 1 if that much history exists; otherwise the change is extrapolated from the 3 h trend |
| `alt_km` | altitude |
| `hour_*`, `doy_*` | time of day and season (sin/cos), local time |

Training adds what the watch sees in real life: 0.15 hPa sensor noise, ±3 hPa calibration error,
drift from AUTO mode occasionally misreading movement, and logs that are only 3–12 h long.

Labels, one per horizon H (3, 6, 12, 24 h) = worst weather in the next H hours:

| Class | Rule |
|---|---|
| STORM | gusts ≥ 60 km/h, or ≥ 8 mm in 3 h |
| RAIN | ≥ 1 mm (snow included) |
| CLOUDY | mean cloud cover ≥ 70 % |
| FAIR | anything else |

## Why no NPU

One prediction, all four horizons at once, is about 2,000 multiply-adds: microseconds on the watch CPU. Handing that to an NPU
would cost more energy in driver setup and data copies than the maths itself, and Android's NNAPI
route is deprecated. The app runs it in plain Kotlin with preallocated buffers, only when the forecast
page is open or inside the background log sample that already wakes the watch every 15 minutes.
