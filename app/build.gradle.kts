// :app — 운전약속(DrivePromise) Pleos 차량용 앱. 판단은 :core, 여기는 어댑터(platform/)·서비스·화면 2개·리플레이 자산.
// 버전·의존성은 G1-B 프로브(probes/g1b)에서 에뮬레이터 빌드·실행이 확인된 것과 같게 둠.
// 11/5 제출본(v1.0.0): release 서명은 저장소 밖 파일(~/.drivepromise/signing.properties)에서만 읽음 — 키·암호는 저장소에 없음.
// 1.1.0: 위치를 Pleos FusedLocation SDK 1.1.3으로(위치 업데이트 + 약속 구역 근접 알림 이중 확인), 안 되면 Android LocationManager로 자동 전환.
// 1.2.0(11/6): 음성 명령 — Pleos SpeechToText 3.3.1(ON_DEVICE). ‘말하기’ 버튼(A) + 쉬기 질문 뒤 5초(B)만 들음.
//   STT 호출은 모두 리플렉션이라 SDK 판이 달라도 컴파일은 깨지지 않음. 의존성을 못 받는 환경에선 -PdpStt=false 로 빼고 빌드(앱은 1.1.0처럼 동작).
//   파일이 없으면 서명 없는 release(app-release-unsigned.apk)가 나오며, 제출 스크립트가 처음 한 번 전용 키를 만들어 줌.
// 1.2.1(11/6 실행 보정): 질문 아닌 본 알림 뒤엔 듣지 않음 · 리플레이 중 듣는 동안 가상 시계 멈춤 · 주행 끝 뒤 요약을 끝까지 ·
//   경로 바뀜 취소(onRouteCancelled) 무시 · 주행 화면 문장 기록 · 와이퍼 WindowArea 영역 · 오류 코드는 말하지 않음.
// 1.2.2(11/6 두 번째 실행 보정): ‘약속 알려 줘’ 대답이 판단 전 약속을 ‘모두’에 넣지 않음(S83) · 와이퍼 ‘없음’ 기록은 모든 등록 꼴이 실패했을 때만
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 서명 정보 — Gradle 속성 dpSigning(경로)이 있으면 그 파일, 없으면 ~/.drivepromise/signing.properties
val dpSigningFile: File = (findProperty("dpSigning") as String?)?.let { file(it) }
    ?: File(System.getProperty("user.home"), ".drivepromise/signing.properties")
val dpSigning = Properties().apply { if (dpSigningFile.isFile) dpSigningFile.inputStream().use { load(it) } }
// 음성 인식 SDK 포함 여부 — 기본 포함. 실행 스크립트가 의존성 해결에 실패하면 -PdpStt=false 로 한 번 더 빌드함
val dpStt: Boolean = (findProperty("dpStt") as String?)?.lowercase() != "false"

android {
    namespace = "app.drivepromise"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.drivepromise"
        minSdk = 33
        targetSdk = 35
        versionCode = 13
        versionName = "1.3.6"   // 공모전 1차 제출본(11/15) — 1.3.5 + 회전 뒤 침묵을 회전점 기준으로(QuietGate.turnReachedM 20 → 0 · 목적지 판정 destinationClearM 20 그대로 · S128 — 11/13 구현 코어 시뮬레이션 docs/TIMING_EVAL.md)
    }

    signingConfigs {
        if (dpSigning.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(dpSigning.getProperty("storeFile"))
                storePassword = dpSigning.getProperty("storePassword")
                keyAlias = dpSigning.getProperty("keyAlias")
                keyPassword = dpSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // 심사용 APK — 난독화 없이(Pleos SDK 리플렉션 사용)
            signingConfig = signingConfigs.findByName("release")   // 없으면 서명 없는 release
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
    implementation("ai.pleos.playground:FusedLocation:1.1.3")  // Pleos 위치(공식 샘플과 같은 버전) — 에뮬레이터 Supported·Ready, geo fix 1 Hz 반영, 근접 알림 ENTER/EXIT(11/4 실측)
    if (dpStt) implementation("ai.pleos.playground:SpeechToText:3.3.1")   // Pleos 음성 인식(ON_DEVICE) — 리플렉션으로만 부름(platform/PleosStt.kt)
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
}
