Sherpa-ONNX Android AAR (16 KB page-size compatible) is downloaded automatically before build:
  Gradle task: downloadSherpaOnnxAar
  Source: https://github.com/k2-fsa/sherpa-onnx/releases

The file sherpa-onnx-1.13.4.aar is gitignored (~47 MB).
KittenTTS v0.8 requires sherpa-onnx >= 1.13.x (style_dim / max_token_len metadata).

Do not add piper-plus AARs or voice weights under assets/models/ — TTS/STT/LLM/VAD
download at first launch (KittenTTS / Moonshine / Silero VAD / LiteRT-LM).
