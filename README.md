# 운전약속 DrivePromise

시니어 운전자가 스스로 정한 운전 약속(예: 해 지기 전 귀가, 고속도로 피하기, 비 오는 날 조심, 2시간마다 쉬기, 약속 구역 안에서만)을 지키도록 돕는 Pleos Connect 차량용 앱입니다.
A Pleos Connect in-vehicle companion that helps older drivers keep the driving promises they set for themselves.

> 11/5: 앱 구현 v0.1 — 판단 코어(`core/`) 위에 어댑터·서비스·정차 화면(`app/`)을 올렸습니다. 주행 중에는 화면 없이 음성만(플랫폼이 비DO 화면을 가림), 세워 둔 동안 화면.

## 구성

| 경로 | 내용 |
|---|---|
| `core/` | 순수 Kotlin 코어(JVM 17) — 약속 엔진(5종)·일몰 계산(NOAA)·출발 전 계획·발화 스케줄러(늦은 알림 규칙)·내비에 물러서기·침묵 구간 게이트·주행 세션(상태 기계)·리플레이 재생기·기기 안 기록과 지표. 계약: `docs/contracts.md` |
| `app/` | Pleos 차량용 앱 — `platform/`(Vehicle SDK 2.3.2 + CarPropertyManager · NaviHelper 3.0.5 · LocationManager · Pleos TextToSpeech 3.3.1 + 오디오 포커스·재생 목록) · `DrivePromiseService`(10 Hz 세션 루프, 포그라운드) · `MainActivity`(정차 화면) · `store/`(기록 파일·설정) · `replay/`(assets 트립) |
| `demo/trips/` | 시연 리플레이 트립 4개(스키마 0.2): 병원→집(해 지기 전) · 두 시간 쉬기 · 약속 구역 경계 · 고속도로·비 — `:app` assets이자 `:core` 스펙 S72의 입력 |
| `docs/contracts.md` | 코어 계약 v0.1 (11/5 고정) — 바꾸려면 PR + 승인 |
| `probes/g1b/` | G1-B 프로브 앱 — 주행 중 화면 차단(DO)·음성(Pleos TTS)·내비 안내와 겹침·차량 신호·경로 API를 에뮬레이터에서 확인(따로 빌드) |
| `apk/` (제출 때) | 심사용 release APK |

## 시험 (코어)

JDK 17(17~23 가능)만 있으면 됩니다. 안드로이드 SDK는 필요 없습니다.

```bash
./gradlew :core:test
```

결과: `core/build/test-results/test/*.xml` (스펙 72개, 이름 앞 번호 S01~S72).

## 빌드·설치 (앱)

JDK 17, Android SDK 35, Pleos Playground Maven 저장소(토큰 불필요)를 씁니다.

```bash
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.drivepromise/.MainActivity
# 리플레이 시연(화면 버튼 또는 명령)
adb shell am start-foreground-service -n app.drivepromise/.DrivePromiseService --es cmd replay --es name hospital_home
```

## 설계 원칙 (요약)

- 주행 중에는 음성 중심, 화면은 정차·출발 전 — DO(주행 중 허용 화면) 선언은 하지 않음(G1-B B1: 사이드로드 앱에는 인정되지 않음)
- 회전 앞뒤 ‘침묵 구간’(회전 8초 전·좌회전 10초 전 ~ 회전 뒤 8초, 방향지시등·급제동 중)에는 말하지 않음 — 안전 경고만 예외
- 기본 내비 안내와 겹치면 운전약속이 물러섬 — 내비 안내 용도(NAVIGATION_GUIDANCE)로 말하고, 재생 목록·포커스로 감지해 250 ms 안에 멈추고, 내비가 모두 끝나고 1.5초 뒤 짧게 다시 말함
- 늦은 알림은 말의 종류로 정함 — 상태가 이어지는 말(2시간 쉬기)은 주행 중 버리지 않고, 지점·시각이 지난 말은 하지 않음
- 판정·발화 로직은 화면과 분리된 포그라운드 서비스에서 동작, 기록은 기기 안에만(`files/ledger/*.jsonl`)
- 인터넷·경로가 없어도 동작 — 약속 판정은 차량 신호·위치만으로, 경로가 있으면 ETA·TBT를 더 씀(경로 실패는 ‘판단 못 함’)

## 비밀 정보

API 키·서명 키는 저장소에 넣지 않습니다(`local.properties`, `secrets.properties`, `*.jks` 등은 `.gitignore`).
