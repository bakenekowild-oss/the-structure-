# Hey Gobi — ProGuard rules (kept minimal; minification is off in both
# build types, but the file must exist because build.gradle.kts references it).
# JNI callback methods in LlamaBridge must never be renamed or removed.
-keep class com.heygobi.app.brain.LlamaBridge {
    void onTokenGenerated(java.lang.String);
    void onLoadProgress(float);
    void onGenerationFinished();
}
-keep class com.heygobi.app.brain.LlamaBridge {
    native <methods>;
}
