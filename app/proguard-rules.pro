-keepattributes *Annotation*
-keep class com.robloxblocker.** { *; }
-dontwarn com.robloxblocker.**
-keepclassmembers class * {
    public <init>(android.content.Context);
}
