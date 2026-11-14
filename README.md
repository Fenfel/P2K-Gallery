# P2K Gallery

**EN** — Android app that talks to early-2000s Motorola P2K phones (tested on C650; other P2K-mode phones should work) over a plain USB-OTG cable. No PC in between: plug the phone into your Android phone, P2K Gallery switches the device from modem mode into P2K (`AT+MODE=8`) and browses the phone filesystem via the P2K FSAC protocol.

**RU** — Android-приложение для старых Motorola с протоколом P2K (проверено на C650) по USB-OTG без компьютера: само переключает телефон в P2K-режим (`AT+MODE=8`) и работает с файловой системой телефона.

## What it does / Что умеет

- Auto-detects Motorola (VID `0x22B8`), sends `AT+MODE=8` and reconnects in P2K mode.
- One recursive listing → instant **flat photo gallery**: only `.jpg`/`.jpeg` files are ever shown. Folders stay for navigation.
- Sort: newest-first (by filename numbering), A→Z, Z→A, largest-first.
- **IMPORT ALL** — pull every photo off the phone into `Pictures/P2K Gallery` (visible in any Android gallery).
- **Multi-select** (long-press): download selected, or delete selected **from the phone**.
- **DELETE ALL** — remove all `.jpg` from the phone, with an explicit confirmation count.
- **Preview** renders from a private app cache and is deleted when closed — never pollutes your gallery.
- Upload any local file back into the current phone folder.
- Material 3 UI that adapts to Android dynamic/Material You themes (label contrast is enforced at runtime).

## Safety of phone deletion

P2K `FSAC delete` is called **only with exact file paths taken from the phone's own listing**, and only for non-directory `.jpg` entries:

- folders can never reach the delete call (`isDir` filtered before it);
- non-jpg files never enter the listing at all;
- no wildcards, no recursive delete, no user-typed paths;
- P2K exposes only user storage volumes (`/a`, `/b`) — system partitions are invisible to the protocol.

## Requirements

- Android 7.0+ (API 24) with USB-OTG
- Motorola P2K phone (C650 tested); mode switch is automatic

## Build

JDK 17 + Gradle 8.9 (wrapper not committed):

```bash
cd android && gradle assembleDebug
```

APK → `android/app/build/outputs/apk/debug/app-debug.apk`. Prebuilt debug APKs are attached to GitHub Releases.

## Notes

P2K directory records carry **no timestamps**, so "newest first" orders by numeric filename numbering (what the phone camera produces). Package id `ru.mptdroid` is kept so old installs update in place.

License: none — all rights reserved.
