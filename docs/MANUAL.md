# 운전약속(DrivePromise) 기동·재기동·운영 매뉴얼

대상: 심사·시연·개발에서 Pleos Connect 에뮬레이터로 앱을 실행하는 사람. 버전 1.3.5(versionCode 12) 기준 — 위치는 Pleos FusedLocation SDK(안 되면 LocationManager 자동 전환), 음성 명령은 Pleos SpeechToText SDK(ON_DEVICE, 안 되면 버튼만).

## 0. 한눈에

| 하고 싶은 일 | 명령 / 동작 |
|---|---|
| 처음 설치·실행 | 1장 |
| 앱만 다시 시작 | `adb shell am force-stop app.drivepromise && adb shell am start -n app.drivepromise/.MainActivity` |
| 에뮬레이터를 다시 켠 뒤 | 2-2 (Cold Boot → 권한 확인 → forward) |
| 시연 주행 | 정차 화면 ▶ 버튼 또는 `--es cmd replay --es name <트립>` |
| 지금 상태 보기 | `--es cmd status` 후 `adb logcat -s DP` |
| 위치·약속 구역 확인(geo fix) | 3-5 |
| 음성 명령(말하기·질문 뒤 듣기) | 3-6 |
| 약속 바꾸기(가족과 함께, 세워 둔 동안) | 정차 화면 ‘약속 정하기’ 또는 3-2 명령 |
| 시동 때 자동 시작 · 다음 주행 | 3-8 |
| 모두 지우고 처음부터 | `adb uninstall app.drivepromise` → 1장 |

명령의 `SVC`는 `app.drivepromise/.DrivePromiseService` 입니다. 터미널에서 먼저 한 번 입력해 두세요. 1.3.0부터 이 서비스는 `android.permission.DUMP`로 보호되어 adb 셸(그 권한을 가짐)과 앱 자신만 부를 수 있습니다 — 아래 명령은 그대로 동작하고, 다른 설치 앱은 부를 수 없습니다.

```bash
SVC=app.drivepromise/.DrivePromiseService
```

## 1. 처음 기동

### 1-1. 에뮬레이터

1. Android Studio → Device Manager → Pleos Connect 기기(Android 14, API 34) → ▾ → **Cold Boot Now**.
   - Quick Boot로 켜면 차량 신호(VHAL)가 붙지 않아 기어·속도가 들어오지 않습니다.
2. 부팅이 끝나면(홈 화면·내비가 보이면) Mac 소리를 켭니다. 앱은 Pleos TTS로 말합니다.

### 1-2. 설치·권한

`adb devices`에 `emulator-5554 device`가 보이면 설치하고 권한을 허용합니다. Pleos 에뮬레이터는 앱이 운전자 사용자로 돌기 때문에 `--user current`를 붙입니다.

```bash
adb devices
adb install -r -g apk/DrivePromiseTeam_DrivePromise_release.apk
adb shell pm grant --user current app.drivepromise pleos.car.permission.TTS_SERVICE
adb shell pm grant --user current app.drivepromise pleos.car.permission.FUSED_LOCATION
adb shell pm grant --user current app.drivepromise pleos.car.permission.STT_SERVICE
adb shell pm grant --user current app.drivepromise android.permission.RECORD_AUDIO
adb shell pm grant --user current app.drivepromise android.car.permission.CAR_SPEED
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 나오면 다른 키로 서명된 이전 빌드가 있는 것입니다 → `adb uninstall app.drivepromise` 후 다시 설치.

### 1-3. 실행과 첫 확인

```bash
adb shell am start -n app.drivepromise/.MainActivity
```

정차 화면에서 확인할 것:

| 위치 | 정상 |
|---|---|
| D·R·I·V·E 줄(1.3.5) | 제목 아래 한 줄 — `D·R·I·V·E 다섯 약속 — 해 지기 전(Daylight) · 비(Rain) · 쉬기(Interval) · 약속 구역(Vicinity) · 고속도로 대신(Expressway)`. 표시만 하는 줄이며 판단·말과 무관(영어 화면에서는 영어 문장) |
| 부제 | `세워 둠 · 연속 운전 0분 · 실차 · 위치 Pleos · …` (Pleos 위치를 못 쓰면 `위치 GPS(대체)`) |
| 약속 타일 5개 | 초록(지키는 중) 또는 회색(모름 — 신호가 아직 없음). 해가 진 뒤 세워 둔 차면 ‘해 지기 전 귀가’가 주황(위험 — 지금 출발하면 해 진 뒤, 말은 하지 않음)이 정상. 단 에뮬레이터 기본 위치처럼 좌표가 기기 시간대(Asia/Seoul)와 맞지 않으면 회색(모름 — 1.3.3, S121) |
| 음성으로 말하기 칸 | ‘다시 · 약속 알려 줘 · 조용히 해 — 쉬기 질문엔 응이나 아니’ + 🎤 말하기 · 질문 뒤 듣기: 켬 (음성 인식이 안 되면 ‘말하기 없음’·‘다시 시도’) |
| 버튼 줄 | 경로 계획 · 주행 끝 · 한/영 · 실차 모드로 / 주행 중 화면 · DO 인정 가정 · 주행 화면 미리보기 / ▶ 시연 주행 4개 · ■ 리플레이 멈춤 |
| 약속 정하기(1.3.0) | 약속 5개 켬/끔 · 쉬기 간격 · 해 지기 전 여유 · 구역 반경 · 지금 위치를 구역 중심으로 · 시동 때 자동 시작 (세워 둔 동안 가족과 함께 — 3-2) |

음성이 준비됐는지:

```bash
adb logcat -d -s DP | grep -E "tts_init|stt_init|svc|location"
```

`tts_init … ok=true`, `stt_init … ok=true mode=ON_DEVICE`, `location … src=pleos s=registered`가 보이면 정상입니다.

## 2. 재기동

### 2-1. 앱만 다시

```bash
adb shell am force-stop app.drivepromise
adb shell am start -n app.drivepromise/.MainActivity
```

서비스는 정차 화면이 다시 띄웁니다.

서비스만 다시 시작하려면 `adb shell am start-foreground-service -n $SVC --es cmd status`(서비스가 없으면 새로 뜸).

### 2-2. 에뮬레이터를 다시 켠 뒤

1. **Cold Boot**로 켭니다(1-1).
2. 앱 권한은 유지됩니다. 음성이 안 나오거나, 부제가 `위치 GPS(대체)`이거나, ‘말하기’가 안 되면 1-2의 `pm grant` 줄을 다시 실행하고 앱을 다시 시작합니다. 에뮬레이터 마이크 설정(3-6)은 다시 켜야 할 수 있습니다.
3. Virtual Console을 쓸 때는 포워딩을 다시 겁니다(Cold Boot·adb 재시작 때마다 풀림):

```bash
adb forward tcp:8088 tcp:8088 && open http://127.0.0.1:8088/
```

### 2-3. 시연 주행 중에 멈췄을 때

정차 화면 **■ 리플레이 멈춤** 또는 `adb shell am start-foreground-service -n $SVC --es cmd stop_replay` → 실차 모드로 돌아옵니다. 같은 트립을 다시 누르면 처음부터 재생합니다.

## 3. 운영

### 3-1. 시연 주행(리플레이)

| 트립 | 명령 | 길이 | 기대 발화(순서) | 끝 요약 |
|---|---|---|---|---|
| hospital_home | `--es cmd replay --es name hospital_home` | 약 50초 | 해 지기 전 도착 위험(17:16 출발 · “이대로면 5시 19분 도착, 해는 5시 22분에 져요.” → 17:18 도착 = 지킴) | 약속 결과 + 수고 인사 |
| rest_two_hours | `... --es name rest_two_hours` | 약 2분 15초 | 두 시간 예고 → “두 시간째예요. 쉬어 갈까요?”(음성 인식이 되면 질문 + 5초 듣기, 듣는 동안 재생 멈춤) → 2시간 13분 도착 | 2시간마다 쉬기 놓침(요약 두 줄) |
| zone_edge | `... --es name zone_edge` | 약 1분 40초 | 구역 끝 → 구역 밖 → 돌아옴 | 약속 구역 놓침 |
| highway_rain | `... --es name highway_rain` | 약 2분 20초 | 갈림길 예고(“앞 갈림길은 판교IC 방면이에요. 약속대로 일반도로로 가요.”, 1.3.0) → 고속도로 진입 → 비 시작 → 빗길 과속 | 고속도로·비 놓침 |

주행이 시작되면 주행 화면이 뜹니다(정차 화면이 앞에 있을 때). 주행 화면은 모드가 바뀌면(세움·음성만) 스스로 닫힙니다.

1.2.1: 리플레이 중 운전자의 대답을 듣는 동안은 재생 시계가 멈추고(`replay_hold on/off`), 주행이 끝나면 요약을 끝까지 말한 뒤 실차 모드로 돌아옵니다(최소 4초·최대 45초, `replay_back why=idle|timeout`). 화면에 띄운 문장은 `screen s=drive_text`로 남습니다.

### 3-2. 설정

| 설정 | 화면 | 명령 | 기본 |
|---|---|---|---|
| 언어 | 한/영 | `--es cmd lang --es value en` (또는 `ko`) | 한국어 |
| 주행 중 화면 | 주행 중 화면: 켜기/음성만 | `--es cmd screen --es value voice` (또는 `on`) | 켜기 |
| DO 인정 가정 | DO 인정 가정: 켬/끔 | `--es cmd assume_do --es value 0` (또는 `1`) | 켬 |
| 다시 들려줘 | 주행 화면 버튼 | `--es cmd repeat` | — |
| 말하기(6초 듣기) | 정차·주행 화면 🎤 말하기 | `--es cmd listen` | — |
| 질문 뒤 듣기 | 질문 뒤 듣기: 켬/끔 | `--es cmd voice --es value off` (또는 `on`) | 켬 |
| 약속 켜기·끄기(1.3.0) | 약속 정하기: 약속마다 켬/끔 | `--es cmd promise --es id rest --es value off` (`id`: dark·highway·rain·rest·zone) | 모두 켬 |
| 쉬기 간격(1.3.0) | 약속 정하기: 1시간 · 90분 · 2시간 | `--es cmd rest_every --es value 90` | 120분 |
| 해 지기 전 여유(1.3.0) | 약속 정하기: 15·30·45·60분 | `--es cmd dark_margin --es value 45` | 30분 |
| 약속 구역 반경(1.3.0) | 약속 정하기: 3·5·10·20 km | `--es cmd zone_radius --es value 5000`(m) | 10 km |
| 약속 구역 중심(1.3.0) | 약속 정하기: 지금 위치를 구역 중심으로 | `--es cmd zone_here` | 서울시청 |
| 시동 때 자동 시작(1.3.0) | 약속 정하기: 켬/끔 | `--es cmd autostart --es value off` (또는 `on`) | 켬 |
| 설정을 처음 값으로(1.3.1) | — | `--es cmd defaults` — 위 값 모두(약속 5개 켬 · 120분 · 30분 · 서울시청 10 km · 한국어 · 화면 켬 · DO 인정 가정 켬 · 질문 뒤 듣기 켬 · 자동 시작 켬). 쉬기 기록은 그대로 | — |

약속 설정은 세워 둔 동안(정차·주행 끝) 바꾸는 것을 전제로 합니다. 바꾸면 기록 `settings …`가 남고, 실차 세션이 정차·끝 상태면 새 설정으로 세션을 다시 시작합니다(주행 중이면 다음 주행부터). 쉬기 간격을 줄이면 예고 시점도 함께 당겨집니다(간격 90분 → 80분에 예고).

‘DO 인정 가정: 끔’이면 플랫폼이 이 앱의 주행 화면을 인정한다고 확인될 때만 주행 화면을 띄우고, 아니면 음성만 씁니다.

### 3-3. 출발 전 계획

정차 화면 **경로 계획: 판교역** 또는 `--es cmd plan` → 내비 경로(NaviHelper)를 받아 약속(해 지기 전·고속도로)에 비추어 경로와 출발 시한을 ‘출발 전 계획’ 칸에 보여 주고 말합니다. 경로를 받지 못하면 그 사실을 알려 주고 판단하지 않습니다(오류 코드는 말하지 않고 기록 `plan error=`에만).

1.2.1: 안내 중인 경로가 있을 때 새 경로를 요청하면 내비가 먼저 ‘앞 경로 취소(onRouteCancelled)’를 보냅니다. 요청 뒤 3초 안의 취소는 실패로 보지 않고 새 경로를 최대 15초 더 기다립니다(기록 `route_replaced`).

### 3-4. 실차 모드(Virtual Console)

`실차 모드로` → Virtual Console에서 기어 D·속도·방향지시등·와이퍼를 바꿉니다. 판단은 리플레이와 같은 코어가 합니다. 와이퍼는 Pleos Vehicle SDK `registerWiperState(WindowArea, WiperStateListener)`로 받습니다 — 1.2.1에서 영역 `WindowArea(FRONT_WINDSHIELD)`를 만들어 등록하고 권한 `pleos.car.permission.CAR_WINDOWS`(설치 때 자동 허용)를 선언했습니다. `grep -E "vsdk_area|vsdk_reg|vsdk_methods"`로 등록 여부를 봅니다. 1.2.2부터 `vsdk_reg_missing`은 모든 등록 꼴이 실패했을 때만 남습니다(1.2.1은 한 인자 꼴이 없을 때 먼저 남겨, 두 인자 꼴 등록에 성공해도 ‘실패’처럼 보였음).

| 바꿀 것 | 앱 반응 |
|---|---|
| 기어 D(P가 아닌 기어) | 주행 시작 — 연속 운전 시간 계산(신호 대기도 포함), 주행 화면 |
| 와이퍼 60초 이상 | 비로 봄 — 한 문장(타일은 지키는 중) · 비 속에서 제한속도의 80%를 넘으면 바로 ‘위험’, 30초 넘게 이어지면 감속 문장 한 번(기준 속도는 말하는 순간의 제한속도로 다시 계산 — 1.3.4) · 주행 끝 ‘못 지킴’도 30초 넘게 이어졌을 때만(제한속도 정보가 없으면 60 km/h 기준) · 속도를 모르면 ‘모름’(1.3.4) |
| 방향지시등 켬 | 그동안 말하지 않음(침묵 구간) |
| 기어 P 15분 이상 | 쉰 것으로 봄 — 운전 시간 0으로, 쉬기 예고·알림 뒤였다면 다시 출발할 때 칭찬 한 번 |

### 3-5. 위치(Pleos FusedLocation)와 약속 구역

| 항목 | 내용 |
|---|---|
| 주 원천 | Pleos FusedLocation SDK 1.1.3 — `isSupported()` → 권한 `pleos.car.permission.FUSED_LOCATION` → `initialize()` → 위치 콜백 등록 + 마지막 위치 |
| 대체 | 지원 안 함 · 권한 없음 · 등록 오류 · 15초 동안 좌표 없음 · 10초 넘게 끊김 → Android LocationManager(1초). 둘 다 돌 때는 3초 안의 Pleos 좌표를 먼저 씀 |
| 약속 구역 판정 | 코어가 거리(하버사인)로 — 구역 끝 300 m 안에서 **움직이며 10초 동안 15 m 넘게** 멀어지면 ‘위험’(1.3.0: 세워 둔 차의 GPS 흔들림은 예고하지 않음), 밖이면 ‘못 지킴’, 돌아오면 ‘지키는 중’ · 밖/안은 **새 쪽 위치가 2번·5초 이어져야** 바뀌고(튄 위치 하나로는 안 바뀜 — 그사이 밖 위치는 ‘위험’만, 1.3.2), 돌아올 때는 경계 안쪽 100 m까지 들어와야 함 · 위치가 5분 넘게 끊기면 ‘판단할 신호 없음’(1.3.0) |
| 이중 확인 | 같은 구역에 Pleos 근접 알림(ENTER/EXIT)을 걸어 기록·부제 표시(`구역 밖(Pleos 확인)`)에 씀. 알림 **8초 뒤**(코어 확인 5초 + 여유 3초 — 코어의 `ZoneCrossCheck.delayMs`에서 계산, 1.3.5) 두 판단이 같은지 `zone_xcheck agree= afterMs=8000`으로, 코어가 바뀐 시각과의 차이를 `lagMs=`(양수 = 코어가 늦음 — 확인 시간 때문에 약 +5초가 정상)로 남김(코어 결정은 바꾸지 않음). 1.3.2~1.3.4는 3초 뒤에 비교해 두 방향 모두 `agree=false`가 나오는 구조였음(11/10 점검, S127) |
| 기록 | `location`(등록·대체) · `loc_src`(원천 바뀜) · `loc_fix`(좌표, 소수 3자리 ≈ 100 m — 1.3.0에서 4자리→3자리, 정확도 `r4`는 뺌) · `geofence`(add·registered·transition·removed) · `zone_xcheck` |

세워 둔 채 시험(기본 구역: 서울시청 반경 10 km, 경도가 먼저). 한 줄씩 10초 간격으로 넣습니다.

```bash
adb emu geo fix 126.9780 37.5665
adb shell am start-foreground-service -n $SVC --es cmd stop_replay
adb emu geo fix 126.9780 37.6474
adb emu geo fix 126.9780 37.6551
adb emu geo fix 126.9780 37.6609
adb emu geo fix 126.9780 37.6384
adb logcat -d -s DP | grep -E "loc_fix|geofence|zone_xcheck|status"
```

| 위치 | 약속 구역 타일 | 기대 기록 |
|---|---|---|
| 중심(세션 새로 시작) | 지키는 중 | `location … s=registered` · `geofence s=add` · `geofence s=registered` |
| 9.0 km | 지키는 중 | `loc_fix src=pleos lat=37.647` |
| 9.85 km | 지키는 중(1.3.0 — 세워 둔 시험이라 ‘바깥으로 가는 중’이 아님) | `status … zone:ON_TRACK` |
| 10.5 km | 못 지킴 | `zone:MISSED`(첫 밖 위치 약 5초 뒤) · `geofence … tr=EXIT` · `zone_xcheck … agree=true afterMs=8000` |
| 8.0 km | 지키는 중 | `zone:ON_TRACK` · `tr=ENTER` · 말 “약속 구역으로 돌아왔어요.” |

약속 경고(구역 끝·밖) 문장은 주행 중에만 말하므로 세워 둔 시험에서는 들리지 않는 것이 정상입니다. 끝나면 원래 위치로 되돌려 둡니다(예: `adb emu geo fix 127.0276 37.4979`).

### 3-6. 음성 명령(Pleos SpeechToText)

| 항목 | 내용 |
|---|---|
| 듣는 때 | ① **🎤 말하기**를 누를 때(최대 6초) ② 앱이 두 시간 쉬기 질문(“두 시간째예요. 쉬어 갈까요?”)을 끝낸 직후 5초. 늘 듣지 않음 |
| 듣기 전후 | 짧은 ‘띵’ 뒤에 마이크를 엶 · 우리 말·내비 안내가 나오는 중이면 끝나기를 기다림 · 듣는 동안 앱은 새 말을 시작하지 않음 · 내비 안내가 시작되면 듣기를 멈춤 · 듣는 중에 다시 누르면 그만 |
| 알아듣는 말 | 다시 들려줘 · 약속 알려 줘 · 조용히 해(10분 동안 안내성 말만 미룸) · (쉬기 질문에) 응/아니 — 그 밖의 말은 “잘 못 들었어요” 후 아무것도 안 함 · 질문 뒤 아무 말이 없으면 아무것도 안 함 |
| 조사(1.3.0) | ‘약속을 알려 줘’ · ‘약속은 잘 지키고 있어?’ · ‘약속 좀 알려 줘’처럼 조사가 붙어도 상태 질문으로 알아들음. 한국어 모드는 한국어 표만(영어 표를 섞지 않음 — 잘못 알아듣고 행동하는 일을 줄임) |
| ‘약속 알려 줘’ 대답(1.2.2) | 위험한 약속만 이름으로 · 아직 판단할 수 없는 약속(목적지가 없을 때의 해 지기 전 귀가 등)은 ‘모두’에 넣지 않고 따로: “약속 4개는 지키는 중이에요. 해 지기 전 귀가는 아직 몰라요.” |
| 인식기 | Pleos SpeechToText 3.3.1 ON_DEVICE(오프라인) · 언어는 앱 언어(ko-KR / en-US) · 실패하면 앱은 버튼·질문 없는 문장으로 동작 |
| 기록 | `stt_init`(준비) · `stt_wait`(말이 끝나기를 기다림) · `stt_listen`(듣기 시작) · `stt_result`(인식 끝 — release 빌드는 들은 글자 대신 글자 수) · `voice_cmd`(뜻) · 기기 안 기록(Ledger)에는 뜻(`VoiceCommand`)만 |

에뮬레이터에서 마이크로 시험하려면 먼저 에뮬레이터 패널 **⋯(Extended Controls) → Microphone → ‘Virtual microphone uses host audio input’**을 켜거나 `adb emu avd hostmicon`을 넣습니다(끄기 `adb emu avd hostmicoff`, 에뮬레이터를 다시 켜면 꺼진 상태). macOS가 마이크 권한을 물으면 허용하고, 헤드폰을 쓰면 Mac 스피커 소리가 마이크에 들어가지 않습니다. 그다음:

```bash
adb shell am start-foreground-service -n $SVC --es cmd listen
adb logcat -d -s DP | grep -E "stt_listen|stt_result|voice_cmd"
```

첫 줄을 넣고 ‘띵’ 뒤 6초 안에 “약속 알려 줘”라고 말합니다. 마이크 없이 뜻 풀이·대답만 확인하려면 글자로 넣습니다(음성 인식기를 건너뜀).

```bash
adb shell am start-foreground-service -n $SVC --es cmd stt_text --es text "'약속 알려 줘'"
adb shell am start-foreground-service -n $SVC --es cmd stt_text --es text "'다시 들려줘'"
```

| 기대 기록 | 뜻 |
|---|---|
| `stt_init ok=true mode=ON_DEVICE sdk=SpeechToText-3.3.1` | 준비됨 |
| `stt_listen ok=true trigger=BUTTON src=mic maxMs=6000` | 버튼으로 듣기 시작 |
| `stt_result trigger=BUTTON src=mic why=final len=…` | 인식 끝(`why=timeout_empty`면 들린 말 없음 — 마이크 설정 확인) |
| `voice_cmd intent=STATUS trigger=BUTTON` → `speak key=voice.status` | 뜻 풀이 → 대답 |

시험용 음성 파일 주입(개발 PC 실행 스크립트가 씀): `--es cmd stt_audio_begin --es label <이름>` → `--es cmd stt_audio_chunk --es pcm <base64>`(여러 번) → `--es cmd stt_audio_end --es trigger button`(또는 `question`). PCM은 16 kHz·16 bit·mono이며 앱이 100 ms(3,200 바이트)씩 실시간 속도로 Pleos 인식기에 넣습니다(ON_DEVICE 전용 기능 — 공식 문서: `request()` 없이 첫 버퍼로 인식 시작, 이때 `onReady`·`onUpdatedRms`는 오지 않음, 끝에 `completeAudioSend()`). 조각을 셸 반복문으로 보낼 때는 `adb shell … < /dev/null`처럼 표준 입력을 막아야 합니다 — `adb shell`이 반복문의 남은 줄을 먹어 첫 조각만 가는 일이 있음(11/6 실측: 0.19초만 전달). 앱 기록 `stt_feed s=send bytes=`가 만든 파일 크기와 같은지 확인합니다.

### 3-7. 기록과 로그

| 하는 일 | 명령 |
|---|---|
| 실시간 기록(event=… 키=값) | `adb logcat -s DP` |
| 상태 한 줄(event=status_now, 위치 원천 `loc=` 포함) | `adb shell am start-foreground-service -n $SVC --es cmd status` |
| 말한 문장·요약·화면 모드만 | `adb logcat -d -s DP \| grep -E "event=(speak\|summary\|mode)"` |
| 말 길이 실측(1.3.0) | `adb logcat -d -s DP \| grep -E "event=(dur\|tts_watchdog)"` — `dur … est= ms= over=`: 예상 길이(글자 수 × 0.165초 + 0.3초)와 실제 길이 · `tts_watchdog`: 재생 끝 알림이 오지 않아 말을 끊고 다시 시도(예상 길이 × 2.5, 최소 15초) |
| 보호 기록(1.3.2) | `adb logcat -d -s DP \| grep -E "event=(tts_cancel\|tts_stale\|tts_init_stale\|step_error\|fgs_error\|adapter_retry\|screen\|settings\|clock_jump\|arrival_cleared\|rest_undo_final_park\|boot)"` — `tts_cancel`·`tts_stale`: 세션을 바꿀 때 말을 멈추고 끊은 말의 끝 소식은 무시 · `step_error`: 판정 루프 예외(앱은 계속, 처음 5번·100번마다 기록) · `fgs_error`: 포그라운드 시작이 막힘(서비스는 조용히 멈춤) · `adapter_retry`: 차량·내비 연결 다시(30초마다 최대 10번 — 1.3.5부터 TTS 초기화가 실패했으면 다음 재시도에서, 10초 넘게 소식이 없었으면(`tts_init_timeout`) 시작 60초 뒤부터 `tts=true`로 함께 다시, 해제된 옛 인스턴스의 늦은 소식은 `tts_init_stale`로 무시) · `screen s=unblock`: 주행 화면 차단 기억을 주행 끝에 지움 · `settings apply=at_renew`: 주행이 막 끝났으면 요약 뒤 새 세션에서 반영 · `settings zoneHere=not_real`: 시연 중엔 구역 중심을 저장하지 않음 · (1.3.3) `clock_jump`: 벽시계가 5초 넘게 뜀(시각 보정) — 그 사이는 운전 시간에 넣지 않음 · `arrival_cleared`: 도착 소식 5분 뒤에도 달리는 중이라 도착을 지우고 주행을 이어 감 · `rest_undo_final_park`: 오래 세워 둬서 끝난 주행은 목적지 정차를 쉼으로 치지 않음 · `boot skip=headless_system_user`: 화면 없는 시스템 사용자(0)에서는 시작하지 않음 |

주행 기록은 앱 내부 저장소 `files/ledger/YYYY-MM.jsonl`에 남고 밖으로 보내지 않습니다. 좌표는 기록 파일에 저장하지 않습니다.

### 3-8. 시동 때 자동 시작과 다음 주행(1.3.0)

| 항목 | 내용 |
|---|---|
| 시동 때 자동 시작 | 설치 뒤 앱을 한 번 열면, 인포테인먼트가 켜질 때(`BOOT_COMPLETED`) 서비스가 스스로 시작(기록 `boot autoStart=true` · `svc … src=boot`). ‘약속 정하기’ 또는 `--es cmd autostart --es value off`로 끔 |
| 다음 주행 | 실차 주행이 끝나면 요약을 다 말한 뒤(최소 3초 · 최대 45초) 새 실차 세션을 엶(기록 `renew why=idle\|timeout waitedMs=`). 주행마다 앱을 다시 켤 필요 없음 |
| 확인 | `adb shell am start-foreground-service -n $SVC --es cmd end` → `adb logcat -d -s DP \| grep -E "event=(renew\|summary)"` |
| 재부팅 확인 | `adb reboot` → 부팅 뒤 `adb logcat -d -s DP \| grep -E "boot\|src=boot"` (Pleos 에뮬레이터는 다시 Cold Boot를 권장 — 차량 신호) |

### 3-9. 데이터 지우기

| 하는 일 | 명령 |
|---|---|
| 설정·기록 초기화(권한도 초기화 → 1-2 다시) | `adb shell pm clear app.drivepromise` |
| 앱 삭제 | `adb uninstall app.drivepromise` |

## 4. 문제 해결

| 증상 | 확인 | 조치 |
|---|---|---|
| 소리가 안 남 | `adb logcat -d -s DP \| grep tts_init` → ok=false | 30초 안에 저절로 다시 시도하는지 `adapter_retry … tts=true`·`tts_reinit` 확인(1.3.5) · 정차 화면을 열어 권한 허용(1.3.1: 허용하면 다시 시작 없이 TTS 재초기화 `tts_reinit`) 또는 `pm grant … TTS_SERVICE` · Mac 소리·에뮬레이터 오디오 확인 |
| 소리가 내비 안내에 묻힘 | 정상 동작 — 내비가 말하면 약 0.2초 만에 물러섰다가 1.5초 뒤 다시 말함 | — |
| 주행 화면이 안 뜸 | 정차 화면이 앞에 있었는지 · 부제의 모드 | 정차 화면을 앞에 두고 다시 · ‘주행 중 화면: 켜기’ 확인 · 사이드로드에서는 플랫폼이 가릴 수 있음(음성만이 정상) |
| 차량 신호가 안 들어옴(실차 모드) | Quick Boot로 켰는지 | Cold Boot로 다시 켬 |
| Virtual Console(127.0.0.1:8088)이 안 열림 | `adb forward --list` | `adb forward tcp:8088 tcp:8088` 다시 · 다른 프로그램이 8088을 쓰면 `tcp:18088 tcp:8088` |
| 설치 실패(UPDATE_INCOMPATIBLE) | 이전 빌드 서명이 다름 | `adb uninstall app.drivepromise` 후 설치 |
| 출발 전 계획이 ‘경로를 받지 못함’ | 내비 앱 상태·네트워크 | 내비 앱을 한 번 열고 다시 시도 |
| 부제가 `위치 GPS(대체)` | `adb logcat -d -s DP \| grep event=location` 의 `why=` | `no_permission` → 1-2의 FUSED_LOCATION `pm grant` 후 앱 재시작 · `unsupported` → Pleos 위치 서비스가 없는 이미지(대체로 정상 동작) · `no_pleos_fix_15s` → geo fix를 한 번 넣기 |
| 부제에 `구역 밖/안(Pleos 확인)`이 안 나옴 | `grep event=geofence` | `s=skip` → Pleos 위치가 준비되지 않음(위 줄) · 전이가 없으면 플랫폼이 근접 알림을 보내지 않은 것 — 약속 판정은 코어가 거리로 하므로 동작에는 지장 없음 |
| ‘말하기’가 ‘말하기 없음’·‘다시 시도’ | `grep -E "stt_init\|stt_perm"` | `err=no_sdk` → 음성 인식 SDK가 빠진 빌드(`-PdpStt=false`) · `PERMISSION_DENIED` → 1-2의 STT_SERVICE·RECORD_AUDIO `pm grant` 후 ‘다시 시도’ · `ON_DEVICE_ENGINE_ERROR` → 에뮬레이터 이미지의 음성 엔진 문제(앱은 버튼으로 동작) |
| 말해도 ‘잘 못 들었어요’ | `grep stt_result` 의 `why=`·`rms=` | `timeout_empty`·`rms=0.0` → 에뮬레이터 마이크가 꺼짐(3-6의 Extended Controls 또는 `adb emu avd hostmicon`) · 글자는 왔는데 뜻이 UNKNOWN → 알아듣는 말(3-6) 중 하나로 |
| 음성 파일 주입이 ‘들림 없음’ | `grep "stt_feed s=send"` 의 `bytes=` | 만든 PCM보다 작으면 조각이 덜 감(셸 반복문의 `adb shell`에 `< /dev/null`) · 같은데도 글자가 없으면 인식기(ON_DEVICE 엔진) 문제 — `stt_error` 확인 |
| 리플레이가 끝났는데 요약이 안 들림 | `grep -E "summary\|replay_back"` | 1.2.1부터 요약을 다 말한 뒤 실차로 돌아감(`replay_back why=idle`) — `why=timeout`이면 말이 45초 넘게 밀린 것(내비 안내가 길게 이어짐) |
| 말이 중간에 끊기고 다시 나옴 | `grep -E "tts_watchdog\|event=dur"` | 재생 끝 알림이 오지 않아 감시 타이머가 끊은 것(1.3.0). 세 번 실패하면 그 문장은 버림 — TTS 엔진·오디오 확인 |
| 주행이 끝났는데 다음 주행이 안 잡힘 | `grep event=renew` | 없으면 요약이 아직 남음(최대 45초) · 앱 강제 종료 뒤면 정차 화면을 한 번 열기 |
| 재부팅 뒤 앱이 안 뜸 | `grep -E "boot"` | 설치 뒤 한 번도 앱을 안 열었으면 안드로이드가 부팅 알림을 보내지 않음(정상) · ‘시동 때 자동 시작: 끔’인지 확인 |
| 질문 뒤에 듣지 않음 | 정차 화면 ‘질문 뒤 듣기’ · `grep -E "stt_wait\|stt_listen"` | ‘끔’이면 켬 · `why=busy` → 내비 안내가 길게 이어짐(다음 질문에서 다시) |
