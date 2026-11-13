import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing material is never committed. CI passes it through environment
// variables (see .github/workflows/release.yml); local builds may use an ignored
// keystore.properties in the repository root instead:
//   storeFile=/absolute/path/to/release.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Without it, assembleRelease falls back to the debug key so the task still runs.
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    signingProperties.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = signingValue("storeFile", "MAGPIE_KEYSTORE_PATH")?.let(rootProject::file)
val releaseStorePassword = signingValue("storePassword", "MAGPIE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "MAGPIE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "MAGPIE_KEY_PASSWORD")
val hasReleaseSigning = releaseKeystoreFile?.isFile == true &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

if (!hasReleaseSigning) {
    logger.lifecycle("Release signing is not configured; release APKs will use the debug key.")
}

android {
    namespace = "dev.magpie.usage"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.magpie.usage"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.10.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
}
