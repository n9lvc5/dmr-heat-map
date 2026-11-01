# DMR Heat Map — project notes

Android app that maps received signal strength of one DMR repeater's output frequency while driving.
Hardware: RTL-SDR dongle (R820T/R828D tuner assumed) on a Pixel 10 Pro via USB-C OTG. User priority: **simplicity**.

## Status (2026-11-01)
- v0.1 code complete; `gradlew test assembleDebug` passes (4 DSP unit tests).
- **Not yet tested on the phone/dongle.** Next step is a bench test: driver connects, SNR ≈ 0 dB when the repeater is idle, and SNR jumps with the readout turning green when it transmits. Then a short drive, then check the exported KML in Google Earth.
- The user may come back with a session CSV for tuning (threshold, offset, cell size).

## Open in Android Studio
- Open the folder `C:\Dev\DMR Mapping` (root `settings.gradle.kts`). Gradle 9.6.0 wrapper, AGP 9.5.0.
- AGP 9 has **built-in Kotlin**, so there is no `org.jetbrains.kotlin.android` plugin. Don't add one.
- JDK: Android Studio's bundled JBR (`C:\Program Files\Android\Android Studio\jbr`). For the CLI, set `JAVA_HOME` to it first.
- `local.properties` points to `C:/Users/techn/AppData/Local/Android/Sdk` (gitignored).
- compileSdk/targetSdk 36, minSdk 29. Deps (`gradle/libs.versions.toml`): activity-ktx, kotlinx-coroutines-android, osmdroid 6.1.20, junit.
- Install on the phone with **wireless debugging**, since the dongle occupies the USB-C port.

## Architecture
The app does **not** drive USB itself. It launches the "RTL-SDR driver" app (`marto.rtl_tcp_andro`, Play Store) with the
intent `iqsrc://-a 127.0.0.1 -p 14423 -n 1`. That app owns the dongle and serves **rtl_tcp** on localhost; we read 8-bit IQ over TCP.

| File | Role |
|---|---|
| `sdr/RtlTcpClient.kt` | rtl_tcp protocol: 12-byte `RTL0` header, 5-byte commands (0x01 freq, 0x02 rate, 0x03 manual gain, 0x04 gain in tenths of a dB, 0x05 ppm, 0x08 AGC); driver intent + R820T gain table |
| `dsp/Fft.kt` | Radix-2 complex FFT (no deps) |
| `dsp/ChannelPowerMeter.kt` | 1.024 MS/s, tuned **+200 kHz offset** (keeps the DC spike out of the channel), 16384-pt Hann FFT. Channel = sum of bins within ±7 kHz; noise = median of bins within ±400 kHz (excluding DC and the channel guard), scaled to the channel bandwidth. Outputs dBFS, SNR and clip fraction |
| `survey/SurveyService.kt` | Foreground service (type `location`) + partial wake lock. SDR thread measures every block; per 1 Hz GPS fix (`LocationManager.GPS_PROVIDER`) logs **peak** channel power, mean noise, and active = SNR ≥ threshold. Appends to the CSV |
| `survey/SurveyState.kt` | Process-wide `StateFlow`s shared by the service and UI (simple singleton, no binding) |
| `survey/HeatGrid.kt` | ~25 m cells, mean SNR of **active** points only; colour blue (10 dB) → red (40 dB) |
| `survey/Exporters.kt` | CSV format/parse, KML writer (5 dB colour buckets) |
| `survey/SessionStore.kt` | Session files; loads all sessions at a frequency onto the map; `exportPublic` copies them via MediaStore to `Download/DMR Heat Map/` plus combined `dmr_<MHz>_all.csv/.kml` (overwrites by name) |
| `ui/MainActivity.kt` | Single screen, classic Views (not Compose, on purpose: osmdroid is a View). Settings in SharedPreferences; permission → driver launch → start service. Export runs `exportPublic` and shares the combined CSV + KML MediaStore URIs |
| `ui/HeatOverlay.kt` | osmdroid Overlay that draws the grid cells |

Sessions: `Android/data/com.dmrmap/files/sessions/dmr_<MHz>_<stamp>.csv` (one per Start; the source of truth).
Copied to the public `Download/DMR Heat Map/` on Stop (from `SurveyService.onDestroy`) and on Export.
CSV columns: `time_ms,lat,lon,accuracy_m,peak_dbfs,noise_dbfs,snr_db,active`.

## Domain notes / design decisions
- DMR is TDMA and bursty: the repeater only transmits while keyed. That's why we log the **peak** per second and only paint points above the SNR threshold. The surveyor must keep traffic on the repeater (parrot/echo talkgroup).
- Manual fixed gain with AGC off, so readings are comparable across a survey. Values are relative (dBFS/SNR), **not calibrated dBm**.
- "Inactive" points are logged but not drawn, because they're ambiguous (no coverage vs. nobody transmitting).
- The map (`SurveyState.grid`) is rebuilt from every session CSV at the current frequency (on launch and on Start), so Start/Stop/Start and app restarts don't lose points. "Clear map" (`SessionStore.clear`, only while stopped) runs `exportPublic` first, then deletes that frequency's private CSVs; the public per-session copies remain.
- After a reinstall (not an update), MediaStore no longer lets the app see its old files in Downloads, so re-exporting creates "name (1).csv" copies.

## Possible next steps (not started)
- Option to colour by peak dBFS instead of SNR; adjustable cell size.
- Decode the DMR colour code to confirm it's the right repeater.
- dBFS → dBm calibration offset.
- Remove `ACCESS_NETWORK_STATE`/`ACCESS_COARSE_LOCATION` if unneeded; app icon (currently a stock system drawable).

## Conventions
- Keep it simple and dependency-light; match the existing Kotlin style (small classes, short KDoc on non-obvious bits).
- Run `gradlew test` after DSP changes; `ChannelPowerMeterTest` uses synthetic IQ tones.
