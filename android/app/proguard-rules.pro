# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class com.jtwolfe.glass.data.** { *; }
-keep class com.jtwolfe.glass.network.** { *; }
