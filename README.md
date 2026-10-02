# Mokya IME for Android

An Android input method built on [libmie](https://github.com/tengigabytes/libmie),
the MokyaInput Engine from the [MokyaLora](https://github.com/tengigabytes/MokyaLora)
feature phone: Bopomofo half-keyboard input for Traditional Chinese, English
prediction and multi-tap direct input, on the touch screen and on hardware
keyboards.

## Using it

Install the app, open **Mokya IME**, enable it and switch to it (the setup
screen has buttons for both and a field to try it in).

**On-screen keyboard.** The layout follows the MokyaLora keypad: OK and ⌫
on top, then the 5×5 core input area. The D-pad is left out: candidates are
tapped, and the cursor is placed by touching the text. Each key carries two
Bopomofo symbols (ㄞㄢㄦ has three):

- Tap a key to match any of its symbols. Hold it (500 ms) to pin the first
  symbol: the key vibrates and turns amber when it is pinned. Hold again
  within 800 ms to cycle to the next.
- **MODE** cycles 中 → EN → ABC. **，SYM** types ，; hold it for the symbol
  table. **。.？** cycles 。？！.
- Tap a candidate in the strip to enter it. **‹ ›** at the ends of the strip
  page through the candidates (a swipe scrolls it too). **OK** enters the
  highlighted candidate, and **⇥** moves the highlight to the next page.
- With nothing pending, OK turns blue and shows what it will do: the
  field's action (Send, Search, Next…) or ↵ for a new line. While
  composing it reads **OK** and only commits.
- **ABC** shows QWERTY with a number row instead of multi-tap. **⇧** shifts
  the next character (capitals, and the symbols ! @ # … on the number row);
  tap it twice to lock. Number, phone, password, e-mail and URL fields
  start in ABC.

**Hardware keyboard.**

- **中**: the standard Zhuyin (Dachen, 大千) layout; each key types its exact
  Bopomofo symbol. Space marks tone 1. Enter commits the highlighted
  candidate, arrows / Tab navigate, and Esc cancels.
- **EN**: letters predict words, everything else is typed as is.
- **ABC**: the keyboard types normally.
- **Ctrl+Space** (or the language key) cycles the modes.
- In 中, **Shift** types capitals and full-width punctuation
  (， 。 ？ ： ； ！ 『』 （）), `[` `]` type 「」 and `'` types 、.

Passwords, numbers, phone numbers, dates, e-mail addresses and URLs start
in ABC. What you type in password fields, or in fields that request no
personalised learning, is not remembered.

## Layout

```
mokya-ime-android/
├── app/                          Android application (:app)
│   ├── build.gradle.kts          AGP config, generateMieDict + collectLicenses tasks
│   └── src/
│       ├── main/java/.../mokyaime/
│       │   ├── MokyaImeService.kt   engine ↔ InputConnection, touch + hardware input
│       │   ├── ui/KeyboardView.kt   on-screen half-keyboard (canvas)
│       │   ├── ui/CandidateStripView.kt  mode, candidates / symbol picker, position
│       │   ├── SetupActivity.kt     launcher + IME settings, try-it field
│       │   ├── LicensesActivity.kt  NOTICE and licence texts
│       │   ├── DictionaryAsset.kt   memory-maps the dictionary asset
│       │   └── LruStore.kt          LRU persistence (filesDir/mie_lru.bin)
│       ├── main/cpp/
│       │   ├── mie_jni.cpp       JNI bridge (RegisterNatives, IImeListener)
│       │   └── libmie/           git submodule → tengigabytes/libmie
│       └── androidTest/          end-to-end tests on a device / emulator
├── mie-engine/                   pure-JVM Kotlin (:mie-engine)
│   └── src/main/kotlin/.../
│       ├── engine/               MieEngine, MieListener, MokyaKeys, MieNative
│       └── input/                keyboard layout & labels, touch timing
│                                 (PressTracker), candidate paging (StripPaging),
│                                 HardwareKeyMapper, EditorPolicy
├── licenses/                     LGPL-2.1 and CC BY-SA 4.0 texts (dictionary data)
├── LICENSE                       Apache License 2.0
└── NOTICE
```

`:mie-engine` has no Android dependency, so its tests load a host build of
the same `mie_jni.cpp` and run on any JDK. That build takes `jni.h` from the
JDK running Gradle, so `JAVA_HOME` must be a full JDK (Android Studio's
bundled runtime has no headers). On Windows it uses Visual Studio (Build
Tools) through CMake's default generator.

## Building and testing

Requirements: JDK 17+ (CI uses 21), the Android SDK with NDK and CMake
3.22.1 (the Android Gradle Plugin installs missing ones), Python 3.8+, and
network access for the first build.

```sh
git clone --recurse-submodules https://github.com/tengigabytes/mokya-ime-android
cd mokya-ime-android
./gradlew :app:assembleDebug               # APK in app/build/outputs/apk/debug/
./gradlew :mie-engine:test                 # host tests (needs cmake + a C++ compiler)
./gradlew :app:connectedDebugAndroidTest   # end-to-end tests on a device / emulator
```

The dictionary asset `dict_mie_v4.bin` (about 4 MB) is generated during the
build by `:app:generateMieDict` with libmie's own tools:

1. `tools/fetch_data.py --only tsi.csv en_50k.txt` downloads the sources into
   `.mie-data/` (gitignored, reused by later builds);
2. `tools/gen_dict.py` builds the MIE4 v4 blob with the embedded English
   dictionary.

Neither the sources nor the generated dictionary are committed. To build
offline, pass an existing dictionary: `-Pmokya.dict=/path/to/dict_mie_v4.bin`.
`-Pmokya.python=python` overrides the Python executable.
`./gradlew :mie-engine:test -Pmokya.realDict=/path/to/dict_mie_v4.bin` also
runs a smoke test against a full dictionary.

CI (`.github/workflows/android.yml`) runs on every push and pull request:

- the host tests;
- `assembleDebug`, plus checks that the dictionary is stored uncompressed,
  the JNI library exists for every ABI and the licence texts are packaged.
  The debug APK is kept for 30 days as the run's `mokya-ime-debug-<commit>`
  artifact. It is signed with a fixed debug key, restored from the
  `MOKYA_DEBUG_KEYSTORE_B64` secret (base64 of a keystore with the SDK's
  debug passwords and alias), so a newer build installs over an older one
  and keeps the learned words. Without the secret (forks) each run uses a
  new key. Locally, `-Pmokya.debugKeystore=/path/to/keystore` (or the
  `MOKYA_DEBUG_KEYSTORE` environment variable) signs with the same key;
- the end-to-end tests on API 34 and API 36 emulators (API 35 made the IME
  window edge to edge). These type through injected hardware keys and
  touches on the on-screen keyboard.

## How the service drives the engine

All engine calls run on the main thread. Touch keys and hardware keys both
end up in `MokyaImeService.dispatchKey`. Each engine call is wrapped in one
InputConnection batch edit. Engine callbacks never call back into the
engine: `on_composition_changed` only marks the composition dirty, and the
composing text and UI are refreshed once the call returns.

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

The dictionary is a read-only memory map of the APK asset. The native
engine holds a global reference to that buffer for its whole life. The
personalised LRU is loaded in `onCreate` and saved atomically to
`filesDir/mie_lru.bin` in `onFinishInput` / `onDestroy`, only when it
changed. App backup is disabled because the file holds recently typed words.

Known limitations:

- If the cursor moves (e.g. a DPAD press) and a key is typed before the
  editor reports the new position, a stale report can be mistaken for an
  external move, which discards the pending composition.
- LRU timestamps use uptime, which restarts at boot; MokyaLora behaves the
  same.
- The on-screen keyboard has no accessibility (TalkBack) support yet.

## Licence

Following MokyaLora's split, where the UI/application firmware (Core 1) is
Apache-2.0 and the engine is MIT:

| Component | Licence |
|-----------|---------|
| This app (`app/`, `mie-engine/`) | Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE) |
| libmie (`app/src/main/cpp/libmie`) | MIT |
| Generated dictionary (`dict_mie_v4.bin` in the APK) | Third-party data licences, see below |

The app shows NOTICE and every licence text under *Open-source licences*
on its setup screen.

### Third-party data

The generated dictionary is built from:

| Source | Used for | Licence |
|--------|----------|---------|
| [libchewing-data](https://github.com/chewing/libchewing-data) `dict/chewing/tsi.csv` @ `ea74f76` | Chinese words and readings | LGPL-2.1-or-later, © 2025 libchewing Core Team (declared in the file header) |
| [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) `content/2018/en/en_50k.txt` | English words and frequencies | CC BY-SA 4.0 for content (MIT for code), © 2016 Hermit Dave; derived from the OpenSubtitles 2018 corpus (OPUS) |

When publishing an APK:

- **LGPL-2.1 source.** Make the dictionary's corresponding source available
  from the same place, e.g. attach `tsi.csv` (pinned commit) to the release
  next to the APK. The conversion tool `gen_dict.py` and the exact command
  are in libmie and in `app/build.gradle.kts`.
- **FrequencyWords pin.** `fetch_data.py` currently downloads FrequencyWords
  from `master`. Pin it to a commit for reproducible, attributable releases.
