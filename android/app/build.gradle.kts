import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val inboxUrl = escapeBuildConfig(localProperties.getProperty("glass.inbox.url", "") ?: "")
val inboxToken = escapeBuildConfig(localProperties.getProperty("glass.inbox.token", "") ?: "")

android {
    namespace = "com.jtwolfe.glass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jtwolfe.glass"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "INBOX_URL", "\"$inboxUrl\"")
        buildConfigField("String", "INBOX_TOKEN", "\"$inboxToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        resources {
            excludes += listOf(
                "META-INF/license/**",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/**",
                "META-INF/native-image/**",
            )
            pickFirsts += listOf(
                "META-INF/services/*",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Encrypted storage for xAI OAuth tokens and pairing PSK
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Chrome Custom Tabs for xAI OAuth browser flow
    implementation("androidx.browser:browser:1.8.0")

    // ML Kit barcode scanning for glass-pair QR codes
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // libp2p for P2P transport (Quay: /glass/inbox/v0 after pair)
    implementation("io.libp2p:jvm-libp2p:1.3.5-RELEASE")

    // Guava (needed by jvm-libp2p and CameraX - use Android variant)
    implementation("com.google.guava:guava:33.3.1-android")

    // WebRTC for ntfy-based DataChannel pairing
    implementation("io.getstream:stream-webrtc-android:1.3.7")
}
