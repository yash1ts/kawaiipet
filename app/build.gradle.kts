import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.Properties
import java.util.zip.ZipEntry as JZipEntry
import java.util.zip.ZipFile as JZipFile
import java.util.zip.ZipOutputStream as JZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Optional Play Store / release signing: create keystore.properties at repo root (gitignored) with:
// storeFile=release.keystore
// storePassword=...
// keyAlias=...
// keyPassword=...
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

configurations.configureEach {
    resolutionStrategy {
        // Compose BOM can pull core-ktx 1.17+ which requires compileSdk 36 / AGP 8.9.1+
        force("androidx.core:core-ktx:1.15.0")
        force("androidx.core:core:1.15.0")
        // LiteRT-LM AARs call SendChannel.close$default (interface-static bridge)
        // that only exists in kotlinx-coroutines 1.11.0+, while the published POM
        // still declares 1.9.0 — without this force, reply crashes with NoSuchMethodError.
        // https://github.com/google-ai-edge/litert-lm/issues/2812
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    }
}

android {
    namespace = "com.kawaiipet.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties.getProperty("keyAlias")!!
                keyPassword = keystoreProperties.getProperty("keyPassword")!!
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile")!!)
                storePassword = keystoreProperties.getProperty("storePassword")!!
            }
        }
    }

    defaultConfig {
        applicationId = "com.kawaiipet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Pixel / modern phones: skip x86 + armeabi-v7a native libs (~100MB+ APK savings).
        // Emulator: use an arm64 system image, or temporarily add "x86_64".
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(
                if (keystorePropertiesFile.exists()) "release" else "debug"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("onnx", "ort", "litertlm", "task")
    }

    packaging {
        jniLibs {
            // 16 KB page-size compatibility (Android 15+).
            useLegacyPackaging = false
            // Safety net if another AAR also ships ORT.
            pickFirsts += "**/libonnxruntime.so"
            // localagents-rag ships unused native embedders/chunkers; we use MiniLmEmbedder + SqliteVectorStore only.
            excludes += listOf(
                "**/libgemma_embedding_model_jni.so",
                "**/libgecko_embedding_model_jni.so",
                "**/libtext_chunker_jni.so",
            )
        }
    }

}

// Sherpa-ONNX AAR is already 16KB page-size compatible (see app/libs/README.txt).
// We strip its bundled libonnxruntime.so and use onnxruntime-android instead so MiniLM
// can use the Java ORT API (libonnxruntime4j_jni.so). Keep ORT >= 1.22 for 16KB JNI.
// https://github.com/k2-fsa/sherpa-onnx/releases
// 1.13.4+ required for KittenTTS v0.8 (style_dim rows / max_token_len=400).
private val sherpaOnnxReleaseVersion = "1.13.4"
private val sherpaOnnxAarFile = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxReleaseVersion.aar")
private val sherpaOnnxAppAarFile = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxReleaseVersion-app.aar")

tasks.register("downloadSherpaOnnxAar") {
    val out = sherpaOnnxAarFile.asFile
    val url =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxReleaseVersion/" +
            "sherpa-onnx-$sherpaOnnxReleaseVersion.aar"
    outputs.file(out)
    doLast {
        if (out.exists()) return@doLast
        out.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

tasks.register("prepareSherpaOnnxAppAar") {
    dependsOn("downloadSherpaOnnxAar")
    val src = sherpaOnnxAarFile.asFile
    val dst = sherpaOnnxAppAarFile.asFile
    inputs.file(src)
    outputs.file(dst)
    doLast {
        dst.parentFile?.mkdirs()
        JZipFile(src).use { zf ->
            FileOutputStream(dst).use { fos ->
                JZipOutputStream(fos).use { zos ->
                    for (e in zf.entries()) {
                        if (e.isDirectory) continue
                        // Drop Sherpa's bundled ORT so onnxruntime-android can own
                        // libonnxruntime.so (+ libonnxruntime4j_jni.so for MiniLM).
                        if (e.name.endsWith("libonnxruntime.so")) continue
                        val outEntry = JZipEntry(e.name).apply { time = e.time }
                        zos.putNextEntry(outEntry)
                        zf.getInputStream(e).use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("prepareSherpaOnnxAppAar")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.lottie.compose)

    implementation(files(sherpaOnnxAppAarFile.asFile))

    // Google LiteRT-LM (on-device chat)
    implementation(libs.litertlm.android)

    // Google AI Edge RAG (SqliteVectorStore + SemanticTextMemory)
    implementation(libs.localagents.rag)
    // SqliteVectorStore serializes via protobuf lite; not pulled in unless tasks-genai is present.
    implementation(libs.protobuf.javalite)
    implementation(libs.onnxruntime.android)
    implementation(libs.coroutines.guava)

    // tar.bz2 extraction for Sherpa voice packs
    implementation(libs.commons.compress)
}
