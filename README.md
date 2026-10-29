# Glyph Breathe — root-only Glyph brightness controller

A small, purpose-built Android utility for manually controlling the Nothing Phone (1) Glyph brightness interface at:

`/sys/class/leds/aw210xx_led/all_white_leds_br`

The app is not a wallpaper or a background service. It is intended to provide a brightness slider, off, breathing animation, and restoration of a saved original scalar value. The kernel node is device/build-specific; the app must report that the node is unavailable rather than probing or writing to unrelated paths.

## Build

The GitHub Actions workflow at `.github/workflows/build-apk.yml` builds a debug APK using JDK 17 and Android SDK Platform 35. Open **Actions → Build Glyph Breathe APK** to inspect a run and download the `GlyphBreathe-debug-apk` artifact.

The APK is not included in this repository unless a successful Actions run produces the artifact.

## Scope and safety

- No Android permissions, including Internet access.
- No ads, analytics, telemetry, WebView, boot receiver, scheduled job, or background service.
- No edits to ROM, kernel, boot, or Magisk module files.
- Writes are restricted to the exact Glyph scalar brightness node above.
- Before a change, the app saves the original brightness value to a root-protected backup at `/data/adb/glyph_breathe/originals`.
- Uninstalling does not restore device state. Use **Restore all saved values** before uninstalling, or use `restore-glyph.sh` from a root shell after uninstall.
- If restore fails, the backup should be retained for another attempt.

## Device-specific limitation

This project has not been tested on the owner's Evolution X installation. The exact node must exist and accept scalar values for the app to function. Community reports about Nothing Phone (1) stock firmware are not proof that a custom ROM exposes the same node.

## Release/upgrades

For in-place updates, use a stable APK signing key for successive builds. Do not upload private signing keys into the repository; configure them as GitHub Actions secrets. Review the workflow and source before installing.
