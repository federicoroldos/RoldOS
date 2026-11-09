# Setting up an Android TV device the RoldOS way

A step-by-step recipe to take a stock Android TV device (the reference is a projector) to a
clean state: light, quiet in the background, and on RoldOS. **It is written for an AI coding
agent** (Claude Code or similar) that does the work over adb together with its user, the way
this setup was first made. A person can follow it too.

**For the user:** clone this repo, open your AI coding agent inside the folder, and tell it
*"set up my Android TV device following SETUP.md"*. It will ask you a few things along the way.

Reference device: **Magcubic projector**, Allwinner H713, 960 MB RAM, Android 11, 32-bit,
reports itself as `ADT-3` (fingerprint `ADT-3/adt3/adt3:11/RP1A.201005.006/Projector.20260530.1707`),
stock launcher `com.htc.luminaos`. Other devices: see the last section.

`JOURNAL.md` has the full story and the reasons behind every step.

---

## Rules for the agent

1. **Talk to the user in their language and in plain words.** Say what changed and what it
   means for them, not how it works inside.
2. **The user decides what goes.** Survey first, then propose; only remove what they say yes to.
3. **Disable, never uninstall** vendor apps (`pm disable-user --user 0 <pkg>`). Keep the undo
   (`pm enable <pkg>`) at hand and tell the user it exists.
4. **Before disabling any vendor app, find out what launches it.** On the reference device,
   `com.softwinner.awlivetv` looked like a "TV tuner" and was disabled, but it is the HDMI
   viewer: HDMI broke. Check with `logcat | grep "START u0"` while using the stock UI, and
   search other vendor APKs for the package name.
5. **Never stop, kill or disable** the packages in "Must keep" below.
6. **Never open Wireless Cast (Miracast) while connected over wireless adb**: it takes the
   projector off Wi-Fi and the connection drops until it's closed.
7. When pressing keys over adb (`input keyevent`), wait ~1.5 s between presses.

---

## 0. What the user needs

- A Windows, macOS or Linux PC on the same Wi-Fi as the projector.
- **Android Studio** (for its JDK at `Android Studio/jbr`) and the Android SDK with
  **platform-tools**, **build-tools 36** and an **android platform** jar (any recent one).
- **Git Bash** on Windows (`build.sh` is a bash script).
- The projector's IP address (Settings → Network, or the router's device list).

## 1. Connect

On the reference device, network adb is already listening on port 5555 at every boot (nobody
had to turn it on). If it isn't on another unit, enable developer options and network/USB
debugging in its settings first.

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb"     # adb usually isn't on PATH
$ADB connect <ip>:5555          # the projector asks to allow the PC: "Always allow" + OK
$ADB -s <ip>:5555 shell getprop ro.build.fingerprint
$ADB -s <ip>:5555 shell pm list packages | grep luminaos   # present = same family as ours
```

## 2. Survey (read only)

Show the user what's there and what uses memory before changing anything:

```bash
adb shell pm list packages -3 -i          # apps installed after the factory (+ who installed them)
adb shell pm list packages -d             # already disabled
adb shell dumpsys meminfo | sed -n '/Total PSS by process/,/Total PSS by OOM/p'
adb shell cat /proc/meminfo | head -3
```

Look out for unknown apps installed with `installer=null` that run at boot: on the reference
device that was an ad app (below).

## 3. Must keep (never disable, never force-stop)

| Package | Why |
|---|---|
| `com.htc.luminaos` | Stock launcher. Hosts **all** projector calibration (keystone, focus, projection mode, zoom) and the settings menus RoldOS opens. A stopped app misses the broadcasts that open them. |
| `com.hysd.vafocus` | Autofocus. |
| `com.htc.closedialog` | Not just a dialog: manual focus, remote control, **fan control**, voice. |
| `com.softwinner.awlivetv` | The **HDMI** input viewer (see rule 4). |
| `com.softwinner.miracastReceiver` | Wireless Cast. |
| `com.toofifi.lineserver` | Wired Cast (cable mirroring from a phone). |
| `com.mphotool.usbcastserver` | Wired Cast binds its `CastService`. |
| `com.android.toofifi` | Casting hub; unclear whether Wired Cast needs it, so keep it. |

## 4. The method: judging an app you don't know

The lists in this guide are for the reference device. On any other device, judge every
vendor app this way before proposing anything. This is how the reference device was cleaned,
and it's what makes the cleanup safe rather than a guess. Use it on the reference device too
when something isn't in the tables.

### 4.1 Collect the facts (read only)

```bash
P=<package>
adb shell dumpsys package $P > $P.txt          # read it whole; the lines that matter:
#   userId=1000 / sharedUser=android.uid.system  -> runs as the system: powerful, handle with care
#   flags=[ ... PERSISTENT ... ]                  -> the system keeps it alive: almost always keep
#   installerPackageName=null + codePath under /data -> pushed after the factory image (suspicious)
#   firstInstallTime / lastUpdateTime             -> updated itself recently, outside Google Play?
#   requested/install permissions                 -> see 4.2
adb shell cmd package query-receivers --components -a android.intent.action.BOOT_COMPLETED | grep $P
adb shell cmd package query-services --components -a android.media.tv.TvInputService | grep $P
adb shell dumpsys meminfo $P | grep "TOTAL PSS"   # what it costs while running
adb shell ps -A -o USER,PID,NAME | grep $P         # running now? as whom?
```

Then look inside the APK, on the PC:

```bash
adb pull "$(adb shell pm path $P | head -1 | cut -d: -f2 | tr -d '\r')" $P.apk
BT=$(ls -d "$LOCALAPPDATA"/Android/Sdk/build-tools/* | sort -V | tail -1)
"$BT/aapt2" dump badging $P.apk | head -5                          # real name, version
"$BT/aapt2" dump xmltree $P.apk --file AndroidManifest.xml > $P.manifest.txt
#   read the activities, services, receivers and their actions: they say what it really does
unzip -o -q $P.apk 'classes*.dex' -d $P.dex
grep -a -o -h -E '[a-z]+([./][a-z0-9_]+){2,}' $P.dex/*.dex | sort -u > $P.classes.txt
#   the code's package names and server addresses: libraries it carries (ad SDKs, analytics,
#   plugin loaders) and where it phones home. On the reference ad app this shows zeasn.tv
#   servers and com.sensorsdata (tracking).
```

### 4.2 Read the signs

**Probably ads or junk** (several of these together):
- installer `null`, installed under `/data` after the factory date, updates itself;
- starts at boot, holds `INSTALL_PACKAGES` / `REQUEST_INSTALL_PACKAGES` and
  `SYSTEM_ALERT_WINDOW` (draw over the screen);
- carries ad or tracking libraries: names with `ad`, `ads`, `adsdk`, `advert`, analytics SDKs
  (`sensorsdata`, `umeng`, `firebase.analytics`…), plugin loaders (`dynamic.plugin`,
  `dexclassloader`), video-ad activities;
- no other vendor app mentions it (4.3), and the user never opens it.

**Probably important** (keep, or ask very carefully):
- `PERSISTENT`, or a `TvInputService` (inputs like HDMI), the only keyboard (IME), a home
  activity, or anything the stock launcher starts on its own;
- hardware words in its manifest or code: `fan`, `focus`, `keystone`, `motor`, `projector`,
  `tof`, `gsensor`, `remote`, `ir`, `hdmi`, `input_source`, `cec`, `bluetooth`;
- services other apps bind to (another app's code names its service class);
- core packages: `android`, `com.android.*` providers and settings, Google Play services and
  the Play Store (other apps depend on them).

Names lie: `com.htc.closedialog` sounded like a popup and runs the fan; `awlivetv` sounded like a
TV tuner and is the HDMI viewer; `screensaversdk` sounded like a screensaver and is an ad app.
**Judge by what the code holds, not by the name.**

### 4.3 Who uses it

Search every other app on the device for the package name:

```bash
mkdir -p vendor && for p in $(adb shell pm list packages -s | cut -d: -f2 | tr -d '\r'); do
  adb pull -q "$(adb shell pm path $p | head -1 | cut -d: -f2 | tr -d '\r')" vendor/$p.apk; done
for f in vendor/*.apk; do unzip -p "$f" 'classes*.dex' | grep -a -q "$P" && echo "$f"; done
```

For every hit, find out **when** it uses it: only on a click (fine, like the stock launcher
opening the store from its store tile) or on its own at start or to bind a service (then
disabling breaks that app; that's how Wired Cast turned out to need the iPhone cast server).
To catch it live, watch while the user uses the stock UI:

```bash
adb logcat -c; adb logcat | grep -E "START u0|Start proc|bindService"
```

### 4.4 Decide with the user

Group every app into three lists and show them in plain words, with the memory each one uses:
**remove** (ads, junk, unused), **ask** (useful to some people: voice search, AirPlay, a
gallery), **keep** (important, or unknown). Unknown means keep. The user decides each one.

### 4.5 Disable one at a time, and check

```bash
adb shell pm disable-user --user 0 $P
adb logcat -b crash -d                          # nothing new
adb shell pidof <each must-keep package>        # still running
```

Then ask the user to try what matters on their device: remote, Home, settings, the inputs
(HDMI), casting, picture adjustments (focus, keystone), sound, and a **reboot**. If anything
fails, `adb shell pm enable $P` and mark it **keep**. Write every change (package, why, date,
undo) into the user's notes, the way `JOURNAL.md` does here.

## 5. Debloat on the reference device (propose, the user decides)

What was disabled on the reference device, with `adb shell pm disable-user --user 0 <package>`:

| Package | What it is | Why it went |
|---|---|---|
| `com.hotack.screensaversdk` | **Ad app** pushed after purchase: starts at boot, runs as system, can install apps silently, draw over the screen, has an ad SDK, analytics and downloadable plugins. Updates itself. | Ads and risk. ~16 MB. |
| `com.htc.storeos` | Vendor app store (likely what pushes the ad app). The stock launcher only opens it on a click. | ~25 MB, pushes apps. |
| `android.rockchip.update.service` | USB updater left over from another chip. Nothing references it. | Useless here. |
| `com.mphotool.airplay` | AirPlay receiver. | Not used. Keep it if the user casts from Apple devices. |
| `com.google.android.katniss` | Google voice search. | ~115 MB. Keep it if the user talks to the remote. |
| `com.htc.hyk_test` | Factory test app. | Useless. |
| `com.mobisystems.office` | Office suite. | Not used. |
| `org.chromium.webview_shell` | Test browser. | Useless. |

Also disabled on the reference unit, not recorded as our doing (probably factory state; don't
touch): `com.android.mtp`, `com.android.nfc`, `com.android.gallery3d`.

After disabling, check nothing broke: `adb logcat -b crash -d` stays empty, and
`adb shell pidof com.htc.closedialog com.hysd.vafocus` still prints processes.
Undo any of them: `adb shell pm enable <package>`.

System animations were already off from factory (`settings get global window_animation_scale`
→ `0`). Leave them that way.

## 6. Apps (only what the user wants)

What's on the reference device, and where each came from:

| App | Package | Source |
|---|---|---|
| YouTube without ads, TV interface | `io.gh.reisxd.tizentube.cobalt` | TizenTube Cobalt, GitHub releases, **`cobalt-arm.apk`** (the device is 32-bit). `adb install`. |
| Spotify | `com.spotify.tv.android` | Google Play (press Install from the phone or play.google.com, choosing the projector). |
| Prime Video | `com.amazon.amazonvideo.livingroom` | Google Play. |
| Send Files to TV | `com.yablio.sendfilestotv` | Google Play. |
| Disney+ | `com.disney.disneyplus` | Came through the vendor store. |
| Netflix | `com.netflix.mediaclient` | The phone app, sideloaded. |

Don't suggest phone versions of apps that have a TV version: they're painful with a remote
(tried with YouTube ReVanced, dropped).

## 7. Install RoldOS

```bash
git clone https://github.com/federicoroldos/RoldOS.git && cd RoldOS
./build.sh install <ip>:5555
```

`build.sh install` builds the APK (signed with the PC's own debug key), installs it, and then:

- makes RoldOS the home app (boot lands on it);
- grants `WRITE_SECURE_SETTINGS`: the stock settings menu **wipes the accessibility list every
  time it opens**, and RoldOS needs this to switch its Home-key service back on;
- grants `ACCESS_FINE_LOCATION`: Android only reveals the Wi-Fi network's name with it;
- approves the media listener: the Now playing row shows titles, covers and controls;
- turns on the Home-key service (`HomeKeyService`) and retries until Android reports it bound.
  The remote's Home key is hard-wired to the stock launcher; this service catches it.

Then, **on the projector**: press **Close all apps** once. Android asks to allow debugging for
`RoldOS@projector`. The user ticks **Always allow** and accepts. From then on RoldOS fully
closes apps (force-stop through the projector's own adb, inside the device). Saying no still
works, but only closes them the gentle way.

Updating later: same command. Build from the **same PC** (same debug key) or Android treats it
as a different app and refuses the update; reinstalling from another PC means uninstalling first
(favorites are lost).

## 8. Check it worked

```bash
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
#   -> com.roldos/.MainActivity
adb shell dumpsys accessibility | grep "Bound services"          # -> RoldOS
adb shell settings get secure enabled_notification_listeners     # -> com.roldos/com.roldos.MediaAccess
adb shell settings get global adb_allowed_connection_time        # -> 0 (after Close all apps)
adb shell dumpsys meminfo com.roldos | grep "TOTAL PSS"          # -> roughly 60-75 MB
```

Then ask the user to try, on the projector: the Home key, the Calibration tile (opens the
Projection page; Back returns to RoldOS), Settings, HDMI, Close all apps, and a reboot (it
should come up on RoldOS).

## 9. Undo everything

```bash
adb shell cmd package set-home-activity com.htc.luminaos/.activity.MainActivity
adb uninstall com.roldos
adb shell pm enable <each disabled package>
```

---

## On other devices

RoldOS checks for `com.htc.luminaos` and shows the projector tiles only when it's there.

- **Same projector family** (LuminaOS present): everything above applies; package names of
  vendor apps may differ, so survey and judge each unknown one with section 4.
- **Anything else** (Chromecast, other brands): the Settings row has a single Settings tile
  (no Calibration, HDMI, cast tiles). Close all apps only does the gentle close unless the
  device has network adb on `127.0.0.1:5555`. Sections 3, 5 and 6 don't apply: debloat that device with
  the method in section 4 and the same rules.
