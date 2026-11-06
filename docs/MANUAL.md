# 운전약속(DrivePromise) 기동·재기동·운영 매뉴얼

대상: 심사·시연·개발에서 Pleos Connect 에뮬레이터로 앱을 실행하는 사람. 버전 1.2.0(versionCode 4) 기준 — 위치는 Pleos FusedLocation SDK(안 되면 LocationManager 자동 전환), 음성 명령은 Pleos SpeechToText SDK(ON_DEVICE, 안 되면 버튼만).

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
| 모두 지우고 처음부터 | `adb uninstall app.drivepromise` → 1장 |

명령의 `SVC`는 `app.drivepromise/.DrivePromiseService` 입니다. 터미널에서 먼저 한 번 입력해 두세요.

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
| 부제 | `세워 둠 · 연속 운전 0분 · 실차 · 위치 Pleos · …` (Pleos 위치를 못 쓰면 `위치 GPS(대체)`) |
| 약속 타일 5개 | 초록(지키는 중) 또는 회색(모름 — 신호가 아직 없음) |
| 음성으로 말하기 칸 | ‘다시 · 약속 알려 줘 · 조용히 해 — 쉬기 질문엔 응이나 아니’ + 🎤 말하기 · 질문 뒤 듣기: 켬 (음성 인식이 안 되면 ‘말하기 없음’·‘다시 시도’) |
| 버튼 줄 | 경로 계획 · 주행 끝 · 한/영 · 실차 모드로 / 주행 중 화면 · DO 인정 가정 · 주행 화면 미리보기 / ▶ 시연 주행 4개 · ■ 리플레이 멈춤 |

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
| hospital_home | `--es cmd replay --es name hospital_home` | 약 50초 | 해 지기 전 도착 위험 | 약속 결과 + 수고 인사 |
| rest_two_hours | `... --es name rest_two_hours` | 약 2분 | 두 시간 예고 → “쉬어 갈까요?”(음성 인식이 되면 질문 + 5초 듣기) | 2시간마다 쉬기 놓침 |
| zone_edge | `... --es name zone_edge` | 약 1분 40초 | 구역 끝 → 구역 밖 → 돌아옴 | 약속 구역 놓침 |
| highway_rain | `... --es name highway_rain` | 약 2분 20초 | 고속도로 진입 → 비 시작 → 빗길 과속 | 고속도로·비 놓침 |

주행이 시작되면 주행 화면이 뜹니다(정차 화면이 앞에 있을 때). 주행 화면은 모드가 바뀌면(세움·음성만) 스스로 닫힙니다.

### 3-2. 설정

| 설정 | 화면 | 명령 | 기본 |
|---|---|---|---|
| 언어 | 한/영 | `--es cmd lang --es value en` (또는 `ko`) | 한국어 |
| 주행 중 화면 | 주행 중 화면: 켜기/음성만 | `--es cmd screen --es value voice` (또는 `on`) | 켜기 |
| DO 인정 가정 | DO 인정 가정: 켬/끔 | `--es cmd assume_do --es value 0` (또는 `1`) | 켬 |
| 다시 들려줘 | 주행 화면 버튼 | `--es cmd repeat` | — |
| 말하기(6초 듣기) | 정차·주행 화면 🎤 말하기 | `--es cmd listen` | — |
| 질문 뒤 듣기 | 질문 뒤 듣기: 켬/끔 | `--es cmd voice --es value off` (또는 `on`) | 켬 |

‘DO 인정 가정: 끔’이면 플랫폼이 이 앱의 주행 화면을 인정한다고 확인될 때만 주행 화면을 띄우고, 아니면 음성만 씁니다.

### 3-3. 출발 전 계획

정차 화면 **경로 계획: 판교역** 또는 `--es cmd plan` → 내비 경로(NaviHelper)를 받아 약속(해 지기 전·고속도로)에 비추어 경로와 출발 시한을 ‘출발 전 계획’ 칸에 보여 주고 말합니다. 경로를 받지 못하면 그 사실을 알려 주고 판단하지 않습니다.

### 3-4. 실차 모드(Virtual Console)

`실차 모드로` → Virtual Console에서 기어 D·속도·방향지시등·와이퍼를 바꿉니다. 판단은 리플레이와 같은 코어가 합니다. 와이퍼는 Pleos Vehicle SDK 등록이 에뮬레이터에서 아직 확인되지 않았습니다 — `grep -E "vsdk_reg|vsdk_methods"`로 등록 여부와 함수 모양을 볼 수 있습니다.

| 바꿀 것 | 앱 반응 |
|---|---|
| 기어 D(P가 아닌 기어) | 주행 시작 — 연속 운전 시간 계산(신호 대기도 포함), 주행 화면 |
| 와이퍼 60초 이상 | ‘비 오는 날 조심’ 주의 + 한 문장 |
| 방향지시등 켬 | 그동안 말하지 않음(침묵 구간) |
| 기어 P 15분 이상 | 쉰 것으로 봄 — 운전 시간 0으로, 다시 출발할 때 칭찬 한 번 |

### 3-5. 위치(Pleos FusedLocation)와 약속 구역

| 항목 | 내용 |
|---|---|
| 주 원천 | Pleos FusedLocation SDK 1.1.3 — `isSupported()` → 권한 `pleos.car.permission.FUSED_LOCATION` → `initialize()` → 위치 콜백 등록 + 마지막 위치 |
| 대체 | 지원 안 함 · 권한 없음 · 등록 오류 · 15초 동안 좌표 없음 · 10초 넘게 끊김 → Android LocationManager(1초). 둘 다 돌 때는 3초 안의 Pleos 좌표를 먼저 씀 |
| 약속 구역 판정 | 코어가 거리(하버사인)로 — 구역 끝 300 m 안에서 멀어지면 ‘위험’, 밖이면 ‘못 지킴’, 돌아오면 ‘지키는 중’ |
| 이중 확인 | 같은 구역에 Pleos 근접 알림(ENTER/EXIT)을 걸어 기록·부제 표시(`구역 밖(Pleos 확인)`)에 씀. 알림 3초 뒤 두 판단이 같은지 `zone_xcheck agree=`로, 코어가 바뀐 시각과의 차이를 `lagMs=`(양수 = 코어가 늦음)로 남김(코어 결정은 바꾸지 않음) |
| 기록 | `location`(등록·대체) · `loc_src`(원천 바뀜) · `loc_fix`(좌표, 소수 4자리) · `geofence`(add·registered·transition·removed) · `zone_xcheck` |

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
| 9.0 km | 지키는 중 | `loc_fix src=pleos lat=37.6474` |
| 9.85 km | 위험 | `status … zone:AT_RISK` |
| 10.5 km | 못 지킴 | `zone:MISSED` · `geofence … tr=EXIT` · `zone_xcheck … agree=true` |
| 8.0 km | 지키는 중 | `zone:ON_TRACK` · `tr=ENTER` · 말 “약속 구역으로 돌아왔어요.” |

약속 경고(구역 끝·밖) 문장은 주행 중에만 말하므로 세워 둔 시험에서는 들리지 않는 것이 정상입니다. 끝나면 원래 위치로 되돌려 둡니다(예: `adb emu geo fix 127.0276 37.4979`).

### 3-6. 음성 명령(Pleos SpeechToText)

| 항목 | 내용 |
|---|---|
| 듣는 때 | ① **🎤 말하기**를 누를 때(최대 6초) ② 앱이 두 시간 쉬기 질문(“두 시간째예요. 쉬어 갈까요?”)을 끝낸 직후 5초. 늘 듣지 않음 |
| 듣기 전후 | 짧은 ‘띵’ 뒤에 마이크를 엶 · 우리 말·내비 안내가 나오는 중이면 끝나기를 기다림 · 듣는 동안 앱은 새 말을 시작하지 않음 · 내비 안내가 시작되면 듣기를 멈춤 · 듣는 중에 다시 누르면 그만 |
| 알아듣는 말 | 다시 들려줘 · 약속 알려 줘 · 조용히 해(10분 동안 안내성 말만 미룸) · (쉬기 질문에) 응/아니 — 그 밖의 말은 “잘 못 들었어요” 후 아무것도 안 함 · 질문 뒤 아무 말이 없으면 아무것도 안 함 |
| 인식기 | Pleos SpeechToText 3.3.1 ON_DEVICE(오프라인) · 언어는 앱 언어(ko-KR / en-US) · 실패하면 앱은 버튼·질문 없는 문장으로 동작 |
| 기록 | `stt_init`(준비) · `stt_wait`(말이 끝나기를 기다림) · `stt_listen`(듣기 시작) · `stt_result`(인식 끝 — release 빌드는 들은 글자 대신 글자 수) · `voice_cmd`(뜻) · 기기 안 기록(Ledger)에는 뜻(`VoiceCommand`)만 |

에뮬레이터에서 마이크로 시험하려면 먼저 에뮬레이터 패널 **⋯(Extended Controls) → Microphone → ‘Virtual microphone uses host audio input’**을 켭니다(macOS가 마이크 권한을 물으면 허용). 그다음:

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

시험용 음성 파일 주입(개발 PC 실행 스크립트가 씀): `--es cmd stt_audio_begin --es label <이름>` → `--es cmd stt_audio_chunk --es pcm <base64>`(여러 번) → `--es cmd stt_audio_end --es trigger button`(또는 `question`). PCM은 16 kHz·16 bit·mono이며 앱이 100 ms(3,200 바이트)씩 실시간 속도로 Pleos 인식기에 넣습니다(ON_DEVICE 전용 기능).

### 3-7. 기록과 로그

| 하는 일 | 명령 |
|---|---|
| 실시간 기록(event=… 키=값) | `adb logcat -s DP` |
| 상태 한 줄(event=status_now, 위치 원천 `loc=` 포함) | `adb shell am start-foreground-service -n $SVC --es cmd status` |
| 말한 문장·요약·화면 모드만 | `adb logcat -d -s DP \| grep -E "event=(speak\|summary\|mode)"` |

주행 기록은 앱 내부 저장소 `files/ledger/YYYY-MM.jsonl`에 남고 밖으로 보내지 않습니다. 좌표는 기록 파일에 저장하지 않습니다.

### 3-8. 데이터 지우기

| 하는 일 | 명령 |
|---|---|
| 설정·기록 초기화(권한도 초기화 → 1-2 다시) | `adb shell pm clear app.drivepromise` |
| 앱 삭제 | `adb uninstall app.drivepromise` |

## 4. 문제 해결

| 증상 | 확인 | 조치 |
|---|---|---|
| 소리가 안 남 | `adb logcat -d -s DP \| grep tts_init` → ok=false | `pm grant … TTS_SERVICE` 후 앱 재시작 · Mac 소리·에뮬레이터 오디오 확인 |
| 소리가 내비 안내에 묻힘 | 정상 동작 — 내비가 말하면 0.2초 안에 물러섰다가 1.5초 뒤 다시 말함 | — |
| 주행 화면이 안 뜸 | 정차 화면이 앞에 있었는지 · 부제의 모드 | 정차 화면을 앞에 두고 다시 · ‘주행 중 화면: 켜기’ 확인 · 사이드로드에서는 플랫폼이 가릴 수 있음(음성만이 정상) |
| 차량 신호가 안 들어옴(실차 모드) | Quick Boot로 켰는지 | Cold Boot로 다시 켬 |
| Virtual Console(127.0.0.1:8088)이 안 열림 | `adb forward --list` | `adb forward tcp:8088 tcp:8088` 다시 · 다른 프로그램이 8088을 쓰면 `tcp:18088 tcp:8088` |
| 설치 실패(UPDATE_INCOMPATIBLE) | 이전 빌드 서명이 다름 | `adb uninstall app.drivepromise` 후 설치 |
| 출발 전 계획이 ‘경로를 받지 못함’ | 내비 앱 상태·네트워크 | 내비 앱을 한 번 열고 다시 시도 |
| 부제가 `위치 GPS(대체)` | `adb logcat -d -s DP \| grep event=location` 의 `why=` | `no_permission` → 1-2의 FUSED_LOCATION `pm grant` 후 앱 재시작 · `unsupported` → Pleos 위치 서비스가 없는 이미지(대체로 정상 동작) · `no_pleos_fix_15s` → geo fix를 한 번 넣기 |
| 부제에 `구역 밖/안(Pleos 확인)`이 안 나옴 | `grep event=geofence` | `s=skip` → Pleos 위치가 준비되지 않음(위 줄) · 전이가 없으면 플랫폼이 근접 알림을 보내지 않은 것 — 약속 판정은 코어가 거리로 하므로 동작에는 지장 없음 |
| ‘말하기’가 ‘말하기 없음’·‘다시 시도’ | `grep -E "stt_init\|stt_perm"` | `err=no_sdk` → 음성 인식 SDK가 빠진 빌드(`-PdpStt=false`) · `PERMISSION_DENIED` → 1-2의 STT_SERVICE·RECORD_AUDIO `pm grant` 후 ‘다시 시도’ · `ON_DEVICE_ENGINE_ERROR` → 에뮬레이터 이미지의 음성 엔진 문제(앱은 버튼으로 동작) |
| 말해도 ‘잘 못 들었어요’ | `grep stt_result` 의 `why=`·`rms=` | `timeout_empty`·`rms=0.0` → 에뮬레이터 마이크가 꺼짐(3-6의 Extended Controls) · 글자는 왔는데 뜻이 UNKNOWN → 알아듣는 말(3-6) 중 하나로 |
| 질문 뒤에 듣지 않음 | 정차 화면 ‘질문 뒤 듣기’ · `grep -E "stt_wait\|stt_listen"` | ‘끔’이면 켬 · `why=busy` → 내비 안내가 길게 이어짐(다음 질문에서 다시) |
