# EssentialMapper (Shizuku)

[English](README.md) | [日本語](README.ja.md)

This is a Shizuku-powered fork of [tracpants/essential-mapper](https://github.com/tracpants/essential-mapper).

Remap the Nothing Phone (3a) Lite's hardware **Essential Key** to launch an app or
toggle the flashlight by tap count — single, double, or triple.

<p align="center">
  <img src="docs/screenshot.png" alt="EssentialMapper config screen" width="300">
</p>

Default mapping is **single → Claude · double → Wallet · triple → flashlight**; change any
slot in the app.

## Key Changes in this Fork

- **No Accessibility Service (Bypass Payment App Blocks)**: Completely eliminates the dependency on Android's Accessibility Service. Many financial and payment apps (such as PayPay and various Japanese banking apps) block execution or show security warnings when Accessibility Services are enabled. By using [Shizuku](https://shizuku.rikka.app/) and running as a standard Foreground Service, this fork avoids those restrictions entirely.
- **Built-in System Actions**: In addition to launching apps and toggling the flashlight, you can map keys to **Quick App Switch (Alt+Tab)**, **Recent Apps**, **Notification Shade (Toggle/Open/Collapse)**, **Control Center / Quick Settings (Toggle/Open/Collapse)**, and **Screenshot** directly via Shizuku.
- **In-App Single Press Unlock**: Toggle the Nothing stock app suppression directly from the app UI via Shizuku without needing a PC or manual ADB commands.
- **Independent Package**: Packaged as `com.aiden.essentialmapper.shizuku` so it can be installed alongside the original app.

## Requirements & Setup

1. **Shizuku**: Make sure [Shizuku](https://shizuku.rikka.app/) is running on your device (via Wireless Debugging or Root).
2. Install the APK and launch **EssentialMapper (Shizuku)**.
3. Grant Shizuku permission when prompted, then start the monitoring service.

## Freeing the single press

Double- and triple-tap work immediately. The **single** press stays system-owned until Nothing's stock Essential surfaces are disabled.

In this fork, **you can simply toggle this on/off directly inside the app** using the **"Free Single Press"** switch (powered by Shizuku).

Alternatively, you can still disable them manually via ADB:

```bash
adb shell pm disable-user --user 0 com.nothing.ntessentialspace
adb shell pm disable-user --user 0 com.nothing.ntessentialrecorder
adb shell pm disable-user --user 0 com.nothing.essentialintelligence
```

## How it works

Instead of hooking Android's `AccessibilityService.onKeyEvent`, this fork uses Shizuku to read kernel input events directly from `/dev/input/event0` via `getevent`.

It filters for the Essential Key's hardware event code `00fa` (hex for **scanCode 250**; reports `keyCode=0`, so scanCode is its only stable identity). Taps are counted on a 400 ms debounce window and dispatched to your configured apps via a DataStore-backed map. Both tunables live in `EssentialKeyService`.

> ⚠️ scanCode 250 is specific to this device/firmware — no promise it matches other Nothing models.

## Credits

- Original project by [Aiden Rudolph (@tracpants)](https://github.com/tracpants/essential-mapper).
- The ADB trick comes from [z3phydev's guide](https://github.com/z3phydev/How-to-remap-or-disable-the-Essential-Key).
- Interception approach mirrors [Key Mapper](https://github.com/keymapperorg/KeyMapper).

## License

[MIT](LICENSE) © 2026 Aiden Rudolph / Contributors. Not affiliated with Nothing.
