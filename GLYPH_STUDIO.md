# Glyph Studio for Nothing Phone (1)

A separate rooted Android app project stored alongside the existing Glyph Breathe project. The source archive is `GlyphStudio-NothingPhone1-source.tar.xz` (extracts into `GlyphStudio/`).

## Build

GitHub Actions workflow: **Build Glyph Studio Debug APK**. A change to the source archive or workflow on `main` triggers a build. The workflow installs JDK 17, Android SDK Platform 35 and Build Tools 35.0.0, builds with Gradle 8.11.1, verifies the APK signature and application ID, and uploads `GlyphStudio-debug-apk` for 14 days.

## What it does

- Draw, tap-toggle, erase and fill calibrated Glyph zones.
- Direct raw AW210xx channel control and 0–33 scan.
- Per-device raw-channel-to-logical-zone calibration.
- Breathe, Chase, Wave and Pulse effects.
- Saved brightness snapshot and all-off control.

## Hardware scope and limits

The app writes to `/sys/class/leds/aw210xx_led/single_led_br` using `<raw-index> <brightness>`. It treats the node as write-only. The 0–33 scan is a reference range, not a guarantee that every index is wired on every kernel. Calibration is mandatory; raw indices are not assumed to match the 15 logical SDK zones. The app does not implement screen-off tap-to-wake and does not control each microscopic diffuser LED individually.

A successful CI build verifies compilation, APK packaging and debug signing. Physical LED mapping still needs to be verified on the rooted Nothing Phone (1) running the target kernel.
