# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Android input method (Kotlin `InputMethodService` + NDK/CMake) around
libmie, the MokyaInput Engine. libmie is a git submodule at
`app/src/main/cpp/libmie` (tengigabytes/libmie, MIT). The app has an
on-screen MokyaLora half-keyboard and hardware-keyboard support (Dachen
Zhuyin). See README.md for usage, architecture and the engine ↔
InputConnection mapping.

## Decisions (owner)

- Front end: both the touch half-keyboard and hardware keyboards.
- Licence: Apache-2.0 for this app, mirroring MokyaLora's UI/application
  firmware (Core 1); libmie stays MIT; the generated dictionary carries its
  data licences (LGPL-2.1-or-later, CC BY-SA 4.0). See NOTICE.
- `minSdk` 24.

## Rules

- **Do not modify libmie from this repo.** Engine changes go to
  tengigabytes/libmie (its own CLAUDE.md has additive-only compatibility
  rules for MokyaLora hardware); then bump the submodule here.
- **Never commit third-party data.** `.mie-data/` (tsi.csv, en_50k.txt) and
  any `dict_mie_v4.bin` are generated or downloaded at build time and are
  gitignored.
- Put `SPDX-License-Identifier: Apache-2.0` at the top of new source and
  resource files. Keep NOTICE in sync when bundling anything new.
- Every `ImeLogic` call runs on the main thread, and never from inside a
  `MieListener` callback: callbacks only record work, which `runEngine`
  applies after the engine call returns.
- The JNI surface is bound by `RegisterNatives`: a change to `MieNative.kt`
  must be mirrored in the `kMethods` table in `mie_jni.cpp`, and callback
  names in `NativeCallbacks` must match the `GetMethodID` lookups (and
  `app/proguard-rules.pro`).
- Strings cross JNI as UTF-16 (`NewString` / `GetStringRegion`), never
  modified UTF-8: the dictionary contains non-BMP characters.
- `MokyaKeys` mirrors libmie's `keycode.h` (`KeycodeSyncTest`), and
  `KeyLabels` mirrors `kKeyTable` in `src/ime_keys.cpp` (`KeyLabelsSyncTest`).
- Keep input logic that does not need Android (layout, touch timing,
  hardware mapping, editor policy) in `:mie-engine`'s `input` package, with
  JVM tests.
- UI strings live in `values/strings.xml` (English) and
  `values-zh-rTW/strings.xml`; code comments and docs are in English.
- The field-test page lives in the `debug` source set (`app/src/debug`,
  its own strings too); its checklist entries are `id|label`, ids
  identical in every language (`FieldTestStringsSyncTest`). The IME side
  (`Diagnostics`, `MokyaImeService.trace`) is in `main` but records
  nothing unless switched on.
- Trace events that tell what is typed (keys, touch positions) go through
  `MokyaImeService.traceInput`, which keeps them out of the diagnostics in
  password and no-learning fields. Never trace the text itself.

## Build and test

```sh
git submodule update --init --recursive
./gradlew :mie-engine:test                 # JVM tests against a host build of mie_jni.cpp
./gradlew :app:assembleDebug               # needs Android SDK/NDK; downloads dict sources once
./gradlew :app:connectedDebugAndroidTest   # end-to-end on a device / emulator
./gradlew :app:assembleDebug -Pmokya.dict=/path/to/dict_mie_v4.bin   # offline
./gradlew :mie-engine:test -Pmokya.realDict=/path/to/dict_mie_v4.bin # + full-dict smoke test
```

The end-to-end tests (`app/src/androidTest`) run in the app process and
use the internal hooks at the end of `MokyaImeService`. They enable the IME
with `ime enable/set`, type into `SetupActivity`'s field via injected key
events and touches, and assert on the field's text. A failure message
includes the IME's recent events (`MokyaImeService.traceForTest`), since
CI only keeps the Gradle log; CI also prints the whole trace from logcat
(tag `MokyaTrace`) after every emulator run. Wait for the IME to see an
app-side edit before typing: `setText` restarts input asynchronously (use
`append`).

CI: `.github/workflows/android.yml` runs the host tests, `assembleDebug`
(with APK content checks) and the emulator tests on every push / PR.

Versions are pinned in `gradle/libs.versions.toml` (AGP 9, built-in Kotlin;
the `kotlin-jvm` plugin there only serves `:mie-engine`) and
`gradle/wrapper/gradle-wrapper.properties`.
