import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun propOrEnv(key: String): String {
    return (localProps.getProperty(key) ?: System.getenv(key) ?: "").trim()
}

android {
    namespace = "dog.bald.arcade"
    compileSdk = 36

    defaultConfig {
        applicationId = "dog.bald.arcade"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        val supabaseUrl = propOrEnv("SUPABASE_URL")
        val supabaseAnonKey = propOrEnv("SUPABASE_ANON_KEY")
        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) {
            logger.warn("WARNING: SUPABASE_URL or SUPABASE_ANON_KEY is not defined in local.properties or environment")
        }
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = propOrEnv("BALDDOG_KEYSTORE_PATH")
            if (keystorePath.isNotBlank()) {
                storeFile = file(keystorePath)
                storePassword = propOrEnv("BALDDOG_KEYSTORE_PASSWORD")
                keyAlias = propOrEnv("BALDDOG_KEY_ALIAS")
                keyPassword = propOrEnv("BALDDOG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
                unstrippedNativeLibsDir = file("${rootProject.projectDir}/.cache/unstripped-jniLibs")
            }
            val keystorePath = propOrEnv("BALDDOG_KEYSTORE_PATH")
            signingConfig = if (keystorePath.isNotBlank()) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("WARNING: BALDDOG_KEYSTORE_PATH not configured; falling back to debug signing for release build")
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // UniFFI Kotlin bindings
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    // Supabase (Auth, Postgrest, Realtime) + Ktor OkHttp
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.2"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-okhttp:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Almacenamiento cifrado de tokens (Android Keystore)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firebase Crashlytics + NDK + Analytics
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    implementation("com.google.firebase:firebase-analytics")

    testImplementation("junit:junit:4.13.2")
}
