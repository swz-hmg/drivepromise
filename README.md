# 운전약속 (DrivePromise)

**시니어 운전자가 스스로 정한 ‘운전 약속’을 차량 신호로 살피다가, 말해도 안전한 순간에만 짧게 알려 주는 Pleos Connect 음성 코파일럿**
*A Pleos Connect voice co-pilot that helps older drivers keep the driving promises they set for themselves — it reads vehicle signals and speaks only when it is safe to talk.*

| 항목 | 내용 |
|---|---|
| 앱 이름 | 운전약속 (DrivePromise) · 패키지 `app.drivepromise` · 버전 1.3.0 (versionCode 7) |
| 한 줄 소개 | 해 지기 전 귀가 · 고속도로 대신 일반도로 · 비 오는 날 조심 · 2시간마다 쉬기 · 약속 구역 — 다섯 가지 약속을 차량 신호로 판단하고, 내비 안내·방향지시등 같은 ‘침묵 구간’을 피해 한두 문장으로 말합니다 |
| APK | [`apk/DrivePromiseTeam_DrivePromise_release.apk`](apk/) (release 빌드, 전용 키 서명) · 체크섬 `apk/SHA256SUMS.txt` |
| 테스트 환경 | Pleos Connect 에뮬레이터(Android 14 · API 34, 2560×1440) — 아래 3장 |
| 시연 영상 | [`docs/demo/drivepromise_demo.mp4`](docs/demo/) (약 3분 45초 · 1.3.0 에뮬레이터 실제 녹화: 출발 전 계획 · 시연 주행 4개 · 질문과 대답 · 음성 명령) |
| 문서 | 운영 매뉴얼 [`docs/MANUAL.md`](docs/MANUAL.md) · 설계 계약 [`docs/contracts.md`](docs/contracts.md) |
| 개발 방식 | 생성형 AI 도구 활용 — 아래 8장 |

---

## 1. 앱 소개

65세 이상 운전면허 보유자는 563만 명(2025)으로 늘었고, 많은 시니어 운전자는 이미 ‘밤에는 안 탄다’, ‘고속도로는 피한다’ 같은 자기만의 원칙으로 운전합니다. 문제는 원칙이 **그 순간**에 잊힌다는 것 — 해 지는 시각을 놓치거나, 비가 오는데 속도가 그대로이거나, 쉬지 않고 두 시간을 넘깁니다. 운전약속은 운전자가 (가족과 함께) 정한 약속을 차량이 지켜보다가 꼭 필요한 순간에만 짧게 말해 줍니다.

| 약속 | 쓰는 차량·내비 신호 | 앱이 하는 말(예) |
|---|---|---|
| 해 지기 전 귀가 | 일몰 시각(기기 안 계산) · 남은 경로 시간(NaviHelper) | “이대로면 5시 19분 도착, 해는 5시 22분에 져요.”(해 지기 30분 전 귀가 약속 — 도착이 일몰 뒤면 짧은 문장은 “해 진 뒤 도착이에요.”) |
| 고속도로 대신 | 도로 제한속도·도로 이름 · 갈림길 방면(NaviHelper TBT) | 들어서기 전: “앞 갈림길은 판교IC 방면이에요. 약속대로 일반도로로 가요.” · 들어서면: “고속도로에 들어섰어요. 약속과 달라요. 다음 나들목에서 내려갈지 정해요.” |
| 비 오는 날 조심 | 와이퍼(Vehicle SDK) · 속도 · 제한속도 | “비가 와요. 약속대로 평소보다 천천히, 앞차와 거리를 두세요.” |
| 2시간마다 쉬기 | 기어·속도로 계산한 연속 운전 시간 | “두 시간 5분째예요. 약속대로 쉬어 가요.” |
| 약속 구역 | 위치(Pleos FusedLocation) · 근접 알림(ENTER/EXIT)으로 한 번 더 확인 | “약속 구역 끝이 가까워요. 이 길로 가면 구역을 벗어나요.” |

**말하는 순간을 고릅니다.** 회전 직전·직후, 방향지시등이 켜진 동안, 급제동 직후에는 말하지 않고(침묵 구간 — 신호 대기 중에도 같음, 1.3.0), 기본 내비 안내와 겹치면 0.2초 안에 물러섰다가 안내가 끝나고 1.5초 뒤 짧게 다시 말합니다. 문장 길이는 글자 수로 다시 재서(한국어 약 0.17초/자, 에뮬레이터 실측) 회전 전에 끝낼 수 있을 때만 시작합니다.

**모르면 판단하지 않습니다(1.3.0).** 도로·와이퍼 신호를 한 번도 받지 못했거나 위치가 5분 넘게 끊기면 그 약속은 주행 끝에도 ‘지킴’이 아니라 ‘판단할 신호가 없었어요’로 따로 말합니다.

**약속은 가족과 함께 정합니다(1.3.0).** 세워 둔 동안 정차 화면의 ‘약속 정하기’에서 약속마다 켜고 끄고, 쉬기 간격(1·1.5·2시간), 해 지기 전 여유(15~60분), 약속 구역 반경(3~20 km)과 중심(지금 위치)을 정합니다. 바꾼 값은 바로 다음 주행부터 적용됩니다.

**한 번 켜 두면 계속 동작합니다(1.3.0).** 설치 뒤 한 번 열면, 시동(인포테인먼트 부팅) 때 자동으로 시작하고(설정에서 끌 수 있음), 주행이 끝날 때마다 요약을 말한 뒤 다음 주행을 새로 받습니다.

**화면은 최소로.** 정차 중에는 약속 상태·출발 전 계획·주행 끝 요약을 보여 주고, 주행 중에는 큰 글씨 한 장(약속 5개 색 · 마지막 문장 · ‘다시 들려줘’·‘🎤 말하기’ 큰 버튼 둘)만 보여 줍니다. 플랫폼이 주행 중 화면을 허용하지 않으면 스스로 감지해 음성만으로 동작합니다.

**물을 때만 듣습니다(1.2.0~).** 앱은 늘 듣지 않습니다. 운전자가 **‘🎤 말하기’를 누를 때(최대 6초)**와, 앱이 **두 시간 쉬기 질문(“두 시간째예요. 쉬어 갈까요?”)을 끝낸 직후 5초**만 Pleos SpeechToText(ON_DEVICE)로 듣습니다. 질문으로 끝난 말 뒤에만 듣고, 도착이 15분 안이라 “곧 도착이에요. 도착하면 꼭 쉬어요.”라고 말할 때는 듣지 않습니다. 듣기 직전에 짧은 ‘띵’ 소리가 나고, 듣는 동안에는 앱이 말하지 않으며, 내비 안내가 나오면 듣기를 멈춥니다. 알아듣는 말은 다섯 가지뿐이고, 모르는 말에는 아무것도 하지 않고 다시 묻습니다.

| 말 | 하는 일 | 앱의 대답(예) |
|---|---|---|
| “다시 들려줘” · “뭐라고?” | 마지막 문장을 다시 | (마지막 문장) |
| “약속 알려 줘” · “약속은 잘 지키고 있어?” | 지금 약속 상태 — 위험한 약속만 이름으로, 아직 판단할 수 없는 약속은 따로(1.2.2) · ‘약속을/약속은/약속 좀’처럼 조사가 붙어도 알아들음(1.3.0) | “해 지기 전 귀가가 위험해요. 나머지는 지키는 중이에요.” · 목적지 전: “약속 4개는 지키는 중이에요. 해 지기 전 귀가는 아직 몰라요.” |
| “조용히 해” | 10분 동안 안내성 말(INFO)만 미룸 — 약속 경고는 그대로 | “네, 10분 동안 조용히 할게요.” |
| (쉬기 질문에) “응” · “쉴게” | 쉬기 의향을 기록 | “좋아요. 다음 쉴 곳에서 쉬어요.” |
| (쉬기 질문에) “아니” · “괜찮아” | 기록만 하고 더 묻지 않음 | “알겠어요. 조심히 가요.” |

## 2. 실행 방법

### 2-1. APK로 바로 실행 (심사용 · 약 5분)

0. Pleos Connect 에뮬레이터를 **Cold Boot**로 켭니다(아래 3장). 소리는 켜 둡니다.
1. 설치합니다. `-g`는 위치·알림 런타임 권한을 함께 허용합니다.
2. Pleos 음성(TTS·STT)·위치(FusedLocation)·차량 권한을 확실히 허용합니다(이미 허용됐으면 아무 일도 없음). Pleos 에뮬레이터에서는 앱이 운전자 사용자로 돌기 때문에 `--user current`를 붙입니다.
3. 실행합니다. 앱 목록의 ‘운전약속’을 눌러도 됩니다.

```bash
adb install -r -g apk/DrivePromiseTeam_DrivePromise_release.apk
adb shell pm grant --user current app.drivepromise pleos.car.permission.TTS_SERVICE
adb shell pm grant --user current app.drivepromise pleos.car.permission.FUSED_LOCATION
adb shell pm grant --user current app.drivepromise pleos.car.permission.STT_SERVICE
adb shell pm grant --user current app.drivepromise android.permission.RECORD_AUDIO
adb shell pm grant --user current app.drivepromise android.car.permission.CAR_SPEED
adb shell am start -n app.drivepromise/.MainActivity
```

4. 정차 화면 아래의 **▶ 버튼**으로 시연 주행(리플레이) 하나를 고릅니다. 기록된 주행을 빠르게 재생하며, 실제 차량 신호와 같은 경로로 판단·발화합니다.

| 버튼 | 장면 | 길이 | 들어야 할 말 |
|---|---|---|---|
| ▶ hospital_home | 병원 → 집, 해 지기 직전 | 약 50초(3배속) | 해 지기 전 도착 위험 → 주행 끝 요약 |
| ▶ rest_two_hours | 쉬지 않고 두 시간 | 약 2분 15초(60배속) | 두 시간 예고 → “두 시간째예요. 쉬어 갈까요?”(음성 인식이 되면 질문, 이어서 5초 듣기 — 듣는 동안 재생도 멈춤) → 2시간 13분에 도착 → 요약 두 줄 |
| ▶ zone_edge | 약속 구역 경계 | 약 1분 40초(10배속) | 구역 끝 → 구역 밖 → 돌아옴 → 요약 |
| ▶ highway_rain | 고속도로 진입 · 비 | 약 2분 20초(5배속) | 고속도로 갈림길 예고 → 고속도로 진입 → 비 시작 → 빗길 과속 → 요약 |

5. 주행이 시작되면 **주행 화면**이 뜨고 약속 문장이 Pleos TTS로 들립니다. 주행이 끝나면 정차 화면의 ‘주행 끝 요약’에 약속별 결과가 남습니다. 멈추려면 **■ 리플레이 멈춤**.

### 2-2. 명령으로 실행 (adb)

먼저 `SVC=app.drivepromise/.DrivePromiseService` 를 한 번 입력해 두면 아래 명령을 그대로 붙여 넣을 수 있습니다. 이 서비스는 `android.permission.DUMP`로 보호되어 **adb 셸과 앱 자신만** 부를 수 있고, 다른 설치 앱은 부를 수 없습니다(1.3.0).

| 하는 일 | 명령 |
|---|---|
| 시연 주행 시작 | `adb shell am start-foreground-service -n $SVC --es cmd replay --es name hospital_home` |
| 멈추고 실차 모드로 | `adb shell am start-foreground-service -n $SVC --es cmd stop_replay` |
| 출발 전 계획(판교역 예시) | `adb shell am start-foreground-service -n $SVC --es cmd plan` |
| 다시 들려줘 | `adb shell am start-foreground-service -n $SVC --es cmd repeat` |
| 말하기(버튼과 같음 — 6초 듣기) | `adb shell am start-foreground-service -n $SVC --es cmd listen` |
| 음성 인식을 건너뛰고 글자로 명령(시험용) | `adb shell am start-foreground-service -n $SVC --es cmd stt_text --es text "'약속 알려 줘'"` |
| 질문 뒤 듣기 끄기(`on`: 켜기) | `adb shell am start-foreground-service -n $SVC --es cmd voice --es value off` |
| 영어로(`ko`로 되돌림) | `adb shell am start-foreground-service -n $SVC --es cmd lang --es value en` |
| 주행 중 음성만(`on`: 화면+음성) | `adb shell am start-foreground-service -n $SVC --es cmd screen --es value voice` |
| 상태를 로그로 | `adb shell am start-foreground-service -n $SVC --es cmd status` |
| 약속 끄기·켜기(`dark`·`highway`·`rain`·`rest`·`zone`) | `adb shell am start-foreground-service -n $SVC --es cmd promise --es id rest --es value off` |
| 쉬기 간격(분)·해 지기 전 여유(분)·구역 반경(m) | `... --es cmd rest_every --es value 90` · `--es cmd dark_margin --es value 45` · `--es cmd zone_radius --es value 5000` |
| 시동 때 자동 시작 끄기(`on`: 켜기) | `adb shell am start-foreground-service -n $SVC --es cmd autostart --es value off` |
| 앱 기록 보기 | `adb logcat -s DP` |

### 2-3. 위치(Pleos FusedLocation)로 약속 구역 확인

앱은 위치를 **Pleos FusedLocation SDK**에서 받습니다(안 되면 Android LocationManager로 자동 전환). 에뮬레이터 위치는 `adb emu geo fix <경도> <위도>`로 바꿀 수 있습니다(경도가 먼저). 기본 약속 구역은 서울시청 반경 10 km입니다. 세워 둔 채로 아래를 한 줄씩 10초 간격으로 넣습니다.

```bash
adb emu geo fix 126.9780 37.5665
adb shell am start-foreground-service -n app.drivepromise/.DrivePromiseService --es cmd stop_replay
adb emu geo fix 126.9780 37.6474
adb emu geo fix 126.9780 37.6551
adb emu geo fix 126.9780 37.6609
adb emu geo fix 126.9780 37.6384
adb logcat -d -s DP | grep -E "loc_fix|geofence|zone_xcheck|status"
```

| 위치(중심에서) | ‘약속 구역’ 타일 | 정차 화면 부제 | 기록(logcat DP) |
|---|---|---|---|
| 중심 → 실차 세션 새로 시작 | 지키는 중 | `위치 Pleos` | `location … src=pleos s=registered` · `geofence s=add r=10000` |
| 9.0 km | 지키는 중 | 〃 | `loc_fix src=pleos lat=37.647`(기록은 소수 3자리) |
| 9.85 km(구역 끝 300 m 안) | 지키는 중 — 세워 둔 차는 ‘바깥으로 가는 중’이 아니라 예고하지 않음(1.3.0, GPS 흔들림 오경보 방지) | 〃 | `status … zone:ON_TRACK` |
| 10.5 km(밖) | 못 지킴 | `구역 밖(Pleos 확인)` | `zone:MISSED` · `geofence s=transition tr=EXIT` · `zone_xcheck agree=true` |
| 8.0 km(돌아옴) | 지키는 중 | `구역 안(Pleos 확인)` | `zone:ON_TRACK` · `tr=ENTER` · 말 “약속 구역으로 돌아왔어요.” |

약속 경고(구역 끝·밖)는 **주행 중에만** 말합니다. 세워 둔 채로는 타일·기록으로 확인하고, 경고 문장은 주행 장면(▶ zone_edge 시연 주행)에서 들을 수 있습니다. 판정은 코어가 거리(하버사인)로 하고, Pleos 근접 알림은 같은 구역을 플랫폼이 따로 확인한 신호로 기록·표시에만 씁니다(두 판단이 같은지 `zone_xcheck`로 남김).

### 2-4. 음성 명령(Pleos SpeechToText) 확인

1. 에뮬레이터가 Mac 마이크를 받게 합니다: 에뮬레이터 패널 **⋯(Extended Controls) → Microphone → ‘Virtual microphone uses host audio input’ 켜기**, 또는 `adb emu avd hostmicon`(끄기 `adb emu avd hostmicoff`). 처음이면 macOS가 마이크 권한을 물음 — 허용. 에뮬레이터를 다시 켜면 꺼져 있습니다.
2. 정차 화면 ‘음성으로 말하기’ 칸의 **🎤 말하기**를 누르고 ‘띵’ 뒤에 “약속 알려 줘”라고 말합니다. 같은 칸에 `들은 말: ‘약속 알려 줘’ → 약속 상태`가 보이고 대답이 들립니다.
3. ▶ rest_two_hours 를 틀고 “두 시간째예요. 쉬어 갈까요?” 뒤 ‘띵’이 울리면 5초 안에 “응”이라고 말합니다. “좋아요. 다음 쉴 곳에서 쉬어요.”가 들리고, 주행이 이어져 도착하면 요약 두 줄이 들립니다(듣는 동안은 재생 시계가 멈춤 — 기록 `replay_hold`).
4. 마이크를 쓸 수 없으면 2-2의 글자 명령(`stt_text`)으로 뜻 풀이·대답만 확인할 수 있습니다(이때는 음성 인식기를 건너뜀).

```bash
adb logcat -d -s DP | grep -E "stt_init|stt_listen|stt_result|voice_cmd"
```

| 기록 | 뜻 |
|---|---|
| `stt_init ok=true mode=ON_DEVICE sdk=SpeechToText-3.3.1` | Pleos 음성 인식 준비됨(안 되면 앱은 버튼과 질문 없는 문장으로 동작) |
| `stt_listen ok=true trigger=BUTTON maxMs=6000` | 말하기 버튼으로 듣기 시작(질문 뒤면 `trigger=QUESTION maxMs=5000`) |
| `stt_result … why=final` | 인식 끝(release 빌드는 들은 글자 대신 글자 수만 기록) |
| `voice_cmd intent=STATUS` | 뜻 풀이 결과(REPEAT·STATUS·QUIET·YES·NO·UNKNOWN) |

### 2-5. 실제 차량 신호로 (Virtual Console)

1. 정차 화면에서 **실차 모드로**를 누르거나 `cmd stop_replay`.
2. 에뮬레이터 안의 Virtual Console을 Mac 브라우저로 엽니다: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/` (Cold Boot·adb 재시작 뒤에는 다시 forward).
3. 기어 D·속도·방향지시등을 바꾸면 앱이 같은 판단을 실시간으로 합니다. 와이퍼(‘비 오는 날 조심’의 신호)는 Pleos Vehicle SDK로 받습니다. 공식 문서의 모양(`registerWiperState(WindowArea(FRONT_WINDSHIELD), 리스너)`, 권한 `CAR_WINDOWS`)으로 등록합니다 — 11/6 에뮬레이터에서 등록과 첫 값(`OFF`)을 확인했습니다(기록 `vsdk_area`·`vsdk_reg domain=Wiper argc=2`·`vsdk domain=Wiper`).

### 2-6. 소스에서 빌드

| 하는 일 | 명령 |
|---|---|
| 판단 코어 스펙 100개(JDK 17만 있으면 됨) | `./gradlew :core:test` |
| 디버그 APK | `./gradlew :app:assembleDebug` |
| release APK(`~/.drivepromise/signing.properties`가 있으면 그 키로 서명) | `./gradlew :app:assembleRelease` |

서명 키·암호는 저장소에 없습니다. `~/.drivepromise/signing.properties`(storeFile·storePassword·keyAlias·keyPassword)를 직접 만들거나 `-PdpSigning=<경로>`로 지정합니다. 음성 인식 SDK를 받을 수 없는 환경에서는 `-PdpStt=false`로 빼고 빌드할 수 있습니다(앱은 음성 명령 없이 동작).

## 3. 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect 시스템 이미지 — Android 14 (API 34, user 빌드), 2560×1440, RAM 4 GB |
| 부팅 | **Cold Boot** 필수(Quick Boot면 차량 신호 VHAL이 붙지 않음) |
| 앱 SDK | minSdk 33 · targetSdk/compileSdk 35 · Kotlin 2.0.0 · AGP 8.8.2 · Gradle 8.10.2 · JDK 17 |
| Pleos SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 · FusedLocation 1.1.3 (Pleos Playground Maven, 토큰 불필요) |
| 권한 | 위치·알림·마이크(설치 `-g`) · `pleos.car.permission.TTS_SERVICE` · `STT_SERVICE` · `FUSED_LOCATION` · 차량 속성(CAR_SPEED 등) — 2-1의 명령 |
| 마이크 | 에뮬레이터 Extended Controls → Microphone → ‘Virtual microphone uses host audio input’(기본 꺼짐) 또는 `adb emu avd hostmicon` — 2-4 |
| 소리 | 에뮬레이터 오디오 출력 켜기(Mac 스피커). 앱은 내비 안내 용도(NAVIGATION_GUIDANCE)로 말함 |
| 네트워크 | 약속 판단·일몰 계산·음성 합성·음성 인식(둘 다 ON_DEVICE)은 오프라인 동작. 출발 전 계획·ETA는 내비 경로(NaviHelper)를 씀 — 경로가 없으면 해당 판단만 ‘판단 못 함’ |
| 주행 중 화면 | 주행 화면은 `distractionOptimized=true`로 선언되어 있고, Pleos 마켓 심사로 DO가 인정된다는 **가정**(설정 ‘DO 인정 가정: 켬’)으로 띄웁니다. 사이드로드 설치에서 플랫폼이 가리면 앱이 감지해 음성만으로 내려갑니다 |
| 시험한 범위 | 에뮬레이터 리플레이 4종(발화 열·요약·물러서기·주행 화면) · 코어 스펙 100개 · Virtual Console 신호(기어·속도·방향지시등) · 위치: geo fix로 약속 구역 경계 넘기(Pleos 좌표 수신·코어 판정·근접 알림 — 2-3) · 음성 명령: 뜻 풀이·질문·조용히·듣는 동안 말 보류·질문일 때만 듣기·리플레이 멈춤(스펙 S77~S82)과 에뮬레이터 시험(2-4) |

**알려진 제한(1차 제출 기준)** — 실차 시험 전입니다. 기본 약속은 5개(쉬기 2시간 · 해 지기 30분 전 · 약속 구역 서울시청 반경 10 km)이고 정차 화면 ‘약속 정하기’에서 바꿉니다. 출발 전 계획은 내비가 주는 추천 경로 1개의 도착 시각으로 일몰 여유를 판단하며(목적지는 시연용 판교역 버튼), 에뮬레이터 경로에는 도로 종류가 없어 ‘고속도로가 있는지는 알 수 없어요’라고 말합니다. 시동 자동 시작은 설치 뒤 한 번 앱을 연 다음부터 동작합니다. 에뮬레이터 VHAL에는 기어·속도만 있어 방향지시등·와이퍼·전조등은 Pleos Vehicle SDK로 받습니다. 위치는 Pleos FusedLocation SDK 1.1.3(위치 업데이트 + 약속 구역 근접 알림)을 쓰고, 지원하지 않거나 권한이 없거나 15초 동안 좌표가 없으면 Android LocationManager로 자동 전환합니다. 음성 명령은 Pleos SpeechToText 3.3.1(ON_DEVICE)로 다섯 가지 말만 알아듣는 닫힌 문법이며, 음성 인식을 쓸 수 없으면 버튼과 질문 없는 문장으로 동작합니다. 마이크 권한(RECORD_AUDIO)이 꼭 필요한지는 실차·마켓 심사 전에 확인해 필요 없으면 뺍니다. 운전자 화면 문장 길이는 플랫폼 허용 길이(기본 120자) 안으로 자릅니다.

## 4. APK 파일 위치

- **`apk/DrivePromiseTeam_DrivePromise_release.apk`** — release 빌드(난독화 없음), 출품용 전용 키로 서명(서명 인증서 CN=DrivePromise Team)
- 체크섬: `apk/SHA256SUMS.txt` · 설치: 2-1
- GitHub 파일 크기 제한을 넘는 경우에만 Releases에 올립니다(현재는 저장소 안).

## 5. 구조

| 경로 | 내용 |
|---|---|
| `core/` | 순수 Kotlin 판단 코어 — 약속 엔진(5종) · 일몰 계산(NOAA) · 출발 전 계획 · 발화 스케줄러(늦은 알림 규칙) · 내비에 물러서기 · 침묵 구간 게이트 · 주행 세션(상태 기계) · 화면 모드 결정 · 음성 명령 뜻 풀이(`voice/`) · 리플레이 재생기 · 기기 안 기록. 스펙 100개 |
| `app/` | Pleos 차량용 앱 — `platform/`(Vehicle SDK · CarPropertyManager · CarUxRestrictions · NaviHelper · Pleos FusedLocation(+LocationManager 대체) · Pleos TextToSpeech · Pleos SpeechToText) · `DrivePromiseService`(10 Hz 판단 루프, 포그라운드) · `MainActivity`(정차 화면) · `DriveActivity`(주행 화면) · `store/`(기록·설정) · `replay/` |
| `demo/trips/` | 시연 주행 4개(JSON) — 앱 assets이자 코어 스펙 S72의 입력 |
| `docs/` | `MANUAL.md`(기동·재기동·운영) · `contracts.md`(설계 계약) · `demo/`(시연 영상) |
| `probes/g1b/` | 기술 확인용 프로브 앱(주행 중 화면 차단·음성·내비 겹침·차량 신호 — 따로 빌드) |
| `apk/` | 제출용 release APK |

**설계 원칙**
- 판단·발화는 화면과 분리된 포그라운드 서비스에서 계속(화면은 ‘보여 주는 층’만)
- 모르면 말하지 않음 — 신호가 없거나 경로가 없으면 그 약속은 ‘모름’으로 두고 경고하지 않음
- 한 번에 한 문장, 상태가 바뀔 때만 말함 · 같은 말 반복 금지 · 안전 경고만 침묵 구간 예외
- 늦은 알림은 말의 종류로 정함 — 계속되는 상태(2시간 쉬기)는 미뤘다가 말하고, 지점·시각이 지난 말은 버림
- 기록은 기기 안(`files/ledger/*.jsonl`)에만 저장, 외부 전송 없음
- 다른 앱은 이 앱의 서비스를 부를 수 없음(시험 명령은 adb 셸 전용, `android.permission.DUMP` 보호)
- 늘 듣지 않음 — 버튼을 누를 때와 앱이 물은 직후 5초만 듣고, 듣는 동안은 말하지 않음, 모르는 말엔 행동하지 않음

## 6. 시험

```bash
./gradlew :core:test
```

결과는 `core/build/test-results/test/*.xml`(S01~S100, 100개 — 1.3.0 검토 반영 S84~S100 포함)에 남습니다.

에뮬레이터 스모크(설치 → 위치 시험 → 음성 명령 시험 → 리플레이 4개 → 발화 열·요약·주행 화면·질문 뒤 듣기 판정)는 개발 PC의 실행 스크립트로 돌립니다(저장소 밖).

## 7. 개인정보·안전

- 위치·주행 기록은 차량 안에만 남고 서버로 보내지 않습니다. 가족 공유는 하지 않습니다(향후 동의 기반 요약만 검토).
- 좌표는 판단에만 쓰고 기록 파일에는 저장하지 않습니다(기기 로그(logcat)에는 시험 확인용으로 약 100 m 단위로만 남김 — 다른 앱은 이 로그를 읽을 수 없음).
- 주행 중에는 입력을 요구하지 않습니다. 큰 버튼은 ‘다시 들려줘’·‘🎤 말하기’ 둘, 문장은 한두 문장(실측 길이로 회전 전에 끝낼 수 있을 때만 시작).
- 마이크는 버튼을 누를 때와 쉬기 질문 직후 5초만 열립니다. 마이크로 들은 말의 원문은 어디에도 기록하지 않고 뜻(예: `STATUS`)만 기기 안 기록에 남깁니다(시험용 글자·음성 주입 명령의 글자만 개발 로그에 남음). ‘질문 뒤 듣기’는 정차 화면에서 끌 수 있습니다.
- 판단이 틀릴 수 있는 상황(신호 없음·경로 없음)에서는 침묵합니다. 이 앱은 운전 보조 안내이며 운전자의 판단을 대신하지 않습니다.

## 8. 개발 방식 — 생성형 AI 활용 고지

- 문제 정의, 약속 규칙과 설계 판단, 시험 기준, 에뮬레이터 실행 검증은 참가자가 직접 했습니다.
- 코드·문서·시연 영상 제작에는 생성형 AI 도구(Anthropic Claude)를 활용했습니다. AI가 만든 결과물은 참가자가 모두 검토하고, 자동 시험(100개)과 Pleos Connect 에뮬레이터 실행으로 확인했습니다.
- 일부 영상·그림 파일에는 이 도구가 붙인 출처 정보(C2PA 콘텐츠 자격 증명)가 들어 있습니다. 개인 정보는 들어 있지 않습니다.

*Built with AI assistance: the participant defined the problem, the promise rules, the design decisions and test criteria, and verified everything on the emulator; code, documents and the demo video were produced with a generative AI tool (Anthropic Claude) and reviewed by the participant. Some media files carry C2PA provenance metadata.*

---

공모전 출품작입니다. 저작권과 권리는 출품자에게 있습니다.
