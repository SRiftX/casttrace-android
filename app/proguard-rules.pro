# Native entry points and callback classes used by the locally supplied FFmpegKit.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
