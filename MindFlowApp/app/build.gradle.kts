import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
val aiApiKey = (localProperties.getProperty("AI_API_KEY")
    ?: localProperties.getProperty("GLM_API_KEY")
    ?: "").trim()
val aiApiBaseUrl = (localProperties.getProperty("AI_BASE_URL")
    ?: "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions").trim()
val aiTextModel = (localProperties.getProperty("AI_TEXT_MODEL")
    ?: localProperties.getProperty("AI_MODEL")
    ?: "qwen-plus").trim()
val aiVisionModel = (localProperties.getProperty("AI_VISION_MODEL")
    ?: "qwen3-vl-plus").trim()
val aiApiKeyEscaped = aiApiKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val aiApiBaseUrlEscaped = aiApiBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val aiTextModelEscaped = aiTextModel
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val aiVisionModelEscaped = aiVisionModel
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.mindflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mindflow"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "AI_API_KEY", "\"$aiApiKeyEscaped\"")
        buildConfigField("String", "AI_BASE_URL", "\"$aiApiBaseUrlEscaped\"")
        buildConfigField("String", "AI_TEXT_MODEL", "\"$aiTextModelEscaped\"")
        buildConfigField("String", "AI_VISION_MODEL", "\"$aiVisionModelEscaped\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 在 android { ... } 内部添加这个：
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Room Database
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)

    // Lifecycle & ViewModel
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)

    // WorkManager
    implementation(libs.work.runtime)

    // Gson
    implementation(libs.gson)

    // CardView
    implementation(libs.cardview)

    // LocalBroadcastManager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation("io.jsonwebtoken:jjwt-orgjson:0.11.5")

    // 1. 网络请求库
    implementation ("com.squareup.okhttp3:okhttp:4.12.0")
// 2. JWT 生成库 (用于智谱鉴权，这是一个纯 Java 轻量库)
    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    implementation("io.jsonwebtoken:jjwt-impl:0.11.5")
    implementation("io.jsonwebtoken:jjwt-jackson:0.11.5")
    // 1. Retrofit (用来发网络请求的)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
// 2. Gson (用来解析 JSON 数据的)
    implementation("com.google.code.gson:gson:2.10.1")



    // UI Charts
    implementation(libs.mpandroidchart)
}
