<p align="center">
  <img src="docs/images/roldos-hero.png" alt="RoldOS: Custom light and fast AndroidTV launcher." width="100%">
</p>

<p align="center">
  <img src="docs/images/roldos-home-2.png" alt="Fast access to all your apps: the real RoldOS home screen on the projector, with the clock, Close all apps, Favorites and All apps." width="100%">
</p>

<p align="center">
  <img src="docs/images/roldos-memory-2.png" alt="Less than half the RAM: on screen, stock launcher 147 MB, popular alternative 131 MB, RoldOS 62 MB." width="100%">
</p>

<p align="center">
  <img src="docs/images/roldos-size.png" alt="Smaller than a photo: app size, stock launcher 34 MB, popular alternative 13.7 MB, RoldOS 193 KB." width="100%">
</p>

<p align="center">
  <img src="docs/images/roldos-tiles.png" alt="Quickly access your most important features: Calibration, Settings, HDMI, Wireless Cast, Wired Cast and Original launcher tiles." width="100%">
</p>

<p align="center">
  <img src="docs/images/roldos-playing.png" alt="Now playing widget on your homescreen: the song, with previous, pause, next and close buttons." width="100%">
</p>

<p align="center">
  <img src="docs/images/roldos-close.png" alt="Close all apps at once: the Close all apps button, then 'Closed background apps · 146 MB freed'." width="100%">
</p>

<p align="center">
  <img src="docs/images/roldos-move-2.png" alt="Configure your homescreen to your specific liking: hold OK on a favorite, pick Move, slide it into place." width="100%">
</p>

## Why you'll like it

- **Fast.** Opens in a third of a second.
- **Light.** No ads, no background clutter, zero libraries.
- **Pretty.** Every app gets its own tile design, even the ones that don't bring one.
- **Yours.** Hold OK on a favorite → **Move** → slide it where you want it.
- **Now playing.** Music or a video playing in the background? Pause, skip or close it right from the home.
- **One click to clean up.** *Close all apps* really closes them: YouTube, Netflix, Spotify and friends stay closed until you open them again.
- **Always home.** After a reboot or an update you land on RoldOS, not the stock launcher.

## Install

```bash
adb connect <device-ip>:5555
./build.sh install <device-ip>:5555
```

The first time you press *Close all apps*, Android asks to **allow debugging** for
`RoldOS@projector`. Tick **Always allow** and accept, once. Android only lets an app fully
close other apps through the device's own debug connection, so RoldOS uses it, inside the
device, for nothing else. Say no and it still closes them the gentle way.

**Let an AI do it all** (cleanup, apps, RoldOS): clone this repo, open your AI coding agent in
the folder and say *"set up my Android TV device following SETUP.md"*.

<sub>Measured on a Magcubic projector (Allwinner H713, 1 GB RAM, Android 11). Details in [JOURNAL.md](JOURNAL.md).</sub>

<p align="center"><sub><a href="LICENSE">MIT</a> © 2026 Federico Roldós</sub></p>
