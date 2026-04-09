-keepattributes *Annotation*
-keep class com.robloxblocker.** { *; }
-dontwarn com.robloxblocker.**
-keep class android.net.VpnService { *; }
-keepclassmembers class * {
    public <init>(android.content.Context);
}
