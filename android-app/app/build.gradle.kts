plugins {
    id("com.android.application")
}

// GeckoView 145+ (including the bundled 156 build) requires Android 8.0 / API 26.
// Keep the ABI list configurable so an Arm64-only build does not accidentally
// advertise x86_64 support merely because GeckoView ships x86_64 libraries.
val cpaAbiFilters = providers.gradleProperty("cpaAbis")
    .orNull
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "x86_64")

val shellVersionName = providers.gradleProperty("cpa.shellVersionName").orNull ?: "0.3.1"
val shellVersionCode = providers.gradleProperty("cpa.shellVersionCode").orNull?.toIntOrNull() ?: 4
val effectiveVersionName = providers.gradleProperty("cpa.versionNameOverride").orNull ?: shellVersionName
val effectiveVersionCode = providers.gradleProperty("cpa.versionCodeOverride").orNull?.toIntOrNull() ?: shellVersionCode

android {
    namespace = "io.github.cliproxy.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.cliproxy.android"
        minSdk = 26
        targetSdk = 36
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName

        ndk {
            abiFilters += cpaAbiFilters
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.yaml:snakeyaml:2.4")

    // Full Gecko engine bundled in the APK/AAB; does not use Android System WebView.
    implementation("org.mozilla.geckoview:geckoview-omni:156.0.20260921121718")
}
