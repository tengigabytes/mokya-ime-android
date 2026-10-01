# Mokya IME for Android

An Android input method built on [libmie](https://github.com/tengigabytes/libmie),
the MokyaInput Engine from the [MokyaLora](https://github.com/tengigabytes/MokyaLora)
feature phone: Bopomofo half-keyboard input for Traditional Chinese, English
prediction, and multi-tap direct input.

**Status: engine skeleton.** The input method service, JNI bridge,
dictionary packaging and LRU persistence are in place. There is no keyboard
UI and no hardware-key mapping yet, so the IME cannot be typed with yet.

---

## Layout

```
mokya-ime-android/
├── app/                          Android application (:app)
│   ├── build.gradle.kts          AGP config + generateMieDict task
│   └── src/main/
│       ├── AndroidManifest.xml   InputMethodService declaration
│       ├── java/.../mokyaime/
│       │   ├── MokyaImeService.kt   engine ↔ InputConnection plumbing
│       │   ├── DictionaryAsset.kt   memory-maps the dictionary asset
│       │   └── LruStore.kt          LRU persistence (filesDir/mie_lru.bin)
│       ├── cpp/
│       │   ├── CMakeLists.txt    builds libmokyaime_jni (Android and host)
│       │   ├── mie_jni.cpp       JNI bridge (RegisterNatives, IImeListener)
│       │   └── libmie/           git submodule → tengigabytes/libmie
│       └── res/xml/method.xml    IME subtype
└── mie-engine/                   pure-JVM Kotlin binding (:mie-engine)
    └── src/
        ├── main/kotlin/.../engine/  MieEngine, MieListener, MokyaKeys, MieNative
        └── test/                    host tests of the real JNI bridge
```

`:mie-engine` has no Android dependency, so its tests load a host build of
the same `mie_jni.cpp` and run on any JDK, without an emulator.

## Building

Requirements: JDK 17+ (CI uses 21), the Android SDK with NDK and CMake
3.22.1 (Android Gradle Plugin installs missing ones), Python 3.8+, and
network access for the first build.

```sh
git clone --recurse-submodules https://github.com/tengigabytes/mokya-ime-android
cd mokya-ime-android
./gradlew :app:assembleDebug      # APK in app/build/outputs/apk/debug/
./gradlew :mie-engine:test        # host tests (needs cmake + a C++ compiler)
```

The dictionary asset `dict_mie_v4.bin` (about 4 MB) is generated during the
build by `:app:generateMieDict`, using libmie's own tools:

1. `tools/fetch_data.py --only tsi.csv en_50k.txt` downloads the sources
   into `.mie-data/` (gitignored, reused by later builds);
2. `tools/gen_dict.py` builds the MIE4 v4 blob with the embedded English
   dictionary into `app/build/generated/`.

Neither the sources nor the generated dictionary are committed. To build
offline, pass an existing dictionary: `-Pmokya.dict=/path/to/dict_mie_v4.bin`.
`-Pmokya.python=python` overrides the Python executable. The asset is stored
uncompressed and memory-mapped at runtime.

Optional: `./gradlew :mie-engine:test -Pmokya.realDict=/path/to/dict_mie_v4.bin`
also runs a smoke test against a full dictionary.

## How the service drives the engine

All engine calls run on the main thread. Key events go in through
`MokyaImeService.dispatchKey(keycode, pressed, flags, eventTimeMs)` (keycodes
in `MokyaKeys`, mirroring libmie's `keycode.h`), and each call is wrapped in
one InputConnection batch edit.

| Engine | Android |
|--------|---------|
| `on_commit` | `commitText`; the idle-OK `"\n"` goes through `sendKeyChar('\n')` so editor actions (Send, Search…) work |
| `pending_view` | `setComposingText`, matched prefix in bold |
| `on_delete_before` | `deleteSurroundingText(1, 0)`, or 2 for a surrogate pair; deletes the selection if there is one |
| `on_cursor_move` | `sendDownUpKeyEvents(KEYCODE_DPAD_*)` |
| `set_text_context` | `getTextBeforeCursor(2, 0)` in `onUpdateSelection`, once our edits have landed |
| `abort` | `onFinishInput`, or when `onUpdateSelection` shows the cursor was moved by someone else |
| `now_ms` | `SystemClock.uptimeMillis()` (same base as `KeyEvent.getEventTime()`) |
| `tick` | `Handler` every 20 ms, only while `MieEngine.needsTick` (something pending, or SYM1 held for the long-press picker) |

The dictionary is a read-only memory map of the APK asset, and the native
engine holds a global reference to that buffer for its whole life. The
personalised LRU is loaded in `onCreate` and saved atomically to
`filesDir/mie_lru.bin` in `onFinishInput` / `onDestroy` (only when it
changed). App backup is disabled because the file holds recently typed words.

Known limitations: if the cursor moves (e.g. a DPAD press) and a key is
typed before the editor reports the new position, a stale report can be
mistaken for an external move, which discards the pending composition. The
LRU timestamps use uptime, which restarts at boot; MokyaLora has the same
behaviour.

## Open decisions

- Front end: touch keyboard (MokyaLora half-keyboard layout), hardware
  keyboard, or both.
- Licence of this app.
- Minimum Android version (`minSdk` is a provisional 24).

## Third-party data

The generated dictionary is built from third-party data with its own
licences, separate from libmie (MIT) and from this app:

| Source | Used for | Licence |
|--------|----------|---------|
| [libchewing-data](https://github.com/chewing/libchewing-data) `dict/chewing/tsi.csv` @ `ea74f76` | Chinese words and readings | LGPL-2.1-or-later, © 2025 libchewing Core Team (declared in the file header) |
| [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) `content/2018/en/en_50k.txt` | English words and frequencies | CC BY-SA 4.0 for content (MIT for code), © 2016 Hermit Dave; derived from the OpenSubtitles 2018 corpus (OPUS) |

Any distributed APK must carry the matching notices; this skeleton does not
include them yet.
