# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path/to/sdk/tools/proguard/proguard-android.txt

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }

# Keep Room entities
-keep class com.watermeter.data.model.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Hilt
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
