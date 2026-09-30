# TorrentOrV2

A native Android BitTorrent client with a hand-built Kotlin UI and a Rust torrent engine
([librqbit](https://github.com/ikatson/rqbit)) connected through JNI. Built entirely from the
command line — VS Code, Gradle CLI, and ADB — no Android Studio.

## Features

- **Add torrents** via magnet link, magnet hash, or `.torrent` file, with a file-selection
  screen before committing to a download
- **Full-screen torrent details** with General, Files, Trackers, Peers, Pieces, and Comment tabs
- **Per-file actions** — long-press a file to open, share, rename, or delete it individually
- **Move a torrent's save location** after it's already downloading
- **RSS feed subscriptions** with configurable auto-refresh and per-feed auto-download of new
  items
- **Built-in search** across torrent search engines
- **DHT, Local Service Discovery (LSD), and Wi-Fi-only mode**, each toggleable
- **Configurable speed limits** and a live storage-space readout
- **Persistent execution log** (Execution Log screen) for diagnosing what the app has done
- **Selectable accent themes** (7 built-in color options)
- **Quick-add** shortcuts and Android share-sheet integration for magnet links / `.torrent` files

## Architecture

- **UI**: Kotlin, plain Android `View`s (no Jetpack Compose, no XML layouts) — everything is
  built programmatically in code
- **Engine**: a Rust crate (`rust/`) wrapping [librqbit](https://github.com/ikatson/rqbit) 9.0.1,
  compiled to a native `.so` library per architecture and called from Kotlin through a small JNI
  bridge (`RustBridge.kt`)
- **Multi-activity**: `MainActivity` (torrent list), `TorrentDetailActivity` (full-screen
  per-torrent view), plus `RssActivity`, `SearchActivity`, and `LogActivity`

## Building

### Prerequisites

- JDK 17
- Android SDK (API 35) and NDK
- Rust toolchain + [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk)

### Build the native engine

From the `rust/` folder, build the `.so` for each architecture you need and drop it into the
matching `app/src/main/jniLibs/<abi>/` folder:

```
cd rust
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 -o ../app/src/main/jniLibs build --release
cd ..
```

### Build the APK

```
.\gradlew.bat assembleDebug
```

The debug APK will be at `app\build\outputs\apk\debug\app-debug.apk`. Install it with:

```
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Release build

Release builds are signed using a keystore that isn't checked into this repo. Copy
`gradle.properties.example` to `gradle.properties` and fill in your own keystore path and
passwords, then:

```
.\gradlew.bat assembleRelease
```

The signed APK will be at `app\build\outputs\apk\release\app-release.apk`.

## Project layout

```
app/
  src/main/java/com/example/torrentorv2/   Kotlin source
  src/main/res/                            App icon, colors, strings
  src/main/jniLibs/<abi>/                  Prebuilt native engine libraries
rust/
  src/lib.rs                               JNI bridge + engine wiring around librqbit
  Cargo.toml
```

## Notes

- No custom native-code toolchain wiring lives in the Gradle build — the `.so` files in
  `jniLibs/` are built separately and just get packaged by Android's standard native-library
  handling.
- librqbit's runtime API doesn't expose a few `.torrent`-file fields (comment, created-by,
  creation date, time active) for a torrent that's already been added — where practical
  (e.g. the Comment tab), this app works around that by capturing what it can at the moment a
  torrent is first added, since that's the only point that data is ever available.
