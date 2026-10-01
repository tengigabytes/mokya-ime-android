# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Android input method (Kotlin `InputMethodService` + NDK/CMake) around
libmie, the MokyaInput Engine. libmie is a git submodule at
`app/src/main/cpp/libmie` (tengigabytes/libmie, MIT). See README.md for the
architecture and the engine ↔ InputConnection mapping.

## Rules

- **Do not modify libmie from this repo.** Engine changes go to
  tengigabytes/libmie (its own CLAUDE.md has additive-only compatibility
  rules for MokyaLora hardware); then bump the submodule here.
- **Never commit third-party data.** `.mie-data/` (tsi.csv, en_50k.txt) and
  any `dict_mie_v4.bin` are generated or downloaded at build time and are
  gitignored. See README "Third-party data" for their licences.
- **Ask before building UI.** The front end (touch half-keyboard, hardware
  keyboard, or both), the app licence and `minSdk` are open decisions for
  the owner.
- Every `ImeLogic` call runs on the main thread, never from inside a
  `MieListener` callback.
- The JNI surface is bound by `RegisterNatives`: a change to `MieNative.kt`
  must be mirrored in the `kMethods` table in `mie_jni.cpp`, and callback
  names in `NativeCallbacks` must match the `GetMethodID` lookups (and
  `app/proguard-rules.pro`).
- Strings cross JNI as UTF-16 (`NewString` / `GetStringRegion`), never
  modified UTF-8: the dictionary contains non-BMP characters.
- `MokyaKeys` mirrors libmie's `keycode.h`; `KeycodeSyncTest` enforces it.
- Code comments and docs in English.

## Build and test

```sh
git submodule update --init --recursive
./gradlew :mie-engine:test        # JVM tests against a host build of mie_jni.cpp
./gradlew :app:assembleDebug      # needs Android SDK/NDK; downloads dict sources once
./gradlew :app:assembleDebug -Pmokya.dict=/path/to/dict_mie_v4.bin   # offline
./gradlew :mie-engine:test -Pmokya.realDict=/path/to/dict_mie_v4.bin # + full-dict smoke test
```

CI: `.github/workflows/android.yml` runs both on every push / PR.

Versions are pinned in `gradle/libs.versions.toml` (AGP 9, built-in Kotlin;
the `kotlin-jvm` plugin there only serves `:mie-engine`) and
`gradle/wrapper/gradle-wrapper.properties`.
