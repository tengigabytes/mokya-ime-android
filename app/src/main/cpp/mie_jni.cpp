// SPDX-License-Identifier: Apache-2.0
// mie_jni.cpp — JNI bridge between the Kotlin IME service and libmie.
//
// One native Engine per MieEngine (Kotlin). It owns the dictionary searchers,
// the mie::ImeLogic instance and a listener that forwards IImeListener events
// to a Kotlin callbacks object (io.github.tengigabytes.mokyaime.engine
// .NativeCallbacks).
//
// Threading: ImeLogic is single-threaded and not re-entrant. The Kotlin side
// calls every entry point on the main thread, and listener callbacks run
// synchronously inside the native call that triggered them, on the same
// thread and with the same JNIEnv. The JNIEnv is therefore stored only for
// the duration of a call (EnvScope) and never cached across calls.
//
// Strings: MIE speaks standard UTF-8, while JNI's NewStringUTF /
// GetStringUTFChars use *modified* UTF-8, which cannot carry 4-byte
// sequences (CheckJNI aborts on them). The dictionary contains characters
// outside the BMP (CJK Extension B), so all strings cross the boundary as
// UTF-16 and are converted here.
//
// Natives are bound with RegisterNatives in JNI_OnLoad, so a signature
// mismatch with MieNative.kt fails loudly at System.loadLibrary() time.

#include <jni.h>

#include <mie/composition_searcher.h>
#include <mie/ime_logic.h>
#include <mie/keycode.h>
#include <mie/trie_searcher.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

namespace {

constexpr const char* kNativeClass = "io/github/tengigabytes/mokyaime/engine/MieNative";

jclass g_string_class = nullptr;   // global ref, set in JNI_OnLoad

// ── UTF-8 <-> UTF-16 ─────────────────────────────────────────────────────

void append_utf16(std::u16string& out, uint32_t cp) {
    if (cp >= 0x10000) {
        cp -= 0x10000;
        out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
        out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    } else {
        out.push_back(static_cast<char16_t>(cp));
    }
}

// Decodes standard UTF-8. Malformed input becomes U+FFFD rather than being
// passed on, so a corrupt dictionary entry cannot produce an invalid String.
std::u16string utf8_to_utf16(const char* s, size_t n) {
    static const uint32_t kMinForLen[5] = {0, 0, 0x80, 0x800, 0x10000};
    std::u16string out;
    out.reserve(n);
    size_t i = 0;
    while (i < n) {
        const uint8_t b = static_cast<uint8_t>(s[i]);
        uint32_t cp;
        size_t len;
        if (b < 0x80)                { cp = b;        len = 1; }
        else if ((b & 0xE0) == 0xC0) { cp = b & 0x1F; len = 2; }
        else if ((b & 0xF0) == 0xE0) { cp = b & 0x0F; len = 3; }
        else if ((b & 0xF8) == 0xF0) { cp = b & 0x07; len = 4; }
        else { out.push_back(0xFFFD); ++i; continue; }

        if (i + len > n) { out.push_back(0xFFFD); break; }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            const uint8_t c = static_cast<uint8_t>(s[i + k]);
            if ((c & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (c & 0x3F);
        }
        if (!ok || cp < kMinForLen[len] || cp > 0x10FFFF ||
            (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }
        append_utf16(out, cp);
        i += len;
    }
    return out;
}

void append_utf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// Encodes UTF-16 as standard UTF-8; unpaired surrogates become U+FFFD so
// the result always splits on code point boundaries.
std::string utf16_to_utf8(const jchar* s, size_t n) {
    std::string out;
    out.reserve(n * 3);
    for (size_t i = 0; i < n; ++i) {
        uint32_t cp = s[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n &&
            s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (s[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        append_utf8(out, cp);
    }
    return out;
}

jstring new_jstring(JNIEnv* env, const char* utf8, size_t n) {
    const std::u16string u16 = utf8_to_utf16(utf8, n);
    return env->NewString(reinterpret_cast<const jchar*>(u16.data()),
                          static_cast<jsize>(u16.size()));
}

jstring new_jstring(JNIEnv* env, const char* utf8) {
    return new_jstring(env, utf8 ? utf8 : "", utf8 ? std::strlen(utf8) : 0);
}

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const jsize n = env->GetStringLength(s);
    std::vector<jchar> buf(static_cast<size_t>(n));
    if (n > 0) env->GetStringRegion(s, 0, n, buf.data());
    return utf16_to_utf8(buf.data(), buf.size());
}

void throw_new(JNIEnv* env, const char* cls, const char* msg) {
    jclass c = env->FindClass(cls);
    if (c) env->ThrowNew(c, msg);
}

// ── Listener: IImeListener -> Kotlin NativeCallbacks ─────────────────────

class JniListener : public mie::IImeListener {
public:
    JNIEnv*   env    = nullptr;   // set only while a native call is running
    jobject   target = nullptr;   // global ref to the NativeCallbacks object
    jmethodID on_commit_id              = nullptr;
    jmethodID on_cursor_move_id         = nullptr;
    jmethodID on_delete_before_id       = nullptr;
    jmethodID on_composition_changed_id = nullptr;

    void on_commit(const char* utf8) override {
        if (!ready()) return;
        jstring s = new_jstring(env, utf8);
        if (!s) return;   // OutOfMemoryError pending
        env->CallVoidMethod(target, on_commit_id, s);
        env->DeleteLocalRef(s);
    }

    void on_cursor_move(mie::NavDir dir) override {
        if (!ready()) return;
        env->CallVoidMethod(target, on_cursor_move_id, static_cast<jint>(dir));
    }

    void on_delete_before() override {
        if (!ready()) return;
        env->CallVoidMethod(target, on_delete_before_id);
    }

    void on_composition_changed() override {
        if (!ready()) return;
        env->CallVoidMethod(target, on_composition_changed_id);
    }

private:
    // Once a callback has thrown, no further JNI calls are legal until the
    // native method returns; the exception then propagates to Kotlin.
    bool ready() const { return env && target && !env->ExceptionCheck(); }
};

// ── Engine ───────────────────────────────────────────────────────────────

struct Engine {
    jobject                        dict_ref = nullptr;  // keeps the dictionary memory alive
    mie::CompositionSearcher       zh;
    mie::TrieSearcher              en;
    bool                           has_en = false;
    std::unique_ptr<mie::ImeLogic> ime;
    JniListener                    listener;
    // ImeLogic needs tick() while SYM1 is held (long-press picker) even
    // though nothing is pending; it does not expose that state, so mirror it.
    bool                           sym1_down = false;
};

// Binds the caller's JNIEnv to the listener for the duration of one call.
class EnvScope {
public:
    EnvScope(Engine* e, JNIEnv* env) : e_(e) { e_->listener.env = env; }
    ~EnvScope() { e_->listener.env = nullptr; }
    EnvScope(const EnvScope&) = delete;
    EnvScope& operator=(const EnvScope&) = delete;
private:
    Engine* e_;
};

Engine* from_handle(jlong h) { return reinterpret_cast<Engine*>(static_cast<intptr_t>(h)); }

jobjectArray new_string_array(JNIEnv* env, int n, const char* (*get)(const mie::ImeLogic&, int),
                              const mie::ImeLogic& ime) {
    jobjectArray arr = env->NewObjectArray(n, g_string_class, nullptr);
    if (!arr) return nullptr;
    for (int i = 0; i < n; ++i) {
        jstring s = new_jstring(env, get(ime, i));
        if (!s) return nullptr;
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

// ── Natives (see MieNative.kt for the Kotlin declarations) ───────────────

jlong native_create(JNIEnv* env, jclass, jobject dict, jobject callbacks) {
    if (!dict || !callbacks) {
        throw_new(env, "java/lang/NullPointerException", "dict and callbacks must be non-null");
        return 0;
    }
    const auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(dict));
    const jlong cap  = env->GetDirectBufferCapacity(dict);
    if (!base || cap <= 0) {
        throw_new(env, "java/lang/IllegalArgumentException",
                  "dictionary must be a non-empty direct ByteBuffer");
        return 0;
    }

    std::unique_ptr<Engine> e(new (std::nothrow) Engine());
    if (!e) {
        throw_new(env, "java/lang/OutOfMemoryError", "MIE engine");
        return 0;
    }
    if (!e->zh.load_from_memory(base, static_cast<size_t>(cap))) {
        throw_new(env, "java/lang/IllegalArgumentException", "not a MIE4 v4 dictionary");
        return 0;
    }

    const uint8_t *dat = nullptr, *val = nullptr;
    size_t dat_n = 0, val_n = 0;
    e->has_en = e->zh.english_sections(&dat, &dat_n, &val, &val_n) &&
                e->en.load_from_memory(dat, dat_n, val, val_n);

    jclass cb_class = env->GetObjectClass(callbacks);
    JniListener& l = e->listener;
    l.on_commit_id              = env->GetMethodID(cb_class, "onCommit", "(Ljava/lang/String;)V");
    if (l.on_commit_id)
        l.on_cursor_move_id     = env->GetMethodID(cb_class, "onCursorMove", "(I)V");
    if (l.on_cursor_move_id)
        l.on_delete_before_id   = env->GetMethodID(cb_class, "onDeleteBefore", "()V");
    if (l.on_delete_before_id)
        l.on_composition_changed_id = env->GetMethodID(cb_class, "onCompositionChanged", "()V");
    env->DeleteLocalRef(cb_class);
    if (!l.on_composition_changed_id) return 0;   // NoSuchMethodError pending

    e->ime.reset(new (std::nothrow) mie::ImeLogic(e->zh, e->has_en ? &e->en : nullptr));
    if (!e->ime) {
        throw_new(env, "java/lang/OutOfMemoryError", "MIE engine");
        return 0;
    }

    l.target    = env->NewGlobalRef(callbacks);
    e->dict_ref = env->NewGlobalRef(dict);
    if (!l.target || !e->dict_ref) {
        if (l.target)    env->DeleteGlobalRef(l.target);
        if (e->dict_ref) env->DeleteGlobalRef(e->dict_ref);
        return 0;   // OutOfMemoryError pending
    }
    e->ime->set_listener(&e->listener);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(e.release()));
}

void native_destroy(JNIEnv* env, jclass, jlong h) {
    Engine* e = from_handle(h);
    if (!e) return;
    e->ime.reset();   // before the searchers and the buffer it points into
    if (e->listener.target) env->DeleteGlobalRef(e->listener.target);
    jobject dict_ref = e->dict_ref;
    delete e;         // searchers reference the buffer; drop it last
    if (dict_ref) env->DeleteGlobalRef(dict_ref);
}

jboolean native_has_english(JNIEnv*, jclass, jlong h) {
    return from_handle(h)->has_en ? JNI_TRUE : JNI_FALSE;
}

jboolean native_process_key(JNIEnv* env, jclass, jlong h, jint keycode, jboolean pressed,
                            jlong now_ms, jint flags) {
    Engine* e = from_handle(h);
    EnvScope scope(e, env);
    if (keycode < 0 || keycode > 0xFF) return JNI_FALSE;
    mie::KeyEvent ev;
    ev.keycode = static_cast<mokya_keycode_t>(keycode);
    ev.pressed = pressed == JNI_TRUE;
    ev.now_ms  = static_cast<uint32_t>(now_ms);   // wraps after ~49.7 days; MIE uses deltas
    ev.flags   = static_cast<uint8_t>(flags);
    if (ev.keycode == MOKYA_KEY_SYM1) e->sym1_down = ev.pressed;
    return e->ime->process_key(ev) ? JNI_TRUE : JNI_FALSE;
}

jboolean native_tick(JNIEnv* env, jclass, jlong h, jlong now_ms) {
    Engine* e = from_handle(h);
    EnvScope scope(e, env);
    return e->ime->tick(static_cast<uint32_t>(now_ms)) ? JNI_TRUE : JNI_FALSE;
}

void native_abort(JNIEnv* env, jclass, jlong h) {
    Engine* e = from_handle(h);
    EnvScope scope(e, env);
    e->sym1_down = false;   // ImeLogic::abort() also forgets the SYM1 press
    e->ime->abort();
}

void native_set_text_context(JNIEnv* env, jclass, jlong h, jstring before) {
    Engine* e = from_handle(h);
    EnvScope scope(e, env);
    const std::string utf8 = jstring_to_utf8(env, before);
    e->ime->set_text_context(utf8.c_str());
}

jboolean native_needs_tick(JNIEnv*, jclass, jlong h) {
    Engine* e = from_handle(h);
    return (e->ime->has_pending() || e->sym1_down) ? JNI_TRUE : JNI_FALSE;
}

jboolean native_has_pending(JNIEnv*, jclass, jlong h) {
    return from_handle(h)->ime->has_pending() ? JNI_TRUE : JNI_FALSE;
}

jstring native_pending_text(JNIEnv* env, jclass, jlong h) {
    const mie::PendingView pv = from_handle(h)->ime->pending_view();
    return new_jstring(env, pv.str ? pv.str : "", pv.str ? static_cast<size_t>(pv.byte_len) : 0);
}

// Matched prefix of the pending string in UTF-16 code units (MIE reports bytes).
jint native_pending_matched_prefix(JNIEnv*, jclass, jlong h) {
    const mie::PendingView pv = from_handle(h)->ime->pending_view();
    if (!pv.str || pv.matched_prefix_bytes <= 0) return 0;
    return static_cast<jint>(
        utf8_to_utf16(pv.str, static_cast<size_t>(pv.matched_prefix_bytes)).size());
}

jint native_pending_style(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(from_handle(h)->ime->pending_view().style);
}

jint native_mode(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(from_handle(h)->ime->mode());
}

jobjectArray native_candidates(JNIEnv* env, jclass, jlong h) {
    const mie::ImeLogic& ime = *from_handle(h)->ime;
    return new_string_array(env, ime.candidate_count(),
                            [](const mie::ImeLogic& i, int k) { return i.candidate(k).word; }, ime);
}

jint native_selected(JNIEnv*, jclass, jlong h) {
    return from_handle(h)->ime->selected();
}

void native_set_selected(JNIEnv* env, jclass, jlong h, jint index) {
    Engine* e = from_handle(h);
    EnvScope scope(e, env);
    e->ime->set_selected(index);
}

jboolean native_picker_active(JNIEnv*, jclass, jlong h) {
    return from_handle(h)->ime->picker_active() ? JNI_TRUE : JNI_FALSE;
}

jint native_picker_cols(JNIEnv*, jclass, jlong h) {
    return from_handle(h)->ime->picker_cols();
}

jobjectArray native_picker_cells(JNIEnv* env, jclass, jlong h) {
    const mie::ImeLogic& ime = *from_handle(h)->ime;
    return new_string_array(env, ime.picker_cell_count(),
                            [](const mie::ImeLogic& i, int k) { return i.picker_cell(k); }, ime);
}

jint native_picker_selected(JNIEnv*, jclass, jlong h) {
    return from_handle(h)->ime->picker_selected();
}

jbyteArray native_serialize_lru(JNIEnv* env, jclass, jlong h) {
    const mie::ImeLogic& ime = *from_handle(h)->ime;
    std::vector<uint8_t> buf(static_cast<size_t>(ime.lru_serialized_size()));
    const int n = ime.serialize_lru(buf.data(), static_cast<int>(buf.size()));
    if (n < 0) {
        throw_new(env, "java/lang/IllegalStateException", "serialize_lru failed");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(n);
    if (out && n > 0) {
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(buf.data()));
    }
    return out;
}

jboolean native_load_lru(JNIEnv* env, jclass, jlong h, jbyteArray data) {
    if (!data) return JNI_FALSE;
    const jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> buf(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(buf.data()));
    return from_handle(h)->ime->load_lru(buf.data(), n) ? JNI_TRUE : JNI_FALSE;
}

#define MIE_NATIVE(name, sig, fn) \
    { const_cast<char*>(name), const_cast<char*>(sig), reinterpret_cast<void*>(fn) }

const JNINativeMethod kMethods[] = {
    MIE_NATIVE("create",               "(Ljava/nio/ByteBuffer;Ljava/lang/Object;)J", native_create),
    MIE_NATIVE("destroy",              "(J)V",                    native_destroy),
    MIE_NATIVE("hasEnglish",           "(J)Z",                    native_has_english),
    MIE_NATIVE("processKey",           "(JIZJI)Z",                native_process_key),
    MIE_NATIVE("tick",                 "(JJ)Z",                   native_tick),
    MIE_NATIVE("abort",                "(J)V",                    native_abort),
    MIE_NATIVE("setTextContext",       "(JLjava/lang/String;)V",  native_set_text_context),
    MIE_NATIVE("needsTick",            "(J)Z",                    native_needs_tick),
    MIE_NATIVE("hasPending",           "(J)Z",                    native_has_pending),
    MIE_NATIVE("pendingText",          "(J)Ljava/lang/String;",   native_pending_text),
    MIE_NATIVE("pendingMatchedPrefix", "(J)I",                    native_pending_matched_prefix),
    MIE_NATIVE("pendingStyle",         "(J)I",                    native_pending_style),
    MIE_NATIVE("mode",                 "(J)I",                    native_mode),
    MIE_NATIVE("candidates",           "(J)[Ljava/lang/String;",  native_candidates),
    MIE_NATIVE("selected",             "(J)I",                    native_selected),
    MIE_NATIVE("setSelected",          "(JI)V",                   native_set_selected),
    MIE_NATIVE("pickerActive",         "(J)Z",                    native_picker_active),
    MIE_NATIVE("pickerCols",           "(J)I",                    native_picker_cols),
    MIE_NATIVE("pickerCells",          "(J)[Ljava/lang/String;",  native_picker_cells),
    MIE_NATIVE("pickerSelected",       "(J)I",                    native_picker_selected),
    MIE_NATIVE("serializeLru",         "(J)[B",                   native_serialize_lru),
    MIE_NATIVE("loadLru",              "(J[B)Z",                  native_load_lru),
};

#undef MIE_NATIVE

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return JNI_ERR;
    g_string_class = static_cast<jclass>(env->NewGlobalRef(string_class));
    env->DeleteLocalRef(string_class);

    jclass native_class = env->FindClass(kNativeClass);
    if (!native_class) return JNI_ERR;
    const jint rc = env->RegisterNatives(native_class, kMethods,
                                         sizeof(kMethods) / sizeof(kMethods[0]));
    env->DeleteLocalRef(native_class);
    return rc == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
