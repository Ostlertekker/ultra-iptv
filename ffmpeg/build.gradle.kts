// Tondecoder auf FFmpeg-Basis (AndroidX Media3 „decoder_ffmpeg“, Apache 2.0) – FFmpeg selbst wird im CI
// ausschließlich mit LGPL-Bausteinen (nur Audio-Decoder, ohne --enable-gpl/--enable-nonfree) gebaut.
// Ohne gebautes FFmpeg (z. B. Lint, lokale Builds) entsteht das Modul ohne native Bibliothek –
// die App läuft dann einfach ohne Software-Decoder weiter.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 35

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("proguard-rules.txt")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (file("src/main/jni/ffmpeg/android-libs").exists()) {
        ndkVersion = System.getenv("ULTRA_NDK_VERSION") ?: "27.0.12077973"
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    lint { abortOnError = false }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.decoder)
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.0")
}
