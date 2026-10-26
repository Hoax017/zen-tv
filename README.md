# Zen TV

**Make your Android TV / Google TV fast, calm and ad-free — in a few clicks, and 100 % reversible.**

🇫🇷 [Lire en français](README.fr.md)

![Zen Home](docs/home.jpg)

Zen TV does two things:

1. **Cleans up the TV**: turns off the manufacturer's ads, telemetry and useless preinstalled apps.
   It **asks you first** for anything you might use (Chromecast, voice assistant, USB player…).
2. **Installs Zen Home**: a minimalist, Apple TV–style home screen with a dock of your favourite apps
   and living nature wallpapers that follow the real time of day (dawn, noon, sunset, night…).

**Nothing is ever uninstalled.** Apps are only *disabled*, a backup is made before any change,
and one menu option puts the TV back exactly as it was.

## What you need

- An Android TV or Google TV (TCL, Sony, Philips, Hisense, Xiaomi, Chromecast with Google TV, Nvidia Shield…)
- A computer (Windows, Mac or Linux) **on the same Wi-Fi / network** as the TV
- 10 minutes

## Step 1 — Turn on debugging on the TV (one time)

1. On the TV: **Settings → System → About** (sometimes *Device Preferences → About*).
2. Click **Android TV OS build** (or *Build*) **7 times** until it says *You are now a developer*.
3. Go back to **Settings → System → Developer options** and turn on **USB debugging**
   (and **Network debugging** / **Wireless debugging** if you see it).

## Step 2 — Download Zen TV

Download **`zen-tv.zip`** from the [latest release](https://github.com/Hoax017/zen-tv/releases/latest)
and unzip it anywhere.

## Step 3 — Run it

| Windows | Mac | Linux |
|---|---|---|
| Double-click **`zen.bat`** *(not yet tested on a real Windows PC — feedback welcome)* | Open *Terminal*, type `cd ` (with a space), drag the unzipped folder into the window, press Enter, then type `./zen.sh` | In a terminal, in the folder: `./zen.sh` |

Then just follow the menu:

```
  1) Back up the TV (done automatically before any change)
  2) Clean up: ads, telemetry, useless apps (asks before each feature)
  3) Install the Zen Home screen
  4) Re-apply after a TV update (if removed apps came back)
  5) Restore the TV to how it was
  6) Take a screenshot of the TV
  7) Connect to another TV
```

- The first time, the script offers to download **adb**, Android's official connection tool, directly from Google.
- It finds the TV on your network by itself. **Look at the TV** when it connects:
  accept the *Allow USB debugging?* window (tick *Always allow*).

## Zen Home

| | |
|---|---|
| ![All apps](docs/all-apps.jpg) | ![Reorder](docs/reorder.jpg) |

- **Dock**: your favourite apps. **Hold OK** on an app to move it (◀ ▶), remove it (▼), then OK.
- **All apps** (grid button): long press or *Menu* to add / remove an app from the dock.
- **Inputs** and **Settings** buttons are always there.
- **15 scenes × 8 times of day**, blended continuously according to the real sunrise and sunset
  where you live (based on the TV's time zone): alpine lake, dunes, misty forest, Japanese temple, fjord with aurora,
  Tuscany, tropical beach, jungle hut, snowy peaks, karst peaks, castle above the clouds, Mayan pyramid, lighthouse,
  floating islands, Greek ruins.

![Themes](docs/themes.jpg)

Google TV doesn't let you change the default home screen. Zen Home therefore includes a tiny accessibility
service that only listens to the **Home** button of the remote and opens Zen Home. To give the Home button back
to the TV without a computer: **Settings → Accessibility → "Zen Home – Home button" → off**.

## Undo everything

Run the script and choose **5) Restore**. It re-enables every app it disabled, restores your accessibility
settings and home screen, and removes Zen Home. The script compares the result with the backup and tells you
if anything differs. (Mac/Linux shortcut: `./zen.sh --restore`; Windows: `zen.bat -Restore`.)

Backups live in the `backups/` folder next to the script: **keep that folder** if you want to be able to restore later.

## Is it safe?

- Apps are disabled with Android's own `pm disable-user` command, **never uninstalled**.
- A list of critical system components (`profiles/protected.txt`) can never be disabled, even by mistake.
- Brand lists marked *tested* were tried on a real TV. For other brands only the generic Google list is used,
  plus an optional **advanced scan** that asks about each manufacturer app one by one.
- Worst case, *Restore* puts everything back, and a factory reset of the TV always works too.

| Brand list | Status |
|---|---|
| Google (all TVs) — `profiles/common.txt` | generic |
| TCL — `profiles/tcl.txt` | ✅ tested (TCL G07, Google TV, Android 11) |

**Help wanted**: tested on another TV? Add `profiles/<brand>.txt` (the brand as reported by
`adb shell getprop ro.product.manufacturer`, lowercase) and open a pull request.

## Troubleshooting

- **TV not found**: check that the TV and the computer are on the same network and that debugging is on.
  You can also type the TV's IP address (TV *Settings → Network*).
- **"waiting for approval on the TV"**: accept the window on the TV screen.
- **Android 11+ "Wireless debugging"** with a pairing code: type the pairing `IP:port` when asked, then the code.
- **Removed apps came back after a TV update**: menu **4) Re-apply**.
- **Mac: "No route to host"**: the script restarts adb automatically; if it persists, allow *Terminal* in
  *System Settings → Privacy & Security → Local Network*.

## Build it yourself (developers)

Requirements: Android SDK (build-tools + a platform ≥ 30), JDK 11+, Python 3 with Pillow.

```bash
pip install pillow
./launcher/build.sh        # → dist/zen-home.apk
./tools/make-release.sh    # → dist/zen-tv.zip
```

Releases are built by GitHub Actions when a `v*` tag is pushed. They must be signed with the same key every
time (otherwise Android refuses the update): store it once as the secrets `ZEN_KEYSTORE_B64` (base64 of the
`.jks`), `ZEN_KEYSTORE_PASS`, `ZEN_KEY_ALIAS` and `ZEN_KEY_PASS`.

No Gradle: `build.sh` calls aapt2, javac, d8 and apksigner directly. The wallpapers are generated by
`launcher/tools/gen_wallpapers.py` (pure Python, no stock images).

## License

[MIT](LICENSE). Not affiliated with Google, TCL or any TV manufacturer.
