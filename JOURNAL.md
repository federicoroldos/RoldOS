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

## 2026-11-09: Frozen after the app menu, and Google TV-style rows

- **Bug (Federico):** in Move mode Left/Right did nothing, and after Home everything looked
  frozen. **Cause (logcat):** every key was dropped with "Dropping event due to no window
  focus": once the hold-OK menu (an Android AlertDialog, a separate window) closed, this
  projector never handed key focus back to RoldOS's window, although the system thought it
  had. **Fix:** the menu is now drawn inside RoldOS's own window (no window switch at all).
  Also: Home ends Move mode and closes the menu, and a move step shifts the neighbor
  instead of pulling out the focused tile (and follows the row as shown, so an uninstalled
  favorite can't throw the order off).
- **Decision (Federico):** the row you're on looks bigger and the others smaller, with nicer
  scrolling. Rows scale 1.05× / 0.8× (dimmed to 45%), and the screen and rows glide with one
  ease-out curve; Android's own jump-to-focus scrolling is switched off.
- **Bug (Federico, photo):** shrunk rows ended at 80% of the screen width, cut off. A row
  only draws inside its own box, and scaling the row scales the box. Fix: rows are laid out
  at screen width / 0.8 (`wideRow`), and the row scroll clamps to the visible width.
- README: removed the "A home screen that gets out of the way." heading and its paragraph.
  GitHub repo description set to "Custom light and fast AndroidTV launcher.". README images
  get a `?v=2` suffix so GitHub's cache serves the new ones.

## 2026-11-09: Noise behind the home, rows that run off screen, Wi-Fi drops

- **Bug (Federico):** pressing Home while an app was still loading (or reloading in the
  background) made it start playing behind the home screen. TizenTube plays through a
  foreground media service, so being sent to the back doesn't stop it, and Android won't
  kill it. **Fix:** an audio guard in `MainActivity`: for 60 s after returning from an app
  RoldOS opened, sound that *starts* while the home shows gets a Pause key, RoldOS takes the
  audio focus, and if it still plays 1.5 s later that app's process is ended. Sound already
  playing when you come back (Spotify) is left alone unless it goes quiet 3 s and restarts.
- **Bug (Federico):** at the end of a long row the focus walked off the right edge. **Cause:**
  rows are laid out at screen / 0.8 wide (so shrunk rows reach the edge), and a scroll box
  can't scroll past (row width − box width): the last ~25 % of every row was unreachable.
  **Fix:** extra end padding on each row equal to the off-screen part of the box.
- **Bug (Federico):** holding Right sometimes jumped to another row. Android's geometric focus
  search escapes the row at its end (and can mis-pick while rows are scaled and gliding).
  **Fix:** Left/Right are routed by hand: next/previous tile in the same row, or stay put.
- **Discovery (Wi-Fi "drops by itself"):** the Wi-Fi log showed both drops that day (04:11 and
  06:25) in the same second **Wireless Cast** (Miracast receiver) opened; signal was excellent
  (−22 dBm). It disables every saved network while open and re-enables them on close. Once the
  first reconnect failed and the projector stayed offline ~3 min until the Wi-Fi page was opened.
  The 06:25 opening came right after the stock launcher was on screen (maybe a mis-press).
- **Milestone (Federico's request):** top bar shows the Wi-Fi network's name and signal (arcs),
  or **No Wi-Fi** in amber. Android hides the name without the location permission;
  `build.sh install` grants `ACCESS_FINE_LOCATION` (location mode was already on). While offline
  and on the home screen, RoldOS asks for a Wi-Fi scan every 25 s so Android reconnects sooner.
- APK now 64 KB.
- **Milestone:** GitHub social preview banner (the card Discord etc. show for the repo link):
  `docs/promo/social.html` → `docs/images/roldos-social.png` (1280×640, via `render.ps1`).
  GitHub has no API for it: upload by hand in the repo's Settings → Social preview.

- **Bug (Federico):** from a row's far right, Down landed on a tile in the middle of the next
  row (geometric search picks whatever sits below on screen). **Decision (Federico):** each row
  remembers the tile you were last on; Up/Down take you back there, or to the first tile if you
  never moved along that row. Up/Down are now routed by hand (`visibleRows` / `focusRow`). Home
  resets every row to its start and clears the memory.
- Social preview: the two stat pills were removed (Federico): wordmark, tagline and tiles only.

## 2026-11-09: Now playing row, gliding favorites

- **Milestone (Federico's request):** a **Now playing** row above Favorites, only while some
  app plays (or paused in the last 10 minutes): cover art, title, "artist · app", and
  previous / play-pause (amber) / next buttons. OK on the card opens the app on its player.
  Up from the row reaches Close all apps. Prev/next hide when the player doesn't offer them.
- **How:** Android shows other apps' media sessions only to an approved notification
  listener. `MediaAccess` is an empty listener (reads no notifications) approved by
  `build.sh install` with `cmd notification allow_listener`. `NowPlaying` follows the sessions
  only while the home is resumed. Without the approval it falls back to "Something is
  playing" (`isMusicActive`) and media keys.
- **Discovery:** the projector reports `ro.config.low_ram=true`. Notification listeners still
  run (the system's own Assistant is bound), but whether low-RAM blocks third-party approval
  is untested: check `dumpsys notification | grep -A3 "Allowed notification listeners"` after
  the next install. Before any player was active, Spotify's session sat at state NONE with no
  metadata (correctly hidden). Unknown yet whether the stock settings menu also wipes the
  approved listeners the way it wipes accessibility.
- Pressing any Now playing control disarms the audio guard, so resuming a paused app from the
  home doesn't get it paused/killed again.
- **Federico's request:** moving a favorite now glides (240 ms, same ease curve) instead of
  jumping: both swapped tiles are offset back to where they were drawn and slide into place.
- APK now 72 KB. Memory not measured yet (needs an install).
- Installed: the projector **approved** the listener despite `low_ram=true` (MediaAccess is
  bound, Now playing gets real titles and covers).
- **Federico:** no captions under the Now playing card or its buttons. New **X** button at the
  row's end: stops the player, hides the row at once (that app stays hidden until it plays
  again by itself, after 3 s), and 1.2 s later ends the app with `killBackgroundProcesses`
  (works once the player has dropped its foreground service; untested per app).

## 2026-11-08: Google TV look: colored background, glow, Google Sans, big row names

- **Federico's request** (with photos of Google TV): the background takes the focused tile's
  color, a soft light glows behind that tile, the whole interface moves as smoothly as
  Google TV (60 fps if possible), row names bigger, and Google's font. This **reverses** the
  earlier "flat graphite, no gradients" rule (the old gradient looked "AI-generated"; this
  one follows the tile, which is what he wants).
- **Ambient** (`Ambient.java`) is the window background: graphite tinted toward the tile's
  main color, a broad lighter wash from the lower right, and an elliptical radial glow
  centered on the focused tile. Color and glow fade/slide over 450 ms (same ease curve).
  The glow follows its tile every frame (pre-draw listener) while rows glide and zoom.
  Gray/white art gives a dim neutral, not a gray screen. No glow on the top-bar button.
- Tile colors: `TileArt.dominantColor` of each finished tile bitmap (on the loader thread),
  settings tiles too, and the Now playing cover for the media row.
- **Smoothness:** sections now report `hasOverlappingRendering() = false`. Dimmed rows were
  drawn into an off-screen copy on every frame (alpha on a whole row); now each tile is drawn
  straight at reduced opacity. Not measured on the device yet (`dumpsys gfxinfo com.roldos`
  gives janky-frame counts without touching the screen).
- **Font:** Google Sans Flex (Google released it under the SIL OFL in 2025), regular + medium,
  fetched from Google Fonts cut to Latin (`text=` parameter): ~60 KB each, stored
  uncompressed in `assets/fonts` (license alongside). Other alphabets fall back to the system.
- **Build gotcha:** `aapt2 link -A` on Windows writes asset paths with backslashes
  (`assets/fonts\X.ttf`), so Android can't open them. `build.sh` adds assets with `jar u0f`.
- Row names: 26 sp, regular weight (were 17 sp medium).
- **Banners for apps without one** (Federico: find real ones online, don't draw them; system
  apps keep RoldOS's art). Only two user apps lack a banner:
  - Netflix (`com.netflix.mediaclient`, the phone app): the real banner from Netflix's TV app
    (`com.netflix.ninja` 10.0.6 from Aptoide's "appstvcommunity" store, `res/yT.png`, 320x180).
    Aptoide's top result (v899.9999, "ganesapps") was a fake "Welcome" stub: check badging.
  - Send Files To TV: its Google Play feature graphic (1024x500), center-cropped to 16:9
    around the logo and scaled to 320x180.
  They live in `assets/banners/<package>.png`, git-ignored (other brands' art in a public
  repo). A fresh clone simply falls back to the drawn banners.
- **Federico:** the X must really stop the music (Spotify kept playing). Now it sends pause,
  then stop, and RoldOS takes the audio focus (players pause for good when they lose it;
  given back when leaving the home). Then the app is ended as before.
- **Federico:** when the Now playing row goes away, it fades out (160 ms) and the rows below
  glide up into its place (`glideRows`, 360 ms); when it appears they glide down.
- TizenTube holds `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, so it *can* keep sounding behind the
  home (that's the old "plays behind the home" bug). Whether it does on purpose when you
  press Home mid-video is untested. It had no media session at the time.
- APK 216 KB (fonts are most of it). Memory not measured yet.
- **Federico, same day, after trying it:** the tint was too aggressive (it colored the whole
  screen), changed too abruptly and looked flat. Reworked `Ambient`: the background stays
  **graphite**; only the area around the focused tile gets a wide, very soft spot of the
  tile's color (bell-shaped fall-off, peak ~34% strength, the color's own saturation). Moving
  to another tile cross-fades: the old spot fades out where it was (900 ms) while the new one
  fades in around the new tile (750 ms), so no muddy in-between color and no sudden switch.
- On the Now playing row the light sits **only behind the card**, never behind the round
  buttons.
- **Google Sans Flex dropped:** it clashed with the Unbounded wordmark. Back to Roboto while
  Federico picks from a comparison page (Unbounded light, Lexend, Outfit, Sora, Red Hat
  Display, Urbanist, Manrope, Figtree). `Fonts` is the single place to switch.
- **Close all apps** now also silences every player (pause + stop, audio focus) and hides the
  Now playing row, then ends the players again 1.2 s later (once paused they're no longer
  foreground, so the kill sticks).
- APK back to 92 KB.
- **Federico:** Home pressed while RoldOS is already showing now glides back to the start
  (560 ms; the screen rises while each scrolled row slides home, 60 ms apart from the top)
  instead of jumping. Coming back from an app still resets instantly (nothing to see).
  "Already showing" = no onStop since the last onResume (`stopped`).
- **Font chosen: Unbounded** (Federico, from the comparison page), the wordmark's own family:
  Light for text, Regular where RoldOS used medium. Latin subset, ~49 KB each; APK 196 KB.
- **Measured the glow version** (`dumpsys gfxinfo`, Federico's own use, 1152 frames): 99%
  janky, median 38 ms per frame (~26 fps), 90th percentile 57 ms; many "slow issue draw
  commands" and "slow UI thread". Federico: "me laguea todo el proyector".
- **Federico's decision:** no two versions (a "Lite" build flag was started and scrapped) and
  no transparency, glow or layers at all: back to the original idea, ultra-fast and light.
  Removed `Ambient` (flat graphite again), the dimming of idle rows (alpha 0.45), the
  non-overlapping-rendering workaround it needed, the Now playing fade (the row just goes,
  rows below still glide up), the notice pill's fade and translucency, and the see-through
  menu overlay (now opaque). Kept: row zoom, tile zoom, glides, Unbounded.
- **Frame-rate fix:** rows had `setClipChildren(false)`, so every tile's node was drawn every
  frame, including the many off screen (rows are wider than the screen). Rows now clip their
  tiles, letting the renderer skip off-screen ones. The move lift went 8 → 6 dp to stay inside
  the tile margin. Not measured yet: `dumpsys gfxinfo com.roldos` after Federico uses it.
- **Measured after the fix** (Federico's use, 1586 frames): median **13 ms** (was 38), 90th
  percentile 25 ms (was 57), 99th 34 ms. Slow UI thread, slow draw commands and missed vsyncs
  all went to 0 (were 341 / 569 / 293). Still 41% of frames over 16 ms, mostly during glides.
  Federico: "ya va a 60".
- **Bug (Federico):** right after a reinstall the Now playing row showed Spotify again although
  he had closed it with X. Cause: X pauses Spotify (its paused session stays), and the "closed"
  list lived only in memory, so the restarted RoldOS saw a session paused < 10 min ago. Now the
  list is saved (prefs `np_dismissed`, package@elapsedRealtime) and an app comes back only when
  it plays again or is used in the player itself after the X (its position time moves past the
  X's own pause + 3 s).

## 2026-11-08: what runs in the background (survey, nothing changed)

- **Close all apps** is `killBackgroundProcesses`: the same kind of close Android does when
  memory runs low, not "Force stop" (that one is reserved for system apps). Apps with services
  can restart themselves. Most vendor apps run as uid 1000 (`android.uid.system`).
- System animations (window / transition scale) were already **0** on the projector; the
  animator scale is untouched, so RoldOS's glides are unaffected.
- **Measurement** (Spotify open, RoldOS home): 270 MB available of 960. PSS: Spotify 143,
  system 135, LuminaOS 92, RoldOS 73, Google services ~85 in three processes, StoreOS 25,
  keyboard 24, lineserver 24, closedialog 20, hisilicon.explorer 18, awtvinputservice 18,
  screensaversdk 16, toofifi 15 (+ com.huawei.connection 5), vafocus 14, mphotool dlna 11 /
  airplay 5 / usbcastserver 6, rockchip update service 5.
- **Discovery: `com.hotack.screensaversdk` is an ad app.** Runs as the system user, starts at
  boot, holds INSTALL_PACKAGES and draw-over-screen, carries the Zeasn ad SDK (video ad player
  activities, `media.tv.intent.action.APP_AD`), SensorsData analytics, downloadable plugins
  (`zeasn.dynamic.plugin`) and its own (not enabled) accessibility service. Installed in /data on
  2026-08-30 (installer null), updated itself on 2026-11-09. No other vendor app references it,
  but LuminaOS embeds the same Zeasn SDK.
- **Must keep:** `com.htc.closedialog` is not just a dialog: it hosts `ManualFocusService`,
  `RemoteControlService`, fan control (`android.htc.fanctrl`) and voice. `com.android.toofifi`
  (FeituHtc) is the casting hub (Huawei HiSight, mphotool PIN code, licence check); unclear
  whether Wired Cast needs it.
- **Decision (Federico): disabled** (`pm disable-user --user 0`) `com.hotack.screensaversdk`,
  `com.htc.storeos`, `android.rockchip.update.service`, `com.mphotool.airplay`. Checked first:
  LuminaOS only opens the store on a click (store tile, app details, export dialog), never at
  start; nothing references the ad app or the USB updater; the Miracast receiver references
  none of them. No crashes afterwards; LuminaOS, closedialog and vafocus still running.
  Undo: `pm enable <package>`.
- **Kept on purpose:** `com.mphotool.usbcastserver` (iPhone cable cast): **Wired Cast
  (`com.toofifi.lineserver`) binds its `CastService`**, so disabling it could break Wired Cast.
  DLNA kept too (not asked).
- **Why Spotify survived Close all apps:** paused, Spotify keeps its foreground media service
  (oom adj 200, "perceptible"), and `killBackgroundProcesses` skips such processes. Spotify
  also restarts itself through that service and through WorkManager jobs.
- **Decision (Federico):** the two downloaded banners (Netflix, Send Files to TV) now live in
  the repo; the `.gitignore` rule is gone.

## Ideas / not done yet

- Show "No signal" or "Connected" on the HDMI tile (TvInputManager state).
- Remember which row and tile you were on when returning from an app.
