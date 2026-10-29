# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Line Numbers for easier debugging of crashes in production
-keepattributes SourceFile,LineNumberTable



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


-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe

# Missing classes from Apache HttpClient dependencies (not on Android)
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**

# --- MSAL & Azure Identity ---
-dontwarn com.google.crypto.tink.subtle.**
-dontwarn com.microsoft.device.display.**
-dontwarn com.microsoft.identity.client.**
-keep class com.microsoft.identity.client.** { *; }

# --- Microsoft Graph & Kiota & Reactor ---
-dontwarn io.micrometer.context.**
-dontwarn reactor.blockhound.integration.**
-dontwarn reactor.core.publisher.**
-dontwarn reactor.util.context.**
-dontwarn com.microsoft.graph.**
-dontwarn com.microsoft.kiota.**
-keep class com.microsoft.graph.** { *; }
-keep class com.microsoft.kiota.** { *; }
