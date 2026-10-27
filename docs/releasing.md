# Releasing on Google Play

How a Play build goes from this repository to a testing track or production.
The F-Droid build is built and signed by F-Droid, and nothing here applies to it.

## The keys

Play App Signing holds the key that users' installs are signed with, and it never leaves Google.
The repository only ever meets the **upload key**, which proves an upload came from the maintainer.

- The keystore is `~/.config/piastre/upload.jks`, alias `upload`.
- Its passwords are in `~/.config/piastre/upload-keystore.properties`, with the keys `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
- `app/build.gradle.kts` reads that file when it exists and signs the `play` release with it.
  Without it, as on CI, a release builds unsigned, and Play refuses an unsigned bundle.
- Neither file ever enters the repository.
- Its SHA-256 is `7D:B4:E3:AF:1A:B5:80:D1:1B:13:8E:0C:B8:C4:58:98:AF:75:88:08:AE:FD:8E:04:01:C5:18:A6:B4:86:69:E7`, the same as Play Console › App integrity › Upload key.
  A lost or leaked upload key is replaced from that page; it costs about two days, not the app.

## A release, step by step

1. Raise `versionCode` in `app/build.gradle.kts` above every code Play has seen, on any track.
   Play holds 1, the billing spike, so the first closed-test build is 2.
2. `./gradlew spotlessApply check`.
3. `./gradlew bundlePlayRelease assemblePlayRelease`.
4. Check the signature: `apksigner verify --print-certs app/build/outputs/apk/play/release/app-play-release.apk` must print the upload key's SHA-256 above.
5. Prove the minified build on an emulator that has never held the debug build.
   The two are signed differently, so one cannot install over the other, and the answer is a second AVD, never an uninstall.
   Walk onboarding, a typed transaction, reports, review, Premium, and an export imported back after erasing.
6. Upload `app/build/outputs/bundle/playRelease/app-play-release.aab` to the track in Play Console.

R8 runs only on release builds, so step 5 is the only place a missing keep rule shows itself on a device.
CI runs `assembleRelease` to catch one that fails the build.

## Moving a phone onto the Play build

A phone running a debug build cannot update to the Play build, because their signatures differ.
The ledger crosses by hand, as it does between the two stores ([ADR-0022](adr/0022-piastre-is-sold-on-play-and-given-on-f-droid.md)): export CSV and JSON, import the JSON on an emulator to prove it reads, uninstall, install from Play, import.
The maintainer does this, on the phone, by hand.
