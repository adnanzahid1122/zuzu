@file:Suppress("UnstableApiUsage")

// Zuzu VPN — commercial Android application module.
// The WireGuard engine stays in :tunnel (unchanged Java packages, JNI-bound names).
// :ui (the original WireGuard app) is kept untouched and is not a dependency of this module.

val appPkg: String = providers.gradleProperty("appPackageName").get()

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
}

dependencies {
    implementation(project(":tunnel")) // WireGuard engine: GoBackend, config, crypto

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
}
