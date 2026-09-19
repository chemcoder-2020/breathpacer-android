# Breath Pacer (Android)

Native companion to the [web pacer](https://chemcoder-2020.github.io/breathwork/), for the one
thing a browser cannot do: **true vibration-amplitude ramps**.

## Why this exists

The web Vibration API is on/off only — `navigator.vibrate()` takes durations, never intensities.
The Pixel Watch-style "path" cue comes from Android's amplitude-controlled haptics
(`VibrationEffect.createWaveform(timings, amplitudes, repeat)`, amplitudes 0–255). That needs an
app with the `VIBRATE` permission, so this is it.

## What it does

7 slots matching the daily schedule, each phase played as a single `VibrationEffect` waveform:

| Phase | Cue |
|---|---|
| Inhale | pulsed at 2.5 Hz, envelope climbing pulse-peak by pulse-peak (`20 → 140` on a `t^0.8` curve) |
| Exhale | the mirror: peaks falling, gaps opening |
| Hold | **a firm double tap, then silence** — 80 ms taps 100 ms apart, nothing after |
| Sigh top-up | one 120 ms pulse |
| Free breathing | one 200 ms pulse every 11 s |

Every phase change is marked by a **1.6× longer first tap**, and pulse rate (2 / 2.5 / 3 Hz),
duty (30%) and strength (default 55% of the reference amplitudes) are adjustable on the device
and persisted there.

Waveforms are emitted whole per phase, so the envelope runs inside the vibrator HAL — it does not
jitter with the UI thread, and each phase starts on the beat because every waveform's durations
sum to exactly the phase length. A hold deliberately goes quiet instead of buzzing through: you
are not breathing, so nothing should be shaking, and the silence makes the next cue sharp.

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
- `Schedules.kt` — slot wall-clock times + next-trigger maths (pure Kotlin, unit-tested)
- `Reminders.kt` — daily `AlarmManager` reminders + notification channel + boot reschedule
- `MainActivity.kt` — slot picker, session engine, reminder toggles, wake lock
- `PatternsTest.kt` — envelope and total-length assertions
- `SchedulesTest.kt` — reminder time and next-trigger assertions

## Reminders

A "Reminders" section sits below the session controls: a master on/off toggle plus one
toggle per slot, persisted in `SharedPreferences`. When on, each slot fires a daily
`AlarmManager.setInexactRepeating` alarm (inexact survives Doze and needs no special
permission screen) that posts a notification; tapping it opens the app with that slot
pre-selected. Reminders re-schedule after a reboot (`RECEIVE_BOOT_COMPLETED`). Android 13+
asks for `POST_NOTIFICATIONS` on the first toggle tap.
