# RoldOS journal

Memories, milestones, discoveries and decisions, newest at the bottom.
Written by Claude Code while working with the owner (the project's author); keep appending.

---

## 2026-11-08: Groundwork on the Chromecast

- **Milestone:** paired the PC with the Chromecast with Google TV over wireless
  debugging (TLS pairing code), so apps can be installed and debugged remotely.
- **Discovery:** the Chromecast runs 32-bit Android 14 on a 1080p, no-HDR TV;
  hardware decoders for VP9/HEVC/AVC, AV1 only in software.
- **Decision:** light debloat (`pm uninstall -k --user 0`, reversible with
  `cmd package install-existing`): TalkBack, TTS, Play Games, Play Movies,
  feedback, calendar sync, old launcher. Voice search and screensaver kept on
  the owner's request. Animations at 0.5×.
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
  the screen and start at boot. Typical ad software. **The owner chose to leave it**,
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

## 2026-11-09: The owner's layout

- **Decision (owner):** top buttons replaced by a single round
  **Close everything** icon (stacked windows with an X; its name appears while
  focused). Rows renamed **All apps** and **Settings**; Settings row = Calibration,
  Change input, Settings, Wi-Fi.
- **Milestone:** every tile has its own design. Apps without a TV banner get one
  generated from their icon: dominant-color gradient, icon, bold name, and a faded
  oversized echo of the icon.
- **Close everything:** `killBackgroundProcesses` on all launchable apps + Play
  Store + input viewer; never LuminaOS or vafocus. Freed 73 MB in testing. Works on
  Android ≤ 13 only.
- **Rename:** "Brisa" → a longer descriptive name → **RoldOS**,
  package renamed within a personal namespace (favorites reset; old app uninstalled).
- **Gotcha:** uninstalling the old package cleared the enabled accessibility list,
  so the service had to be re-enabled by hand.
- **Milestone:** project moved to `AndroidStudioProjects/RoldOS` and published to
  GitHub.

## 2026-11-09: Settings row grows, package becomes com.roldos

- **Decision (owner):** package renamed again, from the personal namespace to **`com.roldos`**
  (Java package `com.roldos` too). Favorites reset again; the old package uninstalled.
- **Decision (owner):** the round icon button became a plain text pill,
  **Close all apps**. Change input became **HDMI** with an HDMI-port icon.
- **Milestone:** the projector's cast apps moved out of All apps into the Settings
  row as branded tiles: **Wireless Cast** (`com.softwinner.miracastReceiver/.Miracast`)
  and **Wired Cast** (`com.toofifi.lineserver/.ui.MainActivity`, USB mirroring).
- **Milestone:** **Original launcher** tile visits the stock home. `HomeKeyService`
  normally bounces the stock home back to RoldOS; the tile sets a flag that allows
  it until the next Home press.
- **Discovery:** opening **Wireless Cast** (Miracast receiver) switches the
  projector's Wi-Fi into direct mode and **drops it off the home network, killing
  the adb connection**. The owner spotted it. Don't test it over wireless adb.
- **Fix:** the focused top button grew past its parent's edge and was clipped
  (`clipChildren` on the top bar).
- **Milestone:** MIT license; README rewritten with screenshots and diagrams.
- **In progress:** logo and icon-family candidates sent to the owner as an artifact
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
- **Decision (owner):** the **Settings** tile must land strictly on the main
  settings menu, even if the stock menu was left on Projection or a sub-page; the
  service steps back until the menu shows. Calibration steps back too if needed.
- **Decision (owner):** Settings row = Calibration, Settings, HDMI, Wireless
  Cast, Wired Cast, Original launcher. Wi-Fi tile removed; Settings took its blue.
- **Milestone:** Google TV-style reordering: hold OK on a favorite → **Move** →
  ◀ ▶ slides it (dashed amber outline, lifted) → OK or Back drops it.
- **Milestone:** a tile's name now appears **under that tile**, following the row
  as it scrolls, instead of pinned to the left.
- **Fix:** Wi-Fi and Wired Cast icons looked off on the projector (cramped arcs;
  a cable that crossed everything). Redrawn: evenly spaced arcs, and a clean U-shaped
  cable between a separate phone and screen.
- **Decision (owner):** logo in the style of another project of the owner's:
  "RoldOS." (the owner's exact capitalization) in Outfit with a colored period. Dot color candidates W1–W4 sent as an
  artifact; README images use W1 (violet) until the owner picks.
- **Decision (owner):** README is promotional: three rendered images
  (`docs/promo/*.html` → `docs/images/*.png` via `render.ps1`), big type, no
  diagrams. The comparison names the stock launcher but not the alternative.
  Stock launcher figure: 147 MB PSS as the active home (measured 2026-11-08).
- **Rule (owner):** don't test on the owner's projector; they test it themselves.

## 2026-11-09: Identity settled

- **Decision (owner):** logo dot **W2 lamp amber** `#FFB547`; background **B2
  Graphite**, flat `#141516`, replacing the navy-purple gradient the owner found "AI-looking".
  Candidates (B1–B7, W1–W4) were shown as full simulated home screens in an artifact.
- **Milestone:** "RoldOS." wordmark left of the clock and a new "R." app icon, both
  rendered from `docs/promo/brand.html` by `render-brand.ps1` (Outfit isn't bundled;
  the PNGs are ~10 KB total). README images re-rendered on graphite with amber.
- **Decision (owner):** Close all apps gives clear feedback: RoldOS's own notice
  pill at the bottom, "Closing apps…" instantly, then "Closed background apps · N MB
  freed" (Android's small grey toast was easy to miss).

## 2026-11-09: Hold-OK menu fixed

- **Bug (owner):** holding OK on an app stopped opening its menu. Likely cause: since
  the Home-key service filters the remote's keys, OK reaches the app re-injected and
  Android's View long-click can fail to fire.
- **Fix:** RoldOS times the hold itself (`handleHoldOk`): menu after the system long-press
  timeout, or as soon as the remote reports the key as held; the release after a menu
  is swallowed so the app doesn't open. Tiles are no longer View long-clickable.

## 2026-11-09: Own typography, a look of its own

- **Decision (owner):** drop the borrowed look (Outfit + colored period). Six
  typography candidates (T1–T6) were shown in the identity artifact; the owner chose **T1**:
  **Unbounded Bold**, white "Rold" + amber "OS". App icon is "RO" with an amber O.
- Wordmark and icon re-rendered from `brand.html`; the top-bar logo is now 30 dp tall
  (Unbounded is wider than Outfit, so it keeps about the same width). README images use
  Unbounded for headlines and Roboto for body text; no amber period anywhere.

## 2026-11-09: Frozen after the app menu, and Google TV-style rows

- **Bug (owner):** in Move mode Left/Right did nothing, and after Home everything looked
  frozen. **Cause (logcat):** every key was dropped with "Dropping event due to no window
  focus": once the hold-OK menu (an Android AlertDialog, a separate window) closed, this
  projector never handed key focus back to RoldOS's window, although the system thought it
  had. **Fix:** the menu is now drawn inside RoldOS's own window (no window switch at all).
  Also: Home ends Move mode and closes the menu, and a move step shifts the neighbor
  instead of pulling out the focused tile (and follows the row as shown, so an uninstalled
  favorite can't throw the order off).
- **Decision (owner):** the row you're on looks bigger and the others smaller, with nicer
  scrolling. Rows scale 1.05× / 0.8× (dimmed to 45%), and the screen and rows glide with one
  ease-out curve; Android's own jump-to-focus scrolling is switched off.
- **Bug (owner, photo):** shrunk rows ended at 80% of the screen width, cut off. A row
  only draws inside its own box, and scaling the row scales the box. Fix: rows are laid out
  at screen width / 0.8 (`wideRow`), and the row scroll clamps to the visible width.
- README: removed the "A home screen that gets out of the way." heading and its paragraph.
  GitHub repo description set to "Custom light and fast AndroidTV launcher.". README images
  get a `?v=2` suffix so GitHub's cache serves the new ones.

## 2026-11-09: Noise behind the home, rows that run off screen, Wi-Fi drops

- **Bug (owner):** pressing Home while an app was still loading (or reloading in the
  background) made it start playing behind the home screen. TizenTube plays through a
  foreground media service, so being sent to the back doesn't stop it, and Android won't
  kill it. **Fix:** an audio guard in `MainActivity`: for 60 s after returning from an app
  RoldOS opened, sound that *starts* while the home shows gets a Pause key, RoldOS takes the
  audio focus, and if it still plays 1.5 s later that app's process is ended. Sound already
  playing when you come back (Spotify) is left alone unless it goes quiet 3 s and restarts.
- **Bug (owner):** at the end of a long row the focus walked off the right edge. **Cause:**
  rows are laid out at screen / 0.8 wide (so shrunk rows reach the edge), and a scroll box
  can't scroll past (row width − box width): the last ~25 % of every row was unreachable.
  **Fix:** extra end padding on each row equal to the off-screen part of the box.
- **Bug (owner):** holding Right sometimes jumped to another row. Android's geometric focus
  search escapes the row at its end (and can mis-pick while rows are scaled and gliding).
  **Fix:** Left/Right are routed by hand: next/previous tile in the same row, or stay put.
- **Discovery (Wi-Fi "drops by itself"):** the Wi-Fi log showed both drops that day,
  each in the same second **Wireless Cast** (Miracast receiver) opened; signal was excellent
  (−22 dBm). It disables every saved network while open and re-enables them on close. Once the
  first reconnect failed and the projector stayed offline ~3 min until the Wi-Fi page was opened.
  The second opening came right after the stock launcher was on screen (maybe a mis-press).
- **Milestone (owner's request):** top bar shows the Wi-Fi network's name and signal (arcs),
  or **No Wi-Fi** in amber. Android hides the name without the location permission;
  `build.sh install` grants `ACCESS_FINE_LOCATION` (location mode was already on). While offline
  and on the home screen, RoldOS asks for a Wi-Fi scan every 25 s so Android reconnects sooner.
- APK now 64 KB.
- **Milestone:** GitHub social preview banner (the card Discord etc. show for the repo link):
  `docs/promo/social.html` → `docs/images/roldos-social.png` (1280×640, via `render.ps1`).
  GitHub has no API for it: upload by hand in the repo's Settings → Social preview.

- **Bug (owner):** from a row's far right, Down landed on a tile in the middle of the next
  row (geometric search picks whatever sits below on screen). **Decision (owner):** each row
  remembers the tile you were last on; Up/Down take you back there, or to the first tile if you
  never moved along that row. Up/Down are now routed by hand (`visibleRows` / `focusRow`). Home
  resets every row to its start and clears the memory.
- Social preview: the two stat pills were removed (owner): wordmark, tagline and tiles only.

## 2026-11-09: Now playing row, gliding favorites

- **Milestone (owner's request):** a **Now playing** row above Favorites, only while some
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
- **Owner's request:** moving a favorite now glides (240 ms, same ease curve) instead of
  jumping: both swapped tiles are offset back to where they were drawn and slide into place.
- APK now 72 KB. Memory not measured yet (needs an install).
- Installed: the projector **approved** the listener despite `low_ram=true` (MediaAccess is
  bound, Now playing gets real titles and covers).
- **Owner:** no captions under the Now playing card or its buttons. New **X** button at the
  row's end: stops the player, hides the row at once (that app stays hidden until it plays
  again by itself, after 3 s), and 1.2 s later ends the app with `killBackgroundProcesses`
  (works once the player has dropped its foreground service; untested per app).

## 2026-11-08: Google TV look: colored background, glow, Google Sans, big row names

- **Owner's request** (with photos of Google TV): the background takes the focused tile's
  color, a soft light glows behind that tile, the whole interface moves as smoothly as
  Google TV (60 fps if possible), row names bigger, and Google's font. This **reverses** the
  earlier "flat graphite, no gradients" rule (the old gradient looked "AI-generated"; this
  one follows the tile, which is what the owner wanted).
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
- **Banners for apps without one** (owner: find real ones online, don't draw them; system
  apps keep RoldOS's art). Only two user apps lack a banner:
  - Netflix (`com.netflix.mediaclient`, the phone app): the real banner from Netflix's TV app
    (`com.netflix.ninja` 10.0.6 from Aptoide's "appstvcommunity" store, `res/yT.png`, 320x180).
    Aptoide's top result (v899.9999, "ganesapps") was a fake "Welcome" stub: check badging.
  - Send Files To TV: its Google Play feature graphic (1024x500), center-cropped to 16:9
    around the logo and scaled to 320x180.
  They live in `assets/banners/<package>.png`, git-ignored (other brands' art in a public
  repo). A fresh clone simply falls back to the drawn banners.
- **Owner:** the X must really stop the music (Spotify kept playing). Now it sends pause,
  then stop, and RoldOS takes the audio focus (players pause for good when they lose it;
  given back when leaving the home). Then the app is ended as before.
- **Owner:** when the Now playing row goes away, it fades out (160 ms) and the rows below
  glide up into its place (`glideRows`, 360 ms); when it appears they glide down.
- TizenTube holds `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, so it *can* keep sounding behind the
  home (that's the old "plays behind the home" bug). Whether it does on purpose when you
  press Home mid-video is untested. It had no media session at the time.
- APK 216 KB (fonts are most of it). Memory not measured yet.
- **Owner, same day, after trying it:** the tint was too aggressive (it colored the whole
  screen), changed too abruptly and looked flat. Reworked `Ambient`: the background stays
  **graphite**; only the area around the focused tile gets a wide, very soft spot of the
  tile's color (bell-shaped fall-off, peak ~34% strength, the color's own saturation). Moving
  to another tile cross-fades: the old spot fades out where it was (900 ms) while the new one
  fades in around the new tile (750 ms), so no muddy in-between color and no sudden switch.
- On the Now playing row the light sits **only behind the card**, never behind the round
  buttons.
- **Google Sans Flex dropped:** it clashed with the Unbounded wordmark. Back to Roboto while
  the owner picks from a comparison page (Unbounded light, Lexend, Outfit, Sora, Red Hat
  Display, Urbanist, Manrope, Figtree). `Fonts` is the single place to switch.
- **Close all apps** now also silences every player (pause + stop, audio focus) and hides the
  Now playing row, then ends the players again 1.2 s later (once paused they're no longer
  foreground, so the kill sticks).
- APK back to 92 KB.
- **Owner:** Home pressed while RoldOS is already showing now glides back to the start
  (560 ms; the screen rises while each scrolled row slides home, 60 ms apart from the top)
  instead of jumping. Coming back from an app still resets instantly (nothing to see).
  "Already showing" = no onStop since the last onResume (`stopped`).
- **Font chosen: Unbounded** (owner, from the comparison page), the wordmark's own family:
  Light for text, Regular where RoldOS used medium. Latin subset, ~49 KB each; APK 196 KB.
- **Measured the glow version** (`dumpsys gfxinfo`, the owner's own use, 1152 frames): 99%
  janky, median 38 ms per frame (~26 fps), 90th percentile 57 ms; many "slow issue draw
  commands" and "slow UI thread". The owner: "the whole projector lags".
- **Owner's decision:** no two versions (a "Lite" build flag was started and scrapped) and
  no transparency, glow or layers at all: back to the original idea, ultra-fast and light.
  Removed `Ambient` (flat graphite again), the dimming of idle rows (alpha 0.45), the
  non-overlapping-rendering workaround it needed, the Now playing fade (the row just goes,
  rows below still glide up), the notice pill's fade and translucency, and the see-through
  menu overlay (now opaque). Kept: row zoom, tile zoom, glides, Unbounded.
- **Frame-rate fix:** rows had `setClipChildren(false)`, so every tile's node was drawn every
  frame, including the many off screen (rows are wider than the screen). Rows now clip their
  tiles, letting the renderer skip off-screen ones. The move lift went 8 → 6 dp to stay inside
  the tile margin. Not measured yet: `dumpsys gfxinfo com.roldos` after the owner uses it.
- **Measured after the fix** (the owner's use, 1586 frames): median **13 ms** (was 38), 90th
  percentile 25 ms (was 57), 99th 34 ms. Slow UI thread, slow draw commands and missed vsyncs
  all went to 0 (were 341 / 569 / 293). Still 41% of frames over 16 ms, mostly during glides.
  The owner: "it runs at 60 now".
- **Bug (owner):** right after a reinstall the Now playing row showed Spotify again although
  the owner had closed it with X. Cause: X pauses Spotify (its paused session stays), and the "closed"
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
- **Decision (owner): disabled** (`pm disable-user --user 0`) `com.hotack.screensaversdk`,
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
- **Decision (owner):** the two downloaded banners (Netflix, Send Files to TV) now live in
  the repo; the `.gitignore` rule is gone.

## 2026-11-08: Close all apps closes for real (force-stop through local adb)

- **Decision (owner):** Close all apps must fully close YouTube, Netflix, Spotify, Disney+,
  Prime… `FORCE_STOP_PACKAGES` can't be granted (`pm grant` refuses), so RoldOS now speaks the
  adb protocol to the projector's own adbd on `127.0.0.1:5555` (`LocalAdb`, framework only:
  RSA key in its private files, `cmd activity force-stop` per package). Needs `INTERNET`.
- First use shows Android's "Allow USB debugging?" for the key `RoldOS@projector`; the owner
  accepted with "always allow" (logcat: `alwaysAllow = true`). If adbd is off (it's
  `service.adb.tcp.port`, set by the firmware at boot), only the soft close runs.
- **Measured:** 14 packages force-stopped in ~0.75 s (Spotify, Disney+, Netflix, Prime,
  TizenTube…); available memory 312 → 354 MB.
- **Safety rule added after the first run:** packages running as the system user (uid 1000:
  htc samescreen, clearmemory, OTA updater, hisilicon explorer, music) and Play Store get only
  the soft close. The first run had force-stopped those; harmless (they're just launcher
  screens), but a stopped app misses broadcasts until opened.
- **Mistake worth remembering:** the first key was saved as PKCS#8 only; read back, Conscrypt
  returns `OpenSSLRSAPrivateKey` (not CRT), the cast failed, and RoldOS silently made a *new*
  key on the next run, so the approved one was lost after the first update. Now both halves
  are saved (`adbkey2` + `adbkey2.pub`) and the old file deleted; the owner approved again.
  The old `RoldOS@projector` entry stays in adb_keys, harmless.
- adb approvals expire after 7 days unused by default: set `adb_allowed_connection_time` to 0
  (never); `MainActivity.ensureHomeService` re-asserts it.
- **RoldOS comes back by itself after an update or reboot:** when `HomeKeyService` binds, the
  stock home may already be on screen (no window event to bounce on). It now asks through
  `LocalAdb` (`dumpsys activity activities | grep -m1 mResumedActivity`, never prompting) and
  goes home if the stock `MainActivity` is resumed. Verified: after `build.sh install` RoldOS
  was back in ~6 s with no prompt. Reboot not tested yet.
- Unknown: what turns on network adb at boot (`service.adb.tcp.port=5555` is not persistent and
  no init file or vendor app found sets it). It has been on every time.
- **Reboot checked (owner):** boots straight into RoldOS. Logcat: at boot Android itself
  starts `com.roldos/.MainActivity` as HOME (`from uid 0`), so the default-home setting *is*
  honored at boot (only the Home key is hard-wired to the stock launcher). Network adb was back
  on port 5555 within a minute of boot, and RoldOS's key was accepted with no prompt.
- **Owner, 2026-11-09:** "it runs super fast now". Confirms the ultra-light direction (flat
  graphite, no transparency/glow, motion only moves and zooms) plus Close all apps freeing
  real memory. Keep it that way: any new visual must pass the same rule.
- **Checked (26 min after a reboot, read-only):** LuminaOS has **no process at all** (it used
  ~92 MB when it was the home). It is not in the stopped state (`stopped=false`), so the
  Settings/Calibration broadcasts still wake it on demand. RoldOS 62 MB PSS; 421 MB available
  (was 270–312 MB in earlier measurements).
- Tried a README image with those numbers ("420 MB free of 960", "0 MB stock launcher in the
  background", a RAM bar). **The owner didn't like it**: removed. They asked for candidate
  section-style images instead (mockups shown in an artifact).

## 2026-11-09: A recipe to replicate the setup

- **Milestone (owner's request):** `SETUP.md`, a step-by-step guide written for an AI agent
  (or a person) to take a stock projector to the owner's state: connect, survey, must-keep list,
  debloat table (with why), apps and their sources, `build.sh install`, checks, undo.
- **Read from the device for it (read only):** disabled packages are the eight in SETUP.md plus
  `com.android.mtp`, `com.android.nfc`, `com.android.gallery3d`, which the journal never
  recorded disabling (probably factory). Projectivy is no longer installed. Nothing was
  uninstalled for the user.
- **Owner:** SETUP.md also teaches the *method* (section 4), so an agent can clean any
  Android TV device with the same care, not just copy our list: read `dumpsys package`, look
  inside the APK (manifest, code names, servers), the signs of ads vs. important hardware apps,
  who else uses it, three lists with the user, disable one at a time and test. The code-name
  search was tried on `com.hotack.screensaversdk` (zeasn.tv servers, com.sensorsdata).
- Reminder: RoldOS replicates nothing by itself. Favorites live in each device's prefs (seeded
  from `DEFAULT_FAVS` if installed), and the debloat was done by hand over adb.

## 2026-11-09: The docs become general

- **Decision (owner):** the public docs serve any user, not one person. `CLAUDE.md` now talks
  about "the user" (their language, plain words, ask before driving a device) and points to
  `SETUP.md`. The owner's own preferences, device addresses and personal details moved to a
  git-ignored `CLAUDE.local.md`; nothing was dropped, only moved. The journal says "the owner".
- `AGENTS.md` points agents other than Claude to `CLAUDE.md` and `SETUP.md`.
- README and SETUP.md give the one-line way in: clone, open your AI agent in the folder, say
  "set up my Android TV device following SETUP.md".
- Earlier versions of these docs (with personal details) remain in git history.

## 2026-11-09: README as sections

- **The owner's picks** from candidate mockups: keep the memory comparison, add app size, a
  real home screenshot and the Now playing, Close all apps and Move sections. Rejected:
  open-time comparison, "closes in 0.75 s" big numbers, frame time (only measured on RoldOS),
  generated banners, "always lands home", "no ads". Headlines are short, with no subtitle
  (the owner's wording: "Fast access to all your apps", "Now playing widget on your
  homescreen", "Close all apps at once", "Configure your homescreen to your specific liking").
- Two section styles in `promo.css`: `.sec` (headline left, bars right, 1600×720) for
  `memory` and `size`; `.stack` (headline on top, real screenshot or crops below) for `home`,
  `playing`, `close`, `move`. All → `docs/images/roldos-<name>.png` via `render.ps1`
  (needs `--allow-file-access-from-files` for the screenshots). The old `roldos-stats-2.png`
  (with the 0.35 s open time) left the README. A first round drew the UI by hand; the owner
  wanted real screenshots instead.
- **App sizes:** stock `LuminaOS.apk` 34,208,374 bytes (read on the projector); RoldOS
  `base.apk` 197,645 bytes (193 KB); the popular alternative isn't installed, 13.7 MB is its
  latest release as listed on APKPure (2026-11-09).
- **Screenshots** (the owner asked for them): `docs/promo/shots/`, 1920×1080, cropped by
  `shots.js`. The top bar shows the Wi-Fi network's real name: it is **erased from the source
  PNGs** (filled with graphite) and `shotCrop(..., {hideWifi})` redraws the arcs + "Home"
  anchored right, 47 px before Close all apps, at the same height, like the app lays it out.
  Never commit a screenshot that still shows the network name. Now playing: Spotify started
  with `monkey` + `KEYCODE_MEDIA_PLAY` (`cmd media_session dispatch play` hit the Bluetooth
  session instead). Up from the Now playing card stays on the card the first time; a second
  Up reaches Close all apps. Close all apps with Netflix, Disney+, Prime Video, TizenTube and
  Spotify open: **146 MB freed** (available 234 → 402 MB); the notice shows ~1.5 s after OK.
  Move: hold OK (`input keyevent --longpress KEYCODE_DPAD_CENTER`) → Down → OK → Right;
  then Left + OK put TizenTube back first (checked on screen).
- **Free memory, stock home vs RoldOS home** (each measured 5 min after a reboot with that
  launcher as home, 3 readings 15 s apart): RoldOS 394–404 MB available (RoldOS 48 MB PSS,
  LuminaOS not running); stock 385–395 MB (LuminaOS 85 MB PSS; RoldOS's process still starts
  for its notification listener, 5 MB). Only ~8 MB apart: `MemAvailable` moves with caches
  and services, so it's not a fair way to show the launchers' difference. The "more room for
  your apps" image was **dropped**; the memory-on-screen comparison already shows it.
- **Switching the home over adb:** `cmd package set-home-activity <component>` is saved a few
  seconds later; rebooting right away lost it once (booted back into the old home). Wait
  ~20 s before rebooting. With the stock home, first `am force-stop com.roldos` and clear
  `enabled_accessibility_services`, or `HomeKeyService` bounces back to RoldOS at boot (and
  `MainActivity`'s observer writes the service back while it runs). Restored afterwards:
  RoldOS home, service bound, LuminaOS asleep after a last reboot.

## 2026-11-09: YouTube instead of TizenTube in the README

- **Owner:** the README screenshots should show YouTube, not TizenTube (it looks nicer). The
  shots had TizenTube as the first favorite; retaking them wasn't possible (projector offline,
  and the owner tests on it himself), so `docs/promo/youtube.ps1` edits copies of them:
  YouTube's real Android TV banner on the tile, and "YouTube" where the name was (under the
  tile, and the hold-OK menu's title), in the same Unbounded weight and size (stems measured
  equal). The originals stay in `shots/`; the pages use `shots/*-youtube.png`.
- Banner source: `com.google.android.youtube.tv` 5.30.304 from Aptoide's "appstv" store
  (signer `CN=ytandroidtv, O=Google Inc.`), `res/dN.png` (drawable `app_banner`, 320x180).
- How the border survives: near the tile's edge each pixel is treated as "old tile + some
  border"; the old tile comes from the unfocused one in `closed.png`, scaled. The same amount of
  border is laid over the new banner, so the white focus ring and the dashed amber move border
  stay exactly as captured.
- Images renamed `roldos-home-2.png` / `roldos-move-2.png` (GitHub caches images).

## Ideas / not done yet

- Show "No signal" or "Connected" on the HDMI tile (TvInputManager state).
- Remember which row and tile you were on when returning from an app.
