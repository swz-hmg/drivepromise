// G1-B 프로브 앱 — DO 화면 차단·음성(TTS)·차량 신호·경로 API를 에뮬레이터에서 확인 (공식 샘플과 같은 버전)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.drivepromise.probe"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.drivepromise.probe"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-g1b"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")   // 2.3.1은 Maven에 없음(공식 샘플 README·1일차 실측은 2.3.2)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
}
