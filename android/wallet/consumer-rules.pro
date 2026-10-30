# JNA y los bindings de UniFFI se cargan por reflexión: no renombrar ni quitar.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class x39.wallet.** { *; }
-dontwarn java.awt.**
