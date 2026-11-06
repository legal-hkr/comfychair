plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sh.hnet.comfychair"
    compileSdk = 36

    defaultConfig {
        applicationId = "sh.hnet.comfychair"
        minSdk = 33
        targetSdk = 36
        versionCode = 41
        versionName = "v0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Disable baseline profile generation for F-Droid reproducible builds
// https://f-droid.org/docs/Reproducible_Builds/
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)

    // OkHttp for HTTP/HTTPS requests and WebSocket communication
    implementation(libs.okhttp)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle and ViewModel for Compose
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation Compose
    implementation(libs.navigation.compose)

    // Media3 for video playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    // Markdown parsing
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    // Security (encrypted SharedPreferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * Copies this version's F-Droid changelogs (metadata/<locale>/changelogs/<versionCode>.txt, English and any
 * translations written for the release) into the app's assets as whats_new/<locale>.txt, for the What's new page.
 * Without an English one, the app shows no What's new.
 */
abstract class WhatsNewAssetTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelogs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = File(outputDir.get().asFile, "whats_new")
        outputDir.get().asFile.deleteRecursively()
        dir.mkdirs()
        // metadata/<locale>/changelogs/<versionCode>.txt -> whats_new/<locale>.txt
        changelogs.files.filter { it.isFile }.forEach { it.copyTo(File(dir, "${it.parentFile.parentFile.name}.txt")) }
    }
}

val whatsNewAsset = tasks.register<WhatsNewAssetTask>("whatsNewAsset") {
    changelogs.from(
        rootProject.fileTree("metadata") { include("*/changelogs/${android.defaultConfig.versionCode}.txt") }
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(whatsNewAsset, WhatsNewAssetTask::outputDir)
    }
}
