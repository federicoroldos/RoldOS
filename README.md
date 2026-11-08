# RoldOS

A tiny, fast home screen (launcher) for Android TV devices, built for a budget
Magcubic projector with 1 GB of RAM. Google TV–inspired layout, no libraries, a
~45 KB APK.

> RoldOS is a launcher, not a full operating system: it replaces the home
> screen and runs on top of the device's own Android.

## Why

The projector's stock home screen is heavy and full of ads, and popular
alternatives like Projectivy Launcher are feature-rich but big. On a device with
~400 MB of free memory, that matters.

Measured on the projector (Allwinner H713, Android 11), 30 s after opening:

| | Projectivy Launcher | RoldOS |
|---|---|---|
| Memory (PSS) | 131 MB | 56–68 MB |
| Cold start | ~1.1 s | ~0.35 s |

About 22 MB of RoldOS's memory is graphics memory the GPU driver reserves for any
full-screen app, so that's close to the floor.

## Features

- **Favorites** row with large tiles, plus **All apps**.
- **Every tile has its own look**: apps that ship a TV banner use it; apps that
  don't get a generated banner in their icon's own colors (icon, name, and a large
  faded echo of the icon).
- **Settings** row (projector): **Calibration** (jumps straight to the projector's
  Projection page: keystone, zoom, installation mode, autofocus…), **Change
  input** (HDMI), **Settings**, **Wi-Fi**.
- **Close everything** button: ends all background apps to free memory and tells
  you how much it freed (music playing in the background is left alone).
- Long-press OK (or press Menu) on an app: add or remove it from favorites, move it,
  open app info, uninstall.
- The remote's **Home** button always opens RoldOS, even on devices that hard-wire
  it to their stock launcher.
- English UI by default, Spanish menus when the system is in Spanish.

## How it stays light

- Plain Android framework widgets only: no AndroidX, no Compose, no Gradle
  dependencies.
- One background thread. Tile art is rendered once, at exact size, into 16-bit
  bitmaps, and dropped when Android asks for memory while another app is in front.
- No wallpapers, channels, previews or background services besides the tiny
  Home-key service.

## Building

Requirements (Windows): Android SDK (build-tools + a platform), the JDK bundled
with Android Studio, and Git Bash. No Gradle needed.

```bash
./build.sh                          # -> build/roldos.apk
./build.sh install 192.168.0.121:5555   # build, install, and set RoldOS as home
```

`build.sh install` also re-enables the Home-key accessibility service, because
Android switches it off whenever an update changes what the service is allowed to do.

## Setting it up on a device

Wireless debugging must be on. Then:

```bash
adb connect <device-ip>:5555
./build.sh install <device-ip>:5555
```

That installs the APK, makes RoldOS the default home, and enables its Home-key
service (`uy.fede.roldos/.HomeKeyService`).

### Going back to the stock launcher

```bash
adb shell settings put secure enabled_accessibility_services ""
adb shell settings put secure accessibility_enabled 0
adb shell cmd package set-home-activity com.htc.luminaos/.activity.MainActivity
adb uninstall uy.fede.roldos   # optional
```

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/uy/fede/roldos/
    MainActivity.java     home screen: rows, tiles, focus handling, actions
    TileArt.java          all drawing: generated banners, Settings tiles, icons
    HomeKeyService.java   catches the Home key; presses "Projection" for Calibration
  res/                    strings (en, es), theme, accessibility config, app icon
build.sh                  javac -> d8 -> aapt2 -> zipalign -> apksigner (+ install)
CLAUDE.md                 working notes for Claude Code
JOURNAL.md                milestones, discoveries and decisions
```

## Tested on

- Magcubic projector, Allwinner H713 (`sun50iw12p1`), Android 11, 1 GB RAM.

On other Android TV devices the projector-only tiles (Calibration, Change input)
are hidden, and Settings/Wi-Fi open Android's own settings. "Close everything" only
works fully up to Android 13: Android 14 stopped letting apps close other apps.

## License

No license has been chosen yet, so all rights are reserved by default.
