# R8 rules for the watch app's release build.
# bitkit-core ships its own consumer rules (JNA structures, callbacks, UniffiLib natives and com.sun.jna).
# The Breez SDK Spark AAR ships none, so the same uniffi/JNA rules are applied to its packages here.

# JNA reads @Structure.FieldOrder and public fields by name, and constructs
# Structure, Structure$ByValue and Structure$ByReference types reflectively.
-keepclassmembers class breez_sdk_spark.** extends com.sun.jna.Structure {
    <fields>;
    <init>(...);
}

# JNA looks up Callback methods by name when building native function pointers.
-keepclassmembers class breez_sdk_spark.** implements com.sun.jna.Callback {
    <methods>;
}

# Native.register maps the native methods by their exact C symbol names.
-keepclasseswithmembers,includedescriptorclasses class breez_sdk_spark.** {
    native <methods>;
}

# JNA reads Structure.FieldOrder at runtime, which R8 full mode strips unless the annotated classes are kept.
-keepattributes RuntimeVisibleAnnotations
-keep,allowshrinking,allowoptimization class com.sun.jna.Structure$FieldOrder
-keep,allowshrinking,allowoptimization,allowobfuscation @com.sun.jna.Structure$FieldOrder class breez_sdk_spark.** {
    <fields>;
    <init>(...);
}

# Keep line numbers for crash reports, with the source file name hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
