-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }

# JNA referencia java.awt.* que no existe en Android
-dontwarn java.awt.**
-dontwarn javax.swing.**

# Tink / security-crypto anotaciones compile-only
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
