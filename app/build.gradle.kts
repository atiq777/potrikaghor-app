plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.potrikaghor"
    compileSdk = 36

    val releaseKeystorePath = System.getenv("KEYSTORE_FILE")
        ?: System.getenv("RELEASE_KEYSTORE_FILE")
        ?: project.findProperty("KEYSTORE_FILE")?.toString()
        ?: project.findProperty("RELEASE_KEYSTORE_FILE")?.toString()
        ?: "${rootDir}/release-keystore.jks"
    val releaseKeystoreFile = file(releaseKeystorePath)
    val releaseKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
        ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
        ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
        ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD")?.toString()
    val releaseKeyAlias = System.getenv("KEY_ALIAS")
        ?: System.getenv("RELEASE_KEY_ALIAS")
        ?: project.findProperty("KEY_ALIAS")?.toString()
        ?: project.findProperty("RELEASE_KEY_ALIAS")?.toString()
    val releaseKeyPassword = System.getenv("KEY_PASSWORD")
        ?: System.getenv("RELEASE_KEY_PASSWORD")
        ?: project.findProperty("KEY_PASSWORD")?.toString()
        ?: project.findProperty("RELEASE_KEY_PASSWORD")?.toString()
    val hasReleaseSigning = releaseKeystoreFile.exists() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    defaultConfig {
        applicationId = "com.aistudio.potrikaghor.qwrty"
        minSdk = 26
        targetSdk = 36
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (ciRunNumber != null && ciRunNumber > 0) {
            19 + ciRunNumber
        } else {
            19
        }
        versionName = "1.7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
