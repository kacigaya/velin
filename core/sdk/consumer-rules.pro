# Upstream tuta-sdk/android/sdk/consumer-rules.pro:21-31. JNA calls these through reflection/native code.
-dontwarn java.awt.*
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
