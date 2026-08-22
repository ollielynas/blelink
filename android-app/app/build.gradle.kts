plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blelink.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blelink.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Custom Tabs launch the device's real Chrome, which — unlike the WebView used for the
    // YouTube player — actually supports Web Bluetooth, so "client mode" can reuse the exact
    // same web app guests already use instead of reimplementing the BLE central role natively.
    implementation("androidx.browser:browser:1.8.0")
    // Embedded HTTP+WebSocket server for LAN mode — lets the same guest web app talk to the
    // phone over the local network instead of BLE (works even from the phone's own browser,
    // unlike a Web Bluetooth client connecting to the phone's own GATT server).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // Casts the queued YouTube video to a TV. Real Google Cast SDK integration — device
    // discovery and session management — driven by an open-source library that talks to our
    // own custom Cast receiver (docs/chromecast-receiver/, running YouTube's IFrame Player API,
    // same technique youtube_player.html already uses locally). This is standard Cast
    // Application Framework usage, not a hack against YouTube's native Chromecast app.
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:chromecast-sender:0.31")
}

// docs/index.html is the single source of truth for the guest web app; copy it into assets at
// build time so LanServer can serve it locally without hand-maintaining a second copy that can
// drift out of sync.
val copyWebClientAsset = tasks.register<Copy>("copyWebClientAsset") {
    from("${project.projectDir}/../../docs/index.html")
    into("${project.projectDir}/src/main/assets")
    rename { "web_client.html" }
}

tasks.named("preBuild") {
    dependsOn(copyWebClientAsset)
}
