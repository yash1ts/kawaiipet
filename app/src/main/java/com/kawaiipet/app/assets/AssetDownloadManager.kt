package com.kawaiipet.app.assets

import android.content.Context
import android.util.Log
import com.kawaiipet.app.audio.ModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

sealed interface AssetDownloadState {
    data object Checking : AssetDownloadState
    data object Ready : AssetDownloadState

    data class Downloading(
        val assetName: String,
        val assetIndex: Int,
        val assetCount: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val overallFraction: Float,
        val completedIds: Set<String>,
        val currentId: String,
    ) : AssetDownloadState

    /** Unpack / install after a file finished downloading (can take a while for voice packs). */
    data class Installing(
        val assetName: String,
        val assetIndex: Int,
        val assetCount: Int,
        val overallFraction: Float,
        val completedIds: Set<String>,
        val currentId: String,
    ) : AssetDownloadState

    data class Failed(
        val message: String,
        val completedIds: Set<String>,
    ) : AssetDownloadState
}

@Singleton
class AssetDownloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var activeJob: Job? = null

    private val _state = MutableStateFlow<AssetDownloadState>(AssetDownloadState.Checking)
    val state: StateFlow<AssetDownloadState> = _state.asStateFlow()

    fun areAllAssetsReady(): Boolean = RequiredAssets.ALL.all { isAssetReady(it) }

    fun completedIds(): Set<String> =
        RequiredAssets.ALL.filter { isAssetReady(it) }.map { it.id }.toSet()

    fun refresh() {
        if (activeJob?.isActive == true) return
        _state.value = if (areAllAssetsReady()) {
            AssetDownloadState.Ready
        } else {
            AssetDownloadState.Checking
        }
    }

    /** Starts download on an app-scoped job so leaving MainActivity does not cancel it. */
    fun ensureAssetsDownloadedAsync() {
        if (areAllAssetsReady()) {
            _state.value = AssetDownloadState.Ready
            return
        }
        if (activeJob?.isActive == true) return
        activeJob = appScope.launch {
            ensureAssetsDownloaded()
        }
    }

    private suspend fun ensureAssetsDownloaded() = mutex.withLock {
        if (areAllAssetsReady()) {
            _state.value = AssetDownloadState.Ready
            return@withLock
        }

        val missing = RequiredAssets.ALL.filterNot { isAssetReady(it) }
        val total = RequiredAssets.ALL.size
        val already = total - missing.size

        try {
            missing.forEachIndexed { i, spec ->
                val index = already + i
                downloadOne(spec, index, total)
            }
            if (!areAllAssetsReady()) {
                error("Assets still incomplete after download")
            }
            _state.value = AssetDownloadState.Ready
            Log.i(TAG, "All runtime assets ready")
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Asset download failed", t)
            _state.value = AssetDownloadState.Failed(
                message = friendlyError(t),
                completedIds = completedIds(),
            )
        }
    }

    private fun isAssetReady(spec: AssetSpec): Boolean {
        if (!modelManager.isModelDownloaded(spec.id)) return false
        return when (val kind = spec.kind) {
            is AssetKind.RawFile -> {
                File(modelManager.getModelDir(spec.id), kind.fileName).isFile
            }
            AssetKind.TarBz2 -> true
        }
    }

    private suspend fun downloadOne(spec: AssetSpec, index: Int, count: Int) =
        withContext(Dispatchers.IO) {
            val destDir = modelManager.getModelDir(spec.id)
            destDir.mkdirs()
            val tmp = File(context.cacheDir, "dl-${spec.id}.partial")
            if (tmp.exists()) tmp.delete()

            Log.i(TAG, "Downloading ${spec.id} from ${spec.url}")
            downloadToFile(spec, tmp, index, count) { bytes, totalBytes ->
                val perAsset = if (totalBytes != null && totalBytes > 0) {
                    (bytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val overall = (index + perAsset * 0.92f) / count.toFloat()
                _state.value = AssetDownloadState.Downloading(
                    assetName = spec.displayName,
                    assetIndex = index,
                    assetCount = count,
                    bytesDownloaded = bytes,
                    totalBytes = totalBytes,
                    overallFraction = overall,
                    completedIds = completedIds(),
                    currentId = spec.id,
                )
            }

            _state.value = AssetDownloadState.Installing(
                assetName = spec.displayName,
                assetIndex = index,
                assetCount = count,
                overallFraction = (index + 0.95f) / count.toFloat(),
                completedIds = completedIds(),
                currentId = spec.id,
            )

            when (val kind = spec.kind) {
                is AssetKind.RawFile -> {
                    destDir.deleteRecursively()
                    destDir.mkdirs()
                    val out = File(destDir, kind.fileName)
                    if (!tmp.renameTo(out)) {
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                    }
                    modelManager.markModelOk(spec.id)
                }
                AssetKind.TarBz2 -> {
                    val modelsRoot = File(context.filesDir, "models").also { it.mkdirs() }
                    destDir.deleteRecursively()
                    extractTarBz2(tmp, modelsRoot)
                    tmp.delete()
                    if (!isAssetReady(spec)) {
                        if (hasUsableContent(destDir) || hasUsableContent(modelsRoot.resolve(spec.id))) {
                            modelManager.markModelOk(spec.id)
                        } else {
                            error("Could not install ${spec.displayName}. Please retry.")
                        }
                    } else {
                        modelManager.markModelOk(spec.id)
                    }
                }
            }
            Log.i(TAG, "Installed asset ${spec.id}")
        }

    private fun downloadToFile(
        spec: AssetSpec,
        dest: File,
        index: Int,
        count: Int,
        onProgress: (bytes: Long, total: Long?) -> Unit,
    ) {
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "KawaiiPet/1.0 (Android)")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                error("Could not download ${spec.displayName} (error $code). Check your connection.")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        if (readTotal - lastEmit >= 256 * 1024 || total != null && readTotal == total) {
                            lastEmit = readTotal
                            onProgress(readTotal, total)
                        }
                    }
                    onProgress(readTotal, total ?: readTotal)
                }
            }
            _state.value = AssetDownloadState.Downloading(
                assetName = spec.displayName,
                assetIndex = index,
                assetCount = count,
                bytesDownloaded = dest.length(),
                totalBytes = dest.length(),
                overallFraction = (index + 0.92f) / count.toFloat(),
                completedIds = completedIds(),
                currentId = spec.id,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, destRoot: File) {
        BufferedInputStream(archive.inputStream()).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('/')
                        if (name.isBlank() || name.contains("..")) {
                            entry = tarIn.nextEntry
                            continue
                        }
                        val out = File(destRoot, name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { fos -> tarIn.copyTo(fos) }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
    }

    private fun hasUsableContent(dir: File): Boolean {
        if (!dir.isDirectory) return false
        var files = 0
        var bytes = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                files++
                bytes += f.length()
            }
        }
        return files >= 2 && bytes > 10_000L
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is UnknownHostException -> "No internet connection. Connect to Wi‑Fi and retry."
        is SocketTimeoutException -> "Download timed out. Try again on a more stable network."
        else -> t.message?.takeIf { it.isNotBlank() } ?: "Download failed. Please retry."
    }

    companion object {
        private const val TAG = "AssetDownloadManager"
    }
}
