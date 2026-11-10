# Pancerz aplikacji Studio Kanalow TV
# media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
# Nasze modele (uzywane przez JSON) - trzymaj nazwy pol
-keep class uk.okok.studiokanalow.model.** { *; }
