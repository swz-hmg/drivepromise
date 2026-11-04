// 운전약속(DrivePromise) 본 빌드 — 지금은 :core(순수 Kotlin)만. :platform·:replay·:app은 11/8에 추가합니다.
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
