// :app — 운전약속(DrivePromise) Pleos 차량용 앱. 판단은 :core, 여기는 어댑터(platform/)·서비스·정차 화면·리플레이 자산.
// 버전·의존성은 G1-B 프로브(probes/g1b)에서 에뮬레이터 빌드·실행이 확인된 것과 같게 둠.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.drivepromise"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.drivepromise"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // 심사용 APK — 난독화 없이(Pleos SDK 리플렉션 사용)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")   // Pleos SDK가 더 새 Kotlin으로 빌드됐을 때도 읽게(프로브와 같음)
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    sourceSets {
        getByName("main") {
            assets.srcDir("../demo")   // demo/trips/*.json → assets/trips/ — :core 스펙(S72)이 같은 파일을 읽어 기대 발화를 확인
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")        // 2.3.1은 Maven에 없음(1일차 실측)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")   // Pleos 음성 합성(ON_DEVICE) — 기기 기본 TTS는 초기화 실패(G1-B v0.5)
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
}
