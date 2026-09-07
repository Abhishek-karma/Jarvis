# Jarvis release ProGuard/R8 rules (02-ARCHITECTURE.md §7)

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Moshi generated adapters
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonClass class *
-keep class com.jarvis.core.network.sse.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }

# LiteRT-LM (on-device engine): its native library (liblitertlm_jni.so) resolves
# SDK classes BY NAME through JNI. R8 renaming/removal (Conversation -> M1.d,
# Content removed) aborts the process on first use. Keep the whole SDK intact.
-keep class com.google.ai.edge.litertlm.** { *; }

# Moshi codegen adapters are constructed reflectively (Class.forName(name +
# "JsonAdapter") then getDeclaredConstructor). R8 strips their otherwise
# unreferenced constructors, so catalog parsing fails silently in release.
-keep class * extends com.squareup.moshi.JsonAdapter {
    <init>(...);
}

# core.ml model layer: Moshi reflects on LocalRuntime (enum) and the engine has
# JNI-adjacent callbacks; the module is small, keep it whole and name-stable.
-keep class com.jarvis.core.ml.** { *; }
