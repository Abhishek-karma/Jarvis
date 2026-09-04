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
