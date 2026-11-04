# 운전약속 DrivePromise

시니어 운전자가 스스로 정한 운전 약속(예: 해 지기 전 귀가, 고속도로 피하기, 2시간마다 쉬기)을 지키도록 돕는 Pleos Connect 차량용 앱입니다.
A Pleos Connect in-vehicle companion that helps older drivers keep the driving promises they set for themselves.

> 앱 화면·어댑터(`app/`, `platform/`)는 개발 중입니다. 지금은 판단 로직 코어(`core/`)와 기술 확인용 프로브가 있습니다.

## 구성

| 경로 | 내용 |
|---|---|
| `core/` | 순수 Kotlin 코어(JVM 17) — 발화 스케줄러(늦은 알림 규칙)·내비에 물러서기·2시간 쉬기·기기 안 기록과 지표·리플레이 스키마 0.2. 계약: `docs/contracts.md` |
| `docs/contracts.md` | 코어 계약 v0.1 (11/5 고정) — 바꾸려면 PR + 승인 |
| `probes/g1b/` | G1-B 프로브 앱 — 주행 중 화면 차단(DO)·음성(Pleos TTS)·내비 안내와 겹침·차량 신호·경로 API를 에뮬레이터에서 확인(따로 빌드) |
| `app/`, `platform/`, `replay/` (예정) | 앱 화면·Pleos/AAOS 어댑터·리플레이 장치 |
| `apk/` (제출 때) | 심사용 release APK |

## 시험 (코어)

JDK 17(17~23 가능)만 있으면 됩니다. 안드로이드 SDK는 필요 없습니다.

```bash
./gradlew :core:test
```

결과: `core/build/test-results/test/*.xml` (스펙 41개, 이름 앞 번호 S01~S41).

## 빌드 (프로브)

JDK 17, Android SDK 35, Pleos Playground Maven 저장소(토큰 불필요)를 씁니다. 음성은 Pleos TextToSpeech SDK(ON_DEVICE)로 냅니다.

```bash
cd probes/g1b
./gradlew assembleDebug
```

## 설계 원칙 (요약)

- 주행 중에는 음성 중심, 화면은 정차·출발 전 (플랫폼이 허용하면 작은 상태 표시만)
- 회전 앞뒤 ‘침묵 구간’에는 말하지 않음 — 안전 경고만 예외
- 기본 내비 안내와 겹치면 운전약속이 물러섬 — 내비 소리를 줄이거나 끊지 않고, 내비가 모두 끝나고 1.5초 뒤 짧게 다시 말함
- 늦은 알림은 말의 종류로 정함 — 상태가 이어지는 말(2시간 쉬기)은 주행 중 버리지 않고, 지점·시각이 지난 말은 하지 않음
- 판정·발화 로직은 화면과 분리된 포그라운드 서비스에서 동작, 기록은 기기 안에만

## 비밀 정보

API 키·서명 키는 저장소에 넣지 않습니다(`local.properties`, `secrets.properties`, `*.jks` 등은 `.gitignore`).
