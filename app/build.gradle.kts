import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Release signing material, resolved from keystore.properties at the repo root (local
// builds) and then from the environment (CI). Deliberately never falls back to the debug
// keystore: its password is public, and consecutive debug-signed releases carry different
// signatures, so users cannot install one over another.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyName: String, environmentName: String): String? =
    keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "LOCALSCRIBE_KEYSTORE_FILE")
    ?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("storePassword", "LOCALSCRIBE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "LOCALSCRIBE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "LOCALSCRIBE_KEY_PASSWORD")

val releaseSigningReady = releaseStoreFile?.exists() == true &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "dev.chaseallbright.localscribe"
    compileSdk = 36
    // Pinned: CI installs this exact NDK, and AGP would otherwise pick its own default
    // (27.x locally), so CI and local machines would build with different toolchains.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.chaseallbright.localscribe"
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v3 carries a signing-certificate lineage, the only mechanism for
                // rotating this key later if it is ever lost or compromised. At minSdk
                // 28 apksigner emits v3 alone and reports v2 as absent -- v3 supersedes
                // it for API 28+. v2 stays enabled as a safety net if minSdk ever drops;
                // v1 (JAR signing) only matters below API 24.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unsigned when no keystore is configured. The taskGraph guard below turns
            // that into a hard build failure rather than a quietly unsigned APK.
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":whisper-jni"))
    implementation(project(":llama-jni"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// A release build that silently produces an unsigned (or debug-signed) APK is the one
// mistake here that cannot be walked back: whoever installs it can never be updated over.
// Fail the build instead, and only when a release artifact is actually being produced.
gradle.taskGraph.whenReady {
    if (releaseSigningReady) return@whenReady
    val releaseTaskName = Regex("^(assemble|bundle|package).*Release$")
    val offending = allTasks.firstOrNull {
        it.project == project && releaseTaskName.matches(it.name)
    } ?: return@whenReady

    throw GradleException(
        """
        Cannot run '${offending.name}': no release signing configuration was found.

        Set these in keystore.properties at the repo root (see keystore.properties.example),
        or as environment variables:

          storeFile     / LOCALSCRIBE_KEYSTORE_FILE
          storePassword / LOCALSCRIBE_KEYSTORE_PASSWORD
          keyAlias      / LOCALSCRIBE_KEY_ALIAS
          keyPassword   / LOCALSCRIBE_KEY_PASSWORD

        Create the keystore once with:
          keytool -genkeypair -v -keystore localscribe-release.jks -alias localscribe \
            -keyalg RSA -keysize 4096 -validity 10000

        Keep that file and its passwords backed up. Losing them means never being able to
        ship an update to anyone who installed a previous release.
        """.trimIndent()
    )
}
