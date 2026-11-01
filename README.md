# DMR Heat Map

Android app: RTL-SDR + phone GPS → live coverage map of a DMR repeater's output frequency.

## Phone setup (once)
1. Install **RTL-SDR driver** (Martin Marinov) from the Play Store. It owns the USB dongle; this app talks to it.
2. Install this app: copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it,
   or use wireless debugging: `adb pair` / `adb connect`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Plug in the RTL-SDR through a USB-C OTG adapter.

## Survey
1. Enter the repeater **output** frequency in MHz, pick a gain (start around 29.7 dB) and press **Start**.
   Allow location + notifications, then allow USB access in the driver's prompt.
2. Bench check: while the repeater is idle the SNR should stay near 0 dB. When it transmits, the readout turns
   green (ACTIVE). If you see "Clipping", lower the gain. Keep the same gain for the whole survey.
3. Drive. Each second the strongest reading and the GPS fix are logged. Points with SNR ≥ the threshold are painted
   as ~25 m cells: blue = weak (10 dB) → red = strong (40+ dB).
   **The repeater must be transmitting**, so keep traffic on it (e.g. a parrot/echo talkgroup or a busy talkgroup).
4. Each Start begins a new session file. Earlier sessions at the same frequency stay on the map, even after
   the app restarts. Changing the frequency shows that frequency's sessions instead.
5. On **Stop** (and on **Export**) everything is copied to **Download/DMR Heat Map/** (visible in the Files app or over
   USB): each session CSV plus `dmr_<MHz>_all.csv` and `dmr_<MHz>_all.kml` combining every session at that frequency.
   Export also opens the share sheet with the combined CSV + KML (open the KML in Google Earth / Google My Maps).
6. **Clear map** (while stopped) saves the frequency's sessions to Download/DMR Heat Map, then removes them from the
   map so the next survey starts fresh. The per-session CSVs in Downloads are kept; the `_all` files only cover
   sessions recorded after the clear.

Values are relative (dBFS / SNR), not calibrated dBm.

## Build
`gradlew test assembleDebug` (uses Android Studio's JDK: set `JAVA_HOME` to `C:\Program Files\Android\Android Studio\jbr`).
