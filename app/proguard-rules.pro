-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }

# JNA referencia java.awt.* que no existe en Android
-dontwarn java.awt.**
-dontwarn javax.swing.**

# Tink / security-crypto anotaciones compile-only
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# NativeSurfaceBridge JNI methods
-keep class dog.bald.arcade.engine.NativeSurfaceBridge { *; }

# Kotlinx Serialization DTOs
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.SerialName <fields>;
}
-keep class * implements kotlinx.serialization.KSerializer {
    <init>(...);
}
