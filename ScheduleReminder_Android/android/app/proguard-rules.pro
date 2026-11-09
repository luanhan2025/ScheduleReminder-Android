# JNI/JNA and generated UniFFI bindings use reflection.
-keep class com.sun.jna.** { *; }
-keep class com.scheduler.reminder.core.** { *; }
-dontwarn com.sun.jna.**
