# Breath Pacer (Android)

Native companion to the [web pacer](https://chemcoder-2020.github.io/breathwork/), for the one
thing a browser cannot do: **true vibration-amplitude ramps**.

## Why this exists

The web Vibration API is on/off only — `navigator.vibrate()` takes durations, never intensities.
The Pixel Watch-style "path" cue comes from Android's amplitude-controlled haptics
(`VibrationEffect.createWaveform(timings, amplitudes, repeat)`, amplitudes 0–255). That needs an
app with the `VIBRATE` permission, so this is it.

## What it does

7 slots matching the daily schedule, each phase played as a single amplitude-envelope waveform:

| Phase | Envelope |
|---|---|
| Inhale | amplitude ramps `40 → 255` on a `t^0.8` curve (a swell) |
| Exhale | `255 → 40`, mirrored (a fade) |
| Hold | steady `80` — a presence, not a buzz |
| Sigh top-up | 120 ms at `180` |
| Free breathing | one 200 ms pulse at `90` every 11 s |

Waveforms are emitted whole per phase (25–133 ms segments, capped at 60), so the ramp runs inside
the vibrator HAL — it does not jitter with the UI thread, and the phase boundary lands exactly
because each waveform's durations sum to the phase length.

The app shows whether the device actually supports amplitude control
(`Vibrator.hasAmplitudeControl()`). Without it, Android maps every non-zero amplitude to 100% and
the ramp degenerates to a steady buzz — that warning is deliberate.

## Build & install

CI builds it; no local Android SDK required:

1. Actions → latest green `android-build` run → Artifacts → **breathpacer-debug-apk**
2. Unzip, copy `app-debug.apk` to the phone, tap it, allow "install unknown apps" for that source
3. Debug builds are signed with the debug keystore, so sideloading works as-is

`gradle testDebugUnitTest` verifies the ramp maths (monotonic envelopes, exact phase totals, every
planned phase has a felt amplitude) without needing a device.

## Layout

- `Patterns.kt` — the plan + waveform envelopes (pure Kotlin, unit-tested)
- `MainActivity.kt` — slot picker, session engine, wake lock
- `PatternsTest.kt` — envelope and total-length assertions
