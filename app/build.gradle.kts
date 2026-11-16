@file:Suppress("UnstableApiUsage")

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Zuzu VPN — commercial Android application module.
// The WireGuard engine stays in :tunnel (unchanged Java packages, JNI-bound names).
// :ui (the original WireGuard app) is kept untouched and is not a dependency of this module.

val appPkg: String = providers.gradleProperty("appPackageName").get()

// Zuzu Control Plane (public server directory). Origin only: the client appends /api/v1 itself.
// Override with -PzuzuApiBaseUrl=https://… (e.g. a staging API); it must be HTTPS.
val apiBaseUrl: String = providers.gradleProperty("zuzuApiBaseUrl").orElse("https://api.gorabbit.net").get().trimEnd('/')
require(apiBaseUrl.startsWith("https://")) { "zuzuApiBaseUrl must use HTTPS" }

plugins {
    alias(libs.plugins.android.application) // AGP 9.1.0 — built-in Kotlin compiles src/main/kotlin
    alias(libs.plugins.kotlin.compose)      // Compose compiler; version = KGP used by AGP (2.2.10)
    // Do not apply org.jetbrains.kotlin.android: it is incompatible with AGP 9 built-in Kotlin.
}

android {
    // Kotlin / R / BuildConfig package. Deliberately fixed and decoupled from applicationId, so the
    // final Play application ID can be set later in gradle.properties without moving any source files.
    namespace = "com.example.zuzuvpn"
    compileSdk = 36         // same as :tunnel and :ui

    defaultConfig {
        applicationId = appPkg   // TEMPORARY development ID — replace before Google Play publication
        minSdk = 24              // same as :tunnel
        targetSdk = 36           // explicit (Google Play requires API 36 from 31 Aug 2026)
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")

        ndk {
            // The Shadowsocks engine (:shadowsocks, native/versions.properties ABIS) is currently built
            // for arm64-v8a only, so the APK is limited to it. Widen both lists together.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required: :tunnel uses java.time (InetEndpoint) while minSdk is 24.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        debug {
            // Must stay ".debug": :tunnel builds its debug native libraries for "<appPackageName>.debug".
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signingConfig yet: add an upload key from a non-committed keystore.properties.
        }
        // Any new build type needs matchingFallbacks += "release" (or "debug"),
        // because :tunnel only defines debug and release.
    }

    packaging {
        jniLibs {
            // :tunnel still builds the wg / wg-quick root-backend executables (GPL-2.0, wireguard-tools)
            // for :ui. Zuzu VPN never uses WgQuickBackend, so they are kept out of this APK.
            excludes += setOf("**/libwg.so", "**/libwg-quick.so")
        }
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/*.version",
            )
        }
    }

    lint {
        disable += "LongLogTag"
    }

    // JVM unit tests: android.util.Log etc. return defaults instead of throwing.
    testOptions.unitTests.isReturnDefaultValues = true
}

/**
 * Debug-only build-time test servers.
 *
 * Reads the untracked zuzu-dev/test-servers.json (see zuzu-dev/README.md), encrypts it with a fresh
 * random AES-256-GCM key and adds it to the debug variant as an asset; the key is written to a
 * generated debug-only Kotlin object (DevSeedKey). The release variant never runs this task, so
 * release APKs contain neither the configuration nor the key.
 *
 * Without the file (or while it still holds the placeholder password) the debug build has no
 * preloaded test server and a warning is printed. File contents are never printed.
 */
abstract class GenerateDevTestServers : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val kotlinDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val assets = assetsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val kotlin = kotlinDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        var keyBase64 = ""
        val config = configFiles.files.firstOrNull { it.isFile }
        if (config == null) {
            logger.warn("Zuzu: zuzu-dev/test-servers.json not found; this debug build has no preloaded test server.")
        } else {
            val text = config.readText()
            val parsed = try {
                groovy.json.JsonSlurper().parseText(text)
            } catch (e: Exception) {
                throw GradleException("zuzu-dev/test-servers.json is not valid JSON") // parser messages quote content
            }
            val servers = (parsed as? Map<*, *>)?.get("servers") as? List<*>
            if (servers.isNullOrEmpty()) throw GradleException("zuzu-dev/test-servers.json needs a non-empty \"servers\" array")
            if (text.contains("PASTE_TEST_SERVER_PASSWORD_HERE")) {
                logger.warn("Zuzu: zuzu-dev/test-servers.json still contains the placeholder password; this debug build has no preloaded test server.")
            } else {
                val random = SecureRandom()
                val key = ByteArray(32).also(random::nextBytes)
                val iv = ByteArray(12).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                val sealed = iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
                assets.resolve("zuzu-dev").apply { mkdirs() }.resolve("test-servers.bin").writeBytes(sealed)
                keyBase64 = Base64.getEncoder().encodeToString(key)
                logger.lifecycle("Zuzu: embedded ${servers.size} encrypted test server(s) in the debug build.")
            }
        }
        kotlin.resolve("com/example/zuzuvpn/dev").apply { mkdirs() }.resolve("DevSeedKey.kt").writeText(
            """
            |package com.example.zuzuvpn.dev
            |
            |/** Generated by :app:generateDebugDevTestServers. Debug builds only. Empty = no test servers. */
            |internal object DevSeedKey {
            |    const val KEY_BASE64: String = "$keyBase64"
            |}
            |""".trimMargin(),
        )
    }
}

val generateDebugDevTestServers = tasks.register<GenerateDevTestServers>("generateDebugDevTestServers") {
    configFiles.from(rootProject.layout.projectDirectory.file("zuzu-dev/test-servers.json"))
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateDebugDevTestServers, GenerateDevTestServers::assetsDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateDebugDevTestServers, GenerateDevTestServers::kotlinDir)
    }
}

dependencies {
    implementation(project(":tunnel")) // WireGuard engine: GoBackend, config, crypto
    implementation(project(":shadowsocks")) // Shadowsocks engine: shadowsocks-rust + hev-socks5-tunnel (JNI)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen) // Android SplashScreen API + backport to API 24
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    coreLibraryDesugaring(libs.desugarJdkLibs)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json for JVM tests of the debug config parser
}
