# Performance and verification

Everything on this page was measured on **one phone**: a Samsung Galaxy A30s running
Android 16, a 2019 mid-range device, USB-powered, battery 100 %, with about 11,800 SMS on it.
Numbers from other phones will differ. If you measure yours, please open a
[device report](https://github.com/Mahdi-mortazavi/angaros/issues/new?template=device_report.yml).

## Method

Each Android run does:

- **10 cold starts:** `am force-stop`, then `am start -W`, recording `TotalTime`.
- **10 warm starts:** HOME, then `am start -W`.
- `dumpsys meminfo` on the idle Home screen.
- **20 flings** through the Messages feed, then `dumpsys gfxinfo` (janky-frame share and percentiles).

ART state matters, so it is reported: *verify* is the state straight after `pm install`;
*speed-profile* is after `cmd package compile -m speed-profile -f`, which is what background
dexopt does with the bundled baseline profile.

## Android

| version · ART state | cold start median (min–max) | warm start median | PSS idle | PSS after scroll | janky frames | p90 frame |
|---|---|---|---|---|---|---|
| 3.0.1 · verify | 1121 ms (1063–1229) | 152 ms | 47 MB | 99 MB | 15.79 % | 53 ms |
| 3.0.2 · verify | 1076 ms (977–1155) | 145 ms | 46 MB | 103 MB | 15.19 % | 61 ms |
| 3.0.1 · speed-profile | 664 ms (597–734) | 140 ms | 55 MB | 111 MB | 12.33 % | 48 ms |
| 3.0.2 · speed-profile | 681 ms (598–731) | 122 ms | 58 MB | 113 MB | 13.35 % | 53 ms |
| **3.0.3 · speed-profile, run 1** | **609 ms (568–704)** | **126 ms** | **56 MB** | **117 MB** | **6.08 %** | **34 ms** |
| **3.0.3 · speed-profile, run 2** | **535 ms (514–570)** | **101 ms** | **51 MB** | **113 MB** | **6.23 %** | **34 ms** |

Reading it honestly:

- **Feed smoothness roughly doubled in 3.0.3** (13.35 % → about 6.2 % janky frames, p90 53 → 34 ms):
  rows are grouped and formatted off the main thread. The goal of **< 5 % is not reached** on this
  device.
- **Cold start, compiled:** 535–609 ms in 3.0.3, against 681 ms in 3.0.2. The two 3.0.3 runs differ by
  74 ms, so treat the improvement as real but its size as approximate. A 3.0.3 *verify-state* run
  (straight after install, before background compilation) **was not measured**; for 3.0.2 it was
  about 1.1 s.
- The baseline profile matters: it cuts cold start by about 40 % (3.0.2: 1076 → 681 ms).
- **Battery:** a 30-minute idle measurement on 3.0.3 was attempted but is **inconclusive** (the process
  was restarted during the window, so the CPU-time delta is invalid). In that window the app acquired
  two WorkManager job wakelocks and two notification wakelocks. No battery figure is claimed.

## Computer (`angaros`, process start included)

Against a real `phone.db` with 11,811 SMS, on Windows 11.

| command | median (min, max) |
|---|---|
| `--offline status` | 138 ms (133, 142) |
| `--offline search <word>` | 141 ms (139, 149) |
| `sync` | 661 ms (659, 6506) |
| `doctor` | 677 ms (642, 877) |
| in-process full-text search, three common words | 5.8 / 23.3 / 2.1 ms |

`sync` and `doctor` are dominated by the network round trip to the relay; the 6.5 s maximum is a
single slow round trip.

## Lifecycle on the real device (3.0.2)

| test | result |
|---|---|
| Reboot (owner-performed) | **pass:** the boot receiver started the service without opening the UI; it synced afterwards |
| Deep Doze (`dumpsys deviceidle force-idle deep`, battery unplugged) | **pass:** service alive, phone answered `ping` 3 of 3 times (1.2–2.7 s) |
| Light Doze | **pass** (5.5 s) |
| Trim memory (`RUNNING_MODERATE` … `RUNNING_CRITICAL`) in background | **pass:** process alive, no crash |
| `am kill`, `am kill-all` | **pass:** process survived (the foreground service keeps it perceptible) |
| `am force-stop` | documented Android behaviour: nothing restarts the app until it is opened again |
| Wi-Fi off for 25 s | **pass:** reconnected about 6 s after Wi-Fi returned; queued items drained |
| Crashes, ANRs, foreground-service errors across all of the above | 0 / 0 / 0 |
| Read-only CLI, SDK, REST + SSE and MCP (stdio + HTTP) checks against the live relay | 54 / 54 pass |

## Not verified

- Any other phone, manufacturer skin or Android version (minSdk is 26, Android 8.0).
- Incoming SMS while the phone is in Doze (only the command path was tested in Doze, because the
  test rules forbade sending SMS).
- Reboot with a locked screen: the app is not direct-boot aware and starts after the first unlock.
- Battery drain over a full day.
