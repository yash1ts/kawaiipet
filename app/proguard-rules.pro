-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM / SmolLM
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Strip verbose logs in release (keep w/e for diagnostics)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
