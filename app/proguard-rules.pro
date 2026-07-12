-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ML Kit GenAI Prompt API (on-device Gemini Nano)
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**

# Strip verbose logs in release (keep w/e for diagnostics)
-assumenosideffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
