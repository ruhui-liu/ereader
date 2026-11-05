import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional secrets, read from local.properties, Gradle properties or environment variables.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String, envName: String): String =
    localProps.getProperty(name)
        ?: providers.gradleProperty(envName).orNull
        ?: System.getenv(envName)
        ?: ""

val dropboxAppKey = secret("dropbox.appKey", "DROPBOX_APP_KEY")

android {
    namespace = "com.ruhuiliu.ereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ruhuiliu.ereader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")
        // The Dropbox SDK's AuthActivity receives the OAuth redirect on the db-<APP_KEY> scheme.
        manifestPlaceholders["dropboxAppKey"] = dropboxAppKey.ifEmpty { "not-configured" }
    }

    signingConfigs {
        // A shared debug key (committed on purpose) keeps the signing certificate — and therefore the
        // SHA-1 registered for Google Drive OAuth — identical on every machine and in CI.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Optional release keystore. Without it, release builds are signed with the debug key
        // so the APK can still be installed for personal use.
        val storeFilePath = secret("release.storeFile", "RELEASE_STORE_FILE")
        if (storeFilePath.isNotEmpty()) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = secret("release.storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = secret("release.keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = secret("release.keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin",
                "META-INF/native-image/**",
            )
        }
    }

    // One APK per CPU architecture (ML Kit's native translation library is ~16 MB per ABI),
    // plus a universal APK. Phones need the arm64-v8a one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    // Translation engines
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.anthropic.java)

    // Cloud sources
    implementation(libs.play.services.auth)
    implementation(libs.dropbox.core)
    implementation(libs.dropbox.android)

    // Text encoding detection for TXT books
    implementation(libs.juniversalchardet)
    // PDF text extraction (pages are rendered with the platform PdfRenderer)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kxml2)
    // Desktop PDFBox (same API as PdfBox-Android) to test the text layout analysis on real PDFs.
    testImplementation(libs.pdfbox.desktop)
}
