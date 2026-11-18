# Homey Room Sync

**EARLY ALPHA — experimental, not production ready.**

An Android/Jetpack Compose app for reviewing Google Home room assignments against their actual rooms in Homey Pro. Based on the Apache-2.0 licensed Google Home APIs Android sample.

## Features

- Reads Google Home devices and rooms from the Home API and Homey inventory from a read-only LinuxServer endpoint.
- Treats the actual Homey room as the preferred Google room. Prefixes in a device name (for example, Loft Wallplug) are hints only.
- A compact per-device list displays current Google room, matching Homey room, default suggestion, and choices: Keep, Ignore, or select another Google room.
- Proposes missing rooms only when a unique Homey room is known. Ambiguous names stay in their current Google rooms.
- A single button previews all selected changes and requires explicit confirmation; then creates approved rooms, moves selected Google devices and verifies each result.
- Reports timeout, Android ANR, and crash metadata securely to a private LinuxServer diagnostic endpoint.
- No light on/off changes. No Homey Flow changes.

**Automatic unattended room synchronization is disabled in this early-alpha release.**

## Repository layout

- android/ — complete Android application, resources, Gradle wrapper, Kotlin source and unit tests.
- server/ — Homey inventory exporter, cache and authenticated diagnostics receiver.
- tests/ — companion server tests.

## Setup

1. Install JDK 17, Android Studio and Android API 36.
2. Create android/local.properties with sdk.dir, WEB_CLIENT_ID_DEV and ROOM_SYNC_READ_TOKEN for your own instance. Do not commit this file.
3. Configure your personal Google Home name, local HTTPS server endpoint and SHA-256 certificate fingerprint in android/app/src/main/java/com/example/googlehomeapisampleapp/roomsync/SyncSettings.kt.
4. Configure the Homey API endpoint and a dedicated private key file for the companion server. Never use the real Homey API key as the app diagnostics token.
5. Run cd android followed by ./gradlew :app:testDebugUnitTest :app:assembleDebug.

The distributed private deployment APK uses instance-specific settings. This public repository intentionally contains only placeholders instead of personal network details, tokens, OAuth credentials, certificates, or signing keys.

## Early-alpha limitations

- Real-device verification is still required for the redesigned batch workflow.
- The Google Home API may not expose every Google-linked device, and unavailable devices cannot be added merely by moving rooms.
- Duplicate names and non-unique identity matches require manual review.
- A batch can partially complete: confirmed earlier moves are not rolled back if Google rejects a later move.
- Crash reports go to the configured LinuxServer, not directly into ChatGPT.
- No automatic unattended room changes in the alpha version.

## Attribution

Based on Google Home APIs Android Sample App: https://github.com/google-home/google-home-api-sample-app-android

Original copyright headers are preserved. License: Apache License 2.0 in android/LICENSE. This is an independent experimental project and is not officially affiliated with Google LLC or Athom B.V.
