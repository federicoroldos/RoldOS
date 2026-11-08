# RoldOS journal

Memories, milestones, discoveries and decisions, newest at the bottom.
Written by Claude Code while working with Federico; keep appending.

---

## 2026-11-08: Groundwork on the Chromecast

- **Milestone:** paired the PC with the Chromecast with Google TV over wireless
  debugging (TLS pairing code), so apps can be installed and debugged remotely.
- **Discovery:** the Chromecast runs 32-bit Android 14 on a 1080p, no-HDR TV;
  hardware decoders for VP9/HEVC/AVC, AV1 only in software.
- **Decision:** light debloat (`pm uninstall -k --user 0`, reversible with
  `cmd package install-existing`): TalkBack, TTS, Play Games, Play Movies,
  feedback, calendar sync, old launcher. Voice search and screensaver kept on
  Federico's request. Animations at 0.5×.
- **Tried and dropped:** running the phone build of YouTube ReVanced (+ GmsCore)
  natively on the TV. It worked but was "horrible to use" with a remote, so it was
  removed. Don't suggest phone apps on the TV again.
- **Decision:** for ad-free YouTube that keeps the original TV interface,
  **TizenTube Cobalt** (YouTube's TV web app in Google's Cobalt engine with an
  ad-blocking script) was installed from its GitHub releases (`cobalt-arm.apk`).
  The phone's ReVanced links to it with "Link with TV code", which works without
  Google Cast.

## 2026-11-08: The projector

- **Milestone:** connected to a Magcubic projector over legacy adb (port 5555).
- **Discovery:** Allwinner H713, 960 MB RAM, Android 11, 32-bit only. It pretends
  to be Google's "ADT-3" dev kit. Custom OS ruled out: there's no working ROM for
  this chip, autofocus/keystone live in vendor apps, and flashing risks a brick.
- **Discovery:** a hidden app `com.hotack.screensaversdk` was pushed onto the
  device after purchase, updates itself, and can silently install apps, draw over
  the screen and start at boot. Typical ad software. **Federico chose to leave it**,
  along with the vendor store and updaters that likely push it.
- **Decision:** disabled voice search (≈115 MB), a factory test app, an office suite,
  a test browser, and (by mistake, see below) the input viewer.
- **Decision:** Projectivy Launcher installed from Google Play (by pressing
  Install remotely, not a mirror APK). The remote's Home key is hard-wired to the
  stock launcher, so Projectivy's accessibility service was used to catch it.
- **Discovery:** the stock launcher `com.htc.luminaos` hosts **all calibration
  menus** (keystone, autofocus, four-corner, obstacle avoidance, projection mode,
  zoom) through `ProjectModeReceiver` and `com.htc.intent.action.*` broadcasts.
  It must keep running, so swapping launchers never saves its ~78 MB.
- **Milestone:** TizenTube installed on the projector too.

## 2026-11-09: RoldOS is born

- **Decision:** build our own launcher instead of tuning Projectivy. Framework
  widgets only, no Gradle, hand-written `build.sh`. First name "Brisa".
- **Milestone:** v1 running as the projector's home, with Favorites, All apps, a
  clock, quick buttons, and the Home key caught by our own accessibility service.
- **Measurement:** Projectivy 131 MB PSS / ~1.1 s cold start vs ours 56–68 MB / ~0.35 s.
- **Experiment, reverted:** a 16-bit window surface (`setFormat(RGB_565)`) saved
  only 1.6 MB, since most "Graphics" memory is driver-side, and made the background
  gradient band. Not worth it.
- **Bug fixed:** on first open, focus landed on a top button (the system's default
  pick). Now it's forced onto the first tile until the user moves.
- **Bug fixed:** Up from left tiles couldn't reach the top-right buttons
  (geometric focus search). Up/Down are now routed by hand.
- **Bug fixed:** Home pressed inside the launcher didn't return to the start,
  because Android pauses the activity right before `onNewIntent`. Don't gate on
  "resumed" there.

### Mistake worth remembering

`com.softwinner.awlivetv` was disabled on 2026-11-08 as a "TV tuner app". It is the
**HDMI/AV input viewer**: the stock "HDMI" button launches it. HDMI was broken
until it was re-enabled on 2026-11-09. **Rule: before disabling a vendor app,
check what launches it** (e.g. trigger the stock UI and watch
`logcat | grep "START u0"`).

### Reverse-engineering notes (read-only, from the device's own APKs with dexdump)

- HDMI: `awlivetv` reads the string extra `input_source`; the stock launcher sends
  `HDMI1`. The last-used input is stored in `Settings.Global input_recovery_record`.
- The input list in the stock launcher comes from a static default, not from
  `/product/etc/config.ini` (that file holds feature flags only).
- `ProjectActivity` (Projection page) is not exported and no broadcast opens it.
  It's only reachable by clicking `rl_project` in `MainSettingActivity`, so
  RoldOS's accessibility service presses it.

## 2026-11-09: Federico's layout

- **Decision (Federico):** top buttons replaced by a single round
  **Close everything** icon (stacked windows with an X; its name appears while
  focused). Rows renamed **All apps** and **Settings**; Settings row = Calibration,
  Change input, Settings, Wi-Fi.
- **Milestone:** every tile has its own design. Apps without a TV banner get one
  generated from their icon: dominant-color gradient, icon, bold name, and a faded
  oversized echo of the icon.
- **Close everything:** `killBackgroundProcesses` on all launchable apps + Play
  Store + input viewer; never LuminaOS or vafocus. Freed 73 MB in testing. Works on
  Android ≤ 13 only.
- **Rename:** "Brisa" → "Federico's Light and Fast AndroidTV OS" → **RoldOS**,
  package `uy.fede.brisa` → `uy.fede.roldos` (favorites reset; old app uninstalled).
- **Gotcha:** uninstalling the old package cleared the enabled accessibility list,
  so the service had to be re-enabled by hand.
- **Milestone:** project moved to `AndroidStudioProjects/RoldOS` and published to
  GitHub.

## 2026-11-09: Settings row grows, package becomes com.roldos

- **Decision (Federico):** package renamed again, `uy.fede.roldos` → **`com.roldos`**
  (Java package `com.roldos` too). Favorites reset again; `uy.fede.roldos` uninstalled.
- **Decision (Federico):** the round icon button became a plain text pill,
  **Close all apps**. Change input became **HDMI** with an HDMI-port icon.
- **Milestone:** the projector's cast apps moved out of All apps into the Settings
  row as branded tiles: **Wireless Cast** (`com.softwinner.miracastReceiver/.Miracast`)
  and **Wired Cast** (`com.toofifi.lineserver/.ui.MainActivity`, USB mirroring).
- **Milestone:** **Original launcher** tile visits the stock home. `HomeKeyService`
  normally bounces the stock home back to RoldOS; the tile sets a flag that allows
  it until the next Home press.
- **Discovery:** opening **Wireless Cast** (Miracast receiver) switches the
  projector's Wi-Fi into direct mode and **drops it off the home network, killing
  the adb connection**. Federico spotted it. Don't test it over wireless adb.
- **Fix:** the focused top button grew past its parent's edge and was clipped
  (`clipChildren` on the top bar).
- **Milestone:** MIT license; README rewritten with screenshots and diagrams.
- **In progress:** logo and icon-family candidates sent to Federico as an artifact
  (logos L1–L6, icon families A–C). The chosen logo goes left of the clock, as the
  app icon and in the README.

## 2026-11-09: The stock menu fights back; Google TV-style moving

- **Discovery (big one):** every time the projector's stock settings menu opens, it
  **wipes the enabled-accessibility list**. That silently killed RoldOS's Home-key
  service, so Calibration couldn't press Projection and Back couldn't be caught.
  Confirmed by watching the setting: stable for 20 s idle, empty 1 s after
  `OPEN_MAIN_SETTINGS`.
- **Fix:** `MainActivity` watches the setting (ContentObserver) and writes the
  service back instantly, using `WRITE_SECURE_SETTINGS` granted once over adb by
  `build.sh install`. The service resumes a pending Calibration when it rebinds,
  and decides "which stock page is showing" from the live screen (`rl_project` =
  menu, `rl_project_mode` = Projection), not from window events it may have missed.
- **Fix:** `build.sh install` used to enable the service too early (Android turned it
  off right after the update finished). It now waits, verifies "Bound services", and
  retries.
- **Decision (Federico):** the **Settings** tile must land strictly on the main
  settings menu, even if the stock menu was left on Projection or a sub-page; the
  service steps back until the menu shows. Calibration steps back too if needed.
- **Decision (Federico):** Settings row = Calibration, Settings, HDMI, Wireless
  Cast, Wired Cast, Original launcher. Wi-Fi tile removed; Settings took its blue.
- **Milestone:** Google TV-style reordering: hold OK on a favorite → **Move** →
  ◀ ▶ slides it (dashed amber outline, lifted) → OK or Back drops it.
- **Milestone:** a tile's name now appears **under that tile**, following the row
  as it scrolls, instead of pinned to the left.
- **Fix:** Wi-Fi and Wired Cast icons looked off on the projector (cramped arcs;
  a cable that crossed everything). Redrawn: evenly spaced arcs, and a clean U-shaped
  cable between a separate phone and screen.
- **Decision (Federico):** logo in the language of his bus app **Tandem**: Outfit
  "RoldOS." (his exact capitalization) in Outfit with a colored period. Dot color candidates W1–W4 sent as an
  artifact; README images use W1 (violet) until he picks.
- **Decision (Federico):** README is promotional: three rendered images
  (`docs/promo/*.html` → `docs/images/*.png` via `render.ps1`), big type, no
  diagrams. The comparison names the stock launcher but not the alternative.
  Stock launcher figure: 147 MB PSS as the active home (measured 2026-11-08).
- **Rule (Federico):** don't test on the projector; he tests himself.

## 2026-11-09: Identity settled

- **Decision (Federico):** logo dot **W2 lamp amber** `#FFB547`; background **B2
  Graphite**, flat `#141516`, replacing the navy-purple gradient he found "AI-looking".
  Candidates (B1–B7, W1–W4) were shown as full simulated home screens in an artifact.
- **Milestone:** "RoldOS." wordmark left of the clock and a new "R." app icon, both
  rendered from `docs/promo/brand.html` by `render-brand.ps1` (Outfit isn't bundled;
  the PNGs are ~10 KB total). README images re-rendered on graphite with amber.
- **Decision (Federico):** Close all apps gives clear feedback: RoldOS's own notice
  pill at the bottom, "Closing apps…" instantly, then "Closed background apps · N MB
  freed" (Android's small grey toast was easy to miss).

## 2026-11-09: Hold-OK menu fixed

- **Bug (Federico):** holding OK on an app stopped opening its menu. Likely cause: since
  the Home-key service filters the remote's keys, OK reaches the app re-injected and
  Android's View long-click can fail to fire.
- **Fix:** RoldOS times the hold itself (`handleHoldOk`): menu after the system long-press
  timeout, or as soon as the remote reports the key as held; the release after a menu
  is swallowed so the app doesn't open. Tiles are no longer View long-clickable.

## 2026-11-09: Own typography, apart from Tandem

- **Decision (Federico):** drop the Tandem look (Outfit + colored period). Six
  typography candidates (T1–T6) were shown in the identity artifact; he chose **T1**:
  **Unbounded Bold**, white "Rold" + amber "OS". App icon is "RO" with an amber O.
- Wordmark and icon re-rendered from `brand.html`; the top-bar logo is now 30 dp tall
  (Unbounded is wider than Outfit, so it keeps about the same width). README images use
  Unbounded for headlines and Roboto for body text; no amber period anywhere.

## Ideas / not done yet

- Show "No signal" or "Connected" on the HDMI tile (TvInputManager state).
- Remember which row and tile you were on when returning from an app.
