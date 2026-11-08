# CLAUDE.md

Working notes for Claude Code on **RoldOS**, Federico's ultra-light Android TV
launcher. Read `JOURNAL.md` for the history and the reasons behind decisions, and
add to it whenever something notable happens (see "Keeping the journal").

## Working with Federico

- Talk to him in **Spanish**, in plain words, with no jargon (a technical status
  update once "meant nothing" to him). Say what changed and what it means for him.
- He decides features and names; you decide implementation. Recommend, don't
  survey options.
- Before changing anything on his devices that is hard to undo, say what and why.
  Disable instead of uninstall, and keep the undo command at hand.
- **Don't test on the projector unless he asks.** He tests RoldOS himself. No
  key presses, no screenshots, no installs while he's using it: build, then tell
  him it's ready (or install only when he asks for it).

## Product rules (Federico's)

- **Settings tile → strictly the stock MAIN settings menu**, never wherever the
  stock menu was last left (Projection, Picture Mode, …). `HomeKeyService.requestSettingsMenu()`
  steps back until the menu (node `rl_project`) is on screen, and presses nothing.
- **Calibration tile → the Projection page**, and Back from there returns to RoldOS.
- Settings row order: **Calibration, Settings, HDMI, Wireless Cast, Wired Cast,
  Original launcher**. No Wi-Fi tile. Every tile a different color; Settings is blue.
- Top bar: RoldOS wordmark (`res/drawable-xhdpi/wordmark.png`), hairline, clock, and a
  plain text **Close all apps** button (no icon). Closing shows RoldOS's own notice pill
  (instant "Closing apps…", then MB freed), not Android's toast.
- Background: **B2 Graphite**, flat `#141516`, no gradients (the old navy-purple
  gradient looked "AI-generated"). Accent: amber `#FFB547`. App icon: "RO" (amber O) on graphite.
- Favorites reorder like Google TV: hold OK → **Move** → ◀ ▶ → OK. Home also ends a move.
- The hold-OK app menu is drawn **inside RoldOS's own window**, never as an Android dialog:
  after a dialog closed, this projector sometimes never gave key focus back, and every key
  was dropped (logcat: `Dropping event due to no window focus`). Don't add dialogs.
- Rows like Google TV: the row you're on is bigger (1.05×), the others smaller (0.8×) and
  dimmed; the screen and the row glide (360 ms ease-out) so the focused row sits near the top
  and the focused tile at the row's start. All scrolling goes through `onFocusMoved`.
- The name under a tile appears **under that tile**, not pinned to the left.
- The README is promotional: big type and images, little text, **no diagrams**, and
  it compares against "a popular alternative launcher" and the stock launcher
  without naming the alternative.

## Golden rules

1. **Stay light.** Framework widgets only: no AndroidX, Compose, Gradle deps or
   image libraries. Measure before and after anything that could add memory (see
   "Measuring"). The whole point is to stay far lighter than other launchers on a
   1 GB device.
2. **Never stop, kill or disable `com.htc.luminaos`** (the stock launcher). It
   hosts all projector calibration (keystone, focus, projection mode) through
   `.receiver.ProjectModeReceiver`, and force-stopped apps don't receive
   broadcasts. Also never touch `com.hysd.vafocus` (autofocus).
3. **Before disabling any vendor app, find out what launches it.** That's how
   `com.softwinner.awlivetv` got disabled as a "tuner app" when it's actually the
   HDMI viewer (see JOURNAL, 2026-11-09).
4. **Don't hand-roll a copy of a brand's art.** Tiles use the banner or icon the app
   itself ships; everything else is original drawing in `TileArt`.

## Build and deploy

```bash
./build.sh                                # build/roldos.apk
./build.sh install 192.168.0.121:5555     # install + set home + re-enable Home service
```

- Toolchain: Android Studio's bundled JBR (`C:\Program Files\Android\Android Studio\jbr`),
  SDK at `%LOCALAPPDATA%\Android\Sdk` (build-tools 36, platform android-37 as
  compile jar). `adb` isn't on PATH: use `$LOCALAPPDATA/Android/Sdk/platform-tools/adb`.
- Build order matters: aapt2 link (generates `R.java`) → javac → d8 → add dex → zipalign → sign.
- Signed with the local debug key (`~/.android/debug.keystore`). Changing the
  signing key or the package name (`com.roldos`) makes Android treat it as a
  new app: favorites are lost and setup must be redone.

### Accessibility service gotchas (Home key depends on it)

- An update that changes the service's capabilities **turns it off**;
  `build.sh install` turns it back on.
- `am force-stop com.roldos` **unbinds** it. Re-run:
  `settings put secure enabled_accessibility_services com.roldos/.HomeKeyService`
  and `settings put secure accessibility_enabled 1`.
- Uninstalling another a11y app can clear the whole list. Verify with
  `dumpsys accessibility | grep "Bound services"`.
- **The stock settings menu wipes the enabled-accessibility list every time it
  opens** (confirmed 2026-11-09). `MainActivity` watches the setting with a
  ContentObserver and writes our service back; that needs
  `WRITE_SECURE_SETTINGS`, granted by `build.sh install` with `pm grant`. When the
  service rebinds mid-request it resumes polling (`onServiceConnected`).
- Android also turns the service off a moment *after* `install` returns, so
  `build.sh install` waits, enables, verifies "Bound services", and retries.
- Because of those unbinds, never trust window events alone for "which stock page
  is showing": read the live screen (`getRootInActiveWindow`). Markers:
  `rl_project` = main settings menu, `rl_project_mode` = Projection page.

## The devices

| | Projector (main target) | Chromecast with Google TV |
|---|---|---|
| Address | `192.168.0.121:5555` (legacy adb, PC authorized) | `192.168.0.176`, TLS adb, port changes: `adb mdns services` |
| SoC / OS | Allwinner H713 `sun50iw12p1`, Android 11 (API 30) | Amlogic "sabrina", Android 14 (API 34) |
| RAM | 960 MB (~300–400 MB free) | 2 GB |
| ABI | 32-bit only (`armeabi-v7a`) | 32-bit only |
| Notes | Magcubic; spoofs "ADT-3"; stock launcher `com.htc.luminaos` | TV is 1080p, no HDR; AV1 is software-only |

RoldOS currently runs only on the projector.

## Projector integration (reverse-engineered, see JOURNAL)

| Want | How |
|---|---|
| Open a stock menu | Broadcast `com.htc.intent.action.<X>` explicitly to `com.htc.luminaos/.receiver.ProjectModeReceiver` (e.g. `OPEN_MAIN_SETTINGS`, `OPEN_WIFI_SETTINGS`, `OPEN_MANUAL_KEYSTONE`, `OPEN_SOURCE`) |
| Projection page | `ProjectActivity` is **not exported**. Open `OPEN_MAIN_SETTINGS`, then `HomeKeyService` clicks node `com.htc.luminaos:id/rl_project` (text fallback "Projection") |
| Switch to HDMI | Start `com.softwinner.awlivetv/.MainActivity` with extra `input_source=HDMI1` (the chip declares HDMI1–3 + CVBS but only one HDMI port exists) |
| Catch the Home key | The stock firmware ignores the default-home setting; `HomeKeyService` filters `KEYCODE_HOME` and also bounces back when `com.htc.luminaos.activity.MainActivity` appears (unless the Original launcher tile set `allowStockHome()`; the next Home press clears it) |
| Cast apps | Wireless Cast = `com.softwinner.miracastReceiver/.Miracast`, Wired Cast = `com.toofifi.lineserver/.ui.MainActivity`. Both hidden from All apps |

**Never test Wireless Cast over wireless adb:** the Miracast receiver switches the
projector's Wi-Fi to direct mode and drops it off the network (adb goes offline
until it's closed).

## Code map

- `MainActivity` builds the UI in code (top bar with clock + "Close all apps" text
  pill; rows Favorites / All apps / Settings), loads apps on one background thread,
  stores favorites (package names) in SharedPreferences `roldos`.
- `TileArt` does all drawing: `appBanner` (generated per-app banners from the
  icon's dominant color) and `settingsBanner` with `Kind` = CALIBRATION, SETTINGS,
  HDMI, WIRELESS_CAST, WIRED_CAST, ORIGINAL_HOME (icon family "A": white line icons,
  one gradient each). Keep new system tiles in this same family so the brand stays
  consistent.
- Branding: the name is always **RoldOS** (capital R, O, S; never "roldos" or "Roldos").
  Wordmark (choice T1): **Unbounded Bold**, white "Rold" + **amber "OS"** (#FFB547). No colored
  period: that was Tandem's recipe and he wants RoldOS apart from it. Promo headlines use
  Unbounded, body copy Roboto. Promo images live in `docs/promo/` (HTML
  sources) and are rendered to `docs/images/roldos-*.png` with headless Edge. GitHub and
  browsers cache images hard: if a re-render doesn't show up, rename the files (a `?v=` suffix
  wasn't enough).
- `HomeKeyService` (accessibility): Home key + stock-home bounce + Calibration click.
- Focus: Android's geometric search can't reach the top-right button from left
  tiles, so Up from the first row and Down from the button are routed by hand in
  `dispatchKeyEvent`. Until the user moves, focus is forced onto the first tile.

## Testing on the device

- Drive it with `adb shell input keyevent KEYCODE_DPAD_*`. Wait **~1.5 s between
  presses**: faster presses get lost during focus animations and look like bugs.
- Screenshots: `adb shell screencap -p /sdcard/s.png` + `adb pull`. Look at them.
- Crashes: `adb logcat -b crash -d`.

### Measuring

- Cold start: `am force-stop` then `am start -W -n com.roldos/.MainActivity`
  (TotalTime), 3 runs. Re-enable the a11y service afterwards.
- Memory: wait 30 s after opening, then `dumpsys meminfo com.roldos` → TOTAL PSS.
  Compare against other launchers under the same conditions.

## Keeping the journal

Append to `JOURNAL.md` (newest at the bottom, dated) when you: finish a milestone,
discover something about the devices or vendor software, make or reverse a design
decision, make a mistake worth not repeating, or take new measurements.
