// 운전약속(DrivePromise) 본 빌드 — :core(순수 Kotlin) + :app(Pleos 차량용 앱: 어댑터·서비스·정차 화면·리플레이).
// 11/5 결정: 따로 두려던 :platform·:replay는 :app 안의 패키지(app.drivepromise.platform)와 core/replay(ReplayPlayer)로 흡수 — 모듈 수를 줄여 빌드 실패 지점을 줄임.
// probes/g1b 는 따로 빌드되는 기술 확인용 프로브라 이 빌드에 넣지 않습니다.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "drivepromise"
include(":core")
include(":app")
