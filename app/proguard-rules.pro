# Horn release rules. R8 keeps the manifest-declared entry points automatically;
# these rules preserve readable stack traces and guard reflection-based framework use.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses
-renamesourcefileattribute SourceFile

-keep class com.industrial.horn.MainActivity { *; }
-keep class com.industrial.horn.HornApplication { *; }

# JNI bridge: names must remain stable for Oboe's native entry points.
-keep class com.industrial.horn.audio.OboeHornAudioEngine { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Do not ship verbose/debug logging in release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
