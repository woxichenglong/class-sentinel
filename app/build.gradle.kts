plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

android {
    namespace = "com.classsentinel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.classsentinel"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystorePath?.let(::file)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Keep minify off until the measured size profile justifies a separate optimization slice.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs {
            // 第三方预编译库不接受当前 strip 工具；显式保留符号，避免构建时重复误报。
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so",
                "**/libonnxruntime.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so",
                "**/libsherpa-onnx-jni.so",
            )
        }
    }
    lint {
        // 依赖版本刻意与已验证的 Kotlin/AGP/WorkManager 组合锁定；升级需单独回归。
        disable += "GradleDependency"
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        val missing = buildList {
            if (releaseKeystorePath.isNullOrBlank()) add("ANDROID_KEYSTORE_PATH")
            if (releaseStorePassword.isNullOrBlank()) add("ANDROID_KEYSTORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("ANDROID_KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("ANDROID_KEY_PASSWORD")
        }
        check(missing.isEmpty()) {
            "Release signing requires local secure configuration: ${missing.joinToString(", ")}"
        }
        check(file(releaseKeystorePath!!).isFile) {
            "ANDROID_KEYSTORE_PATH must point to a readable keystore"
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyReleaseSigning)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Offline continuous ASR runtime pinned by the streaming-ASR plan.
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager（2.9.1：保守兼容 minSdk 26；升级到 2.11.x 需单独验证调度行为）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 拼音（点名模糊匹配）——原版 promeg 只发 jcenter(已死)，用 biezhi fork（Maven Central，API 同源）
    implementation("io.github.biezhi:TinyPinyin:2.0.3.RELEASE")

    // HTTP（ASR / LLM）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // JVM 单测下 org.json 是 Android stub(not mocked)，用真实现覆盖
    testImplementation("org.json:json:20240303")
    // WorkManager 测试支持（Task 16）
    testImplementation("androidx.work:work-testing:2.9.1")
}
