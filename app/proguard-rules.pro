# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep the Xposed module entry class
-keep class com.sky.xposed.rimet.Main { *; }

# Keep all plugin and data classes referenced by reflection
-keep class com.sky.xposed.rimet.** { *; }

# Keep libxposed API
-keep class io.github.libxposed.** { *; }
