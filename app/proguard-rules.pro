# JNI bridge (app/src/main/cpp/mie_jni.cpp):
#  - JNI_OnLoad finds MieNative by name and binds its natives with
#    RegisterNatives, so the class and native method names must survive.
#  - Callback methods on NativeCallbacks are looked up by name from C++.
-keep class io.github.tengigabytes.mokyaime.engine.MieNative {
    native <methods>;
}
-keepclassmembers class io.github.tengigabytes.mokyaime.engine.NativeCallbacks {
    public void onCommit(java.lang.String);
    public void onCursorMove(int);
    public void onDeleteBefore();
    public void onCompositionChanged();
}
