@file:Suppress("UnstableApiUsage")

import javax.inject.Inject

// Shadowsocks engine for Zuzu VPN:
//   libzuzuss.so            shadowsocks-rust 1.25.0 client (SOCKS5 inbound), JNI wrapper in native/zuzuss
//   libhev-socks5-tunnel.so hev-socks5-tunnel 2.19.3 (TUN -> SOCKS5), JNI
// Both are compiled from pinned, checksum-verified sources by native/build-native.sh (no Go code).

plugins {
    alias(libs.plugins.android.library)
}

android {
    // Fixed JNI namespace: the native libraries bind to com.zuzuvpn.shadowsocks.{SsLocal,HevTunnel}.
    // Independent of the app's applicationId, like com.wireguard.* in :tunnel.
    namespace = "com.zuzuvpn.shadowsocks"
    compileSdk = 36
    ndkVersion = "28.2.13676358" // same NDK as :tunnel; also pinned in native/versions.properties

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

/** Runs native/build-native.sh and exposes the per-ABI .so files as generated jniLibs. */
abstract class BuildShadowsocksNative : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val script: RegularFileProperty

    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:Internal
    abstract val workDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        execOperations.exec {
            environment("ANDROID_NDK_HOME", ndkDirectory.get())
            environment("ZUZU_NATIVE_WORK", workDirectory.get().asFile.absolutePath)
            commandLine("bash", script.get().asFile.absolutePath, out.absolutePath)
        }
    }
}

val nativeDir = rootProject.layout.projectDirectory.dir("native")

val buildShadowsocksNative = tasks.register<BuildShadowsocksNative>("buildShadowsocksNative") {
    group = "build"
    description = "Builds libzuzuss.so and libhev-socks5-tunnel.so for all ABIs."
    script.set(nativeDir.file("build-native.sh"))
    sources.from(
        nativeDir.file("versions.properties"),
        nativeDir.file("hev/Application.mk"),
        nativeDir.file("zuzuss/Cargo.toml"),
        nativeDir.file("zuzuss/Cargo.lock"),
        nativeDir.file("zuzuss/rust-toolchain.toml"),
        nativeDir.dir("zuzuss/src"),
    )
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    workDirectory.set(layout.buildDirectory.dir("native-work"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildShadowsocksNative,
            BuildShadowsocksNative::outputDirectory,
        )
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
