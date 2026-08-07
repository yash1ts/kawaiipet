package com.kawaiipet.app.audio

import android.content.Context
import android.util.Log
import java.io.File

class ModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun getModelDir(modelId: String): File = File(modelsDir, modelId)

    /**
     * Copies any voice model bundled under `assets/models/<id>/` into
     * `files/models/<id>/` exactly once. Idempotent (guarded by a marker file)
     * and safe to call from multiple threads. Call before checking
     * [isModelDownloaded] so bundled voices work with no adb push.
     */
    fun installBundledModelsIfNeeded() = synchronized(installLock) {
        val ids = runCatching { context.assets.list(ASSET_MODELS_DIR) }.getOrNull() ?: return
        for (id in ids) {
            val destDir = getModelDir(id)
            val assetDir = "$ASSET_MODELS_DIR/$id"
            val entries = runCatching { context.assets.list(assetDir) }.getOrNull().orEmpty()
            if (entries.isEmpty()) continue

            // Copy any missing bundled files even when a prior install marker
            // exists (e.g. cmudict added after first install).
            val missing = entries.filter { name -> !File(destDir, name).isFile }
            if (missing.isEmpty() && File(destDir, MARKER_FILE).exists()) continue

            destDir.mkdirs()
            var ok = true
            val toCopy = if (File(destDir, MARKER_FILE).exists()) missing else entries.toList()
            for (name in toCopy) {
                try {
                    context.assets.open("$assetDir/$name").use { input ->
                        File(destDir, name).outputStream().use { out -> input.copyTo(out) }
                    }
                } catch (e: Exception) {
                    ok = false
                    Log.e(TAG, "Failed copying bundled model asset $assetDir/$name", e)
                }
            }
            if (ok) {
                File(destDir, MARKER_FILE).writeText("ok")
                Log.i(TAG, "Installed bundled model: $id (${toCopy.size} files)")
            }
        }
    }

    fun resolveSherpaStreamingTransducer(modelId: String): SherpaStreamingTransducerPaths? {
        val base = getModelDir(modelId)
        if (!base.isDirectory) return null
        val roots = modelRoots(base)
        for (root in roots) {
            val tokens = File(root, "tokens.txt")
            if (!tokens.isFile) continue
            val encoder = pickSherpaOnnx(root, "encoder") ?: continue
            val decoder = pickSherpaOnnx(root, "decoder") ?: continue
            val joiner = pickSherpaOnnx(root, "joiner") ?: continue
            return SherpaStreamingTransducerPaths(
                tokensPath = tokens.absolutePath,
                encoderPath = encoder.absolutePath,
                decoderPath = decoder.absolutePath,
                joinerPath = joiner.absolutePath
            )
        }
        return null
    }

    fun resolveSherpaVitsTts(modelId: String): SherpaVitsTtsPaths? {
        val base = getModelDir(modelId)
        if (!base.isDirectory) return null
        val roots = modelRoots(base)
        for (root in roots) {
            val tokens = File(root, "tokens.txt")
            if (!tokens.isFile) continue
            val espeak = File(root, "espeak-ng-data")
            if (!espeak.isDirectory) continue
            val onnxFiles = root.listFiles()
                ?.filter { f -> f.isFile && f.name.endsWith(".onnx", ignoreCase = true) }
                .orEmpty()
            if (onnxFiles.isEmpty()) continue
            val modelFile = onnxFiles
                .filter { !it.name.contains(".int8.", ignoreCase = true) }
                .maxByOrNull { it.length() }
                ?: onnxFiles.maxByOrNull { it.length() }
                ?: continue
            val lex = File(root, "lexicon.txt")
            val lexPath = if (lex.isFile) lex.absolutePath else ""
            val dict = File(root, "dict")
            val dictPath = if (dict.isDirectory) dict.absolutePath else ""
            return SherpaVitsTtsPaths(
                modelPath = modelFile.absolutePath,
                tokensPath = tokens.absolutePath,
                dataDirPath = espeak.absolutePath,
                lexiconPath = lexPath,
                dictDirPath = dictPath
            )
        }
        return null
    }

    fun resolveSherpaMoonshine(modelId: String): SherpaMoonshinePaths? {
        val base = getModelDir(modelId)
        if (!base.isDirectory) return null
        for (root in modelRoots(base)) {
            val p = resolveSherpaMoonshineFromRoot(root) ?: continue
            return p
        }
        return null
    }

    /** NeMo EncDec CTC: `model.int8.onnx` (preferred) or `model.onnx` + `tokens.txt`. */
    fun resolveSherpaNemoCtc(modelId: String): SherpaNemoCtcPaths? {
        val base = getModelDir(modelId)
        if (!base.isDirectory) return null
        for (root in modelRoots(base)) {
            val p = resolveSherpaNemoCtcFromRoot(root) ?: continue
            return p
        }
        return null
    }

    private fun resolveSherpaNemoCtcFromRoot(root: File): SherpaNemoCtcPaths? {
        val tokens = File(root, "tokens.txt")
        if (!tokens.isFile) return null
        val int8 = File(root, "model.int8.onnx")
        val fp32 = File(root, "model.onnx")
        val model = when {
            int8.isFile -> int8
            fp32.isFile -> fp32
            else -> root.listFiles()?.firstOrNull { f ->
                f.isFile && f.name.endsWith(".onnx", ignoreCase = true) &&
                    f.name.contains("model", ignoreCase = true)
            } ?: return null
        }
        return SherpaNemoCtcPaths(
            modelPath = model.absolutePath,
            tokensPath = tokens.absolutePath,
        )
    }

    private fun resolveSherpaMoonshineFromRoot(root: File): SherpaMoonshinePaths? {
        val tokens = File(root, "tokens.txt")
        if (!tokens.isFile) return null
        val files = root.listFiles() ?: return null
        val encoder = files.firstOrNull { f ->
            f.isFile && f.name.startsWith("encoder", ignoreCase = true) &&
                (f.name.endsWith(".ort", ignoreCase = true) || f.name.endsWith(".onnx", ignoreCase = true))
        } ?: return null
        val merged = files.firstOrNull { f ->
            f.isFile &&
                (f.name.endsWith(".ort", ignoreCase = true) || f.name.endsWith(".onnx", ignoreCase = true)) &&
                f.name.contains("merged", ignoreCase = true)
        } ?: files.firstOrNull { f ->
            f.isFile &&
                (f.name.endsWith(".ort", ignoreCase = true) || f.name.endsWith(".onnx", ignoreCase = true)) &&
                f.name.startsWith("decoder", ignoreCase = true)
        } ?: return null
        return SherpaMoonshinePaths(
            encoderPath = encoder.absolutePath,
            mergedDecoderPath = merged.absolutePath,
            tokensPath = tokens.absolutePath
        )
    }

    fun resolveSherpaKittenTts(modelId: String): SherpaKittenTtsPaths? {
        val base = getModelDir(modelId)
        if (!base.isDirectory) return null
        for (root in modelRoots(base)) {
            val p = resolveSherpaKittenTtsFromRoot(root) ?: continue
            return p
        }
        return null
    }

    private fun resolveSherpaKittenTtsFromRoot(root: File): SherpaKittenTtsPaths? {
        val voices = File(root, "voices.bin")
        if (!voices.isFile) return null
        val tokens = File(root, "tokens.txt")
        val espeak = File(root, "espeak-ng-data")
        if (!tokens.isFile || !espeak.isDirectory) return null
        val onnxFiles = root.listFiles()
            ?.filter { f -> f.isFile && f.name.endsWith(".onnx", ignoreCase = true) }
            .orEmpty()
        if (onnxFiles.isEmpty()) return null
        val modelFile = onnxFiles
            .filter { !it.name.contains(".int8.", ignoreCase = true) }
            .maxByOrNull { it.length() }
            ?: onnxFiles.maxByOrNull { it.length() }
            ?: return null
        return SherpaKittenTtsPaths(
            modelPath = modelFile.absolutePath,
            voicesPath = voices.absolutePath,
            tokensPath = tokens.absolutePath,
            dataDirPath = espeak.absolutePath
        )
    }

    fun isModelDownloaded(modelId: String): Boolean {
        val dir = getModelDir(modelId)
        if (!dir.exists()) return false
        if (File(dir, MARKER_FILE).exists()) return true
        return hasUsableModelContent(dir)
    }

    /** Marks [modelId] as successfully installed after a network download. */
    fun markModelOk(modelId: String) {
        val dir = getModelDir(modelId)
        dir.mkdirs()
        File(dir, MARKER_FILE).writeText("ok")
    }

    private fun modelRoots(base: File): List<File> = buildList {
        add(base)
        base.listFiles()?.sortedBy { it.name }?.forEach { if (it.isDirectory) add(it) }
    }

    private fun hasUsableModelContent(dir: File): Boolean {
        var fileCount = 0
        var totalBytes = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                fileCount++
                totalBytes += f.length()
            }
        }
        return fileCount >= 2 && totalBytes > 10_000L
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val MARKER_FILE = ".kawaiipet_model_ok"
        private const val ASSET_MODELS_DIR = "models"
        private val installLock = Any()

        private fun pickSherpaOnnx(dir: File, role: String): File? {
            val files = dir.listFiles() ?: return null
            val candidates = files.filter { f ->
                f.isFile &&
                    f.name.endsWith(".onnx", ignoreCase = true) &&
                    f.name.startsWith(role, ignoreCase = true)
            }
            if (candidates.isEmpty()) return null
            return candidates.sortedWith(compareBy { it.name.contains(".int8.") }).first()
        }
    }
}

/** Absolute paths for Sherpa streaming transducer (encoder/decoder/joiner + tokens). */
data class SherpaStreamingTransducerPaths(
    val tokensPath: String,
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String
)

/** Piper VITS layout: *.onnx + tokens.txt + espeak-ng-data/. */
data class SherpaVitsTtsPaths(
    val modelPath: String,
    val tokensPath: String,
    val dataDirPath: String,
    val lexiconPath: String,
    val dictDirPath: String
)

/** Moonshine v2: encoder + merged decoder (.ort or .onnx) + tokens.txt */
data class SherpaMoonshinePaths(
    val encoderPath: String,
    val mergedDecoderPath: String,
    val tokensPath: String
)

/** NeMo EncDec CTC: single onnx + tokens.txt */
data class SherpaNemoCtcPaths(
    val modelPath: String,
    val tokensPath: String,
)

/** Kitten TTS: model.onnx + voices.bin (raw floats) + tokens.txt + espeak-ng-data/. */
data class SherpaKittenTtsPaths(
    val modelPath: String,
    val voicesPath: String,
    val tokensPath: String,
    val dataDirPath: String
)
