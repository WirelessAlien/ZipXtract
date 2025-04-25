# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn org.brotli.dec.BrotliInputStream

# ASM
-dontwarn org.objectweb.asm.**

# Commons Compress
-dontwarn org.apache.commons.compress.harmony.**

# Keep JNI classes and methods
-keep class net.sf.sevenzipjbinding.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class me.zhanghai.android.libarchive.** { *; }
-keep class com.github.luben.zstd.** { *; }

# Keep native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep classes that are referenced by native code
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep all method names for classes that have native methods
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}