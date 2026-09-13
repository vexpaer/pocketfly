# PocketFly release rules.

# JNI entry points.
-keepclasseswithmembernames class io.github.pocketfly.sim.PocketFlyRuntime {
    native <methods>;
}

# kotlinx-serialization serializers for the game/brain schemas.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class io.github.pocketfly.**$$serializer { *; }
-keepclassmembers class io.github.pocketfly.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.pocketfly.** {
    kotlinx.serialization.KSerializer serializer(...);
}
