# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Line Numbers for easier debugging of crashes in production
-keepattributes SourceFile,LineNumberTable

# --- Google API Client & Drive API ---
-keep class com.google.api.client.** { *; }
-keep interface com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep interface com.google.api.services.drive.** { *; }

# --- Gson / JSON ---
# Keep classes used for JSON serialization/deserialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
# If you have data classes for JSON (none currently in this project,
# as we use JSONObject directly, but good to have)
#-keep class com.cavearchive.app.models.** { *; }

# --- Guava ---
-dontwarn com.google.common.**
-keep class com.google.common.** { *; }

# --- Generic reflection rules for Google Libraries ---
-keepclassmembers class * {
  @com.google.api.client.util.Key <fields>;
}
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe

# Missing classes from Apache HttpClient dependencies (not on Android)
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**
