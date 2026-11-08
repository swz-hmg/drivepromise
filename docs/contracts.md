# 운전약속 코어 계약 v0.1 (11/5 고정)

DrivePromise core contract v0.1 — frozen on 2026-11-05. 실차(Pleos·AAOS 어댑터)와 리플레이가 같은 계약으로 바꿔 끼워지고, 판단 로직은 모두 `:core`에서 JVM 시험으로 검증합니다.

- 시험: 저장소 맨 위에서 `./gradlew :core:test` (JDK 17~23)
- 출처: 진행계획 v0.2(모듈 구조·인터페이스 초안) · 발화 정책 v0.3 · 늦은알림정책 v0.1(LATE_OK 종류별) · G1-B v0.6 실측(내비에 물러서기) · 침묵구간검증 v0.1(창 값)

## 0. 원칙

| 원칙 | 내용 |
|---|---|
| 순수 Kotlin | `:core`는 Kotlin 표준 라이브러리와 `java.time`만 씁니다. 안드로이드·코루틴·직렬화 라이브러리 없음 → JVM 시험만으로 검증, Android(minSdk 33)에서 그대로 실행 |
| 시간 | 모두 epoch 밀리초(`tMillis`, `...Ms`). 시각을 직접 읽지 않고 입력(World·이벤트)으로 받음 → 결정적 시험 |
| 모르는 값 | `null` 또는 `UNKNOWN`. 에뮬레이터·차종마다 주는 신호가 다르므로 없다고 가정하지 않음 |
| 입력·출력 | 입력은 콜백(`Source`/`Sink`), 출력은 결정(`Step`)과 기록(`LedgerEvent`). 소리·화면·저장은 `:platform`/`:app` |
| 개인정보 | 기록은 기기 안에만(서버 전송 없음). 위치 원본은 Ledger에 넣지 않음 |
| 변경 절차 | 이 문서의 이름·필드·의미를 바꾸려면 PR + 사용자 승인. 기본값이 있는 새 필드 추가는 PR만 |

## 1. 패키지

| 패키지 | 들어 있는 것 | 상태 |
|---|---|---|
| `model` | GeoFix · VehicleSnapshot/VehicleDelta · NaviEvent · Promise · PromiseStatus | v0.1 고정 |
| `source` | Sink · Source · Location/Vehicle/RouteSource · Place · RouteOption · RoutePlan(s) · Clock | v0.1 고정 |
| `speech` | Priority · Validity · World · Utterance · QuietPolicy · RestPolicy · QuietWindows · Step · SpeechScheduler · VoiceArbiter · VoiceLoop · Speaker | v0.1 고정 + 구현·시험 |
| `rest` | RestTracker · Phrases(쉬기 문장 ko/en) | 구현·시험 |
| `ledger` | LedgerEvent · LedgerSink · MemoryLedger · RestCompliance · RestMetrics · **LedgerJson**(JSON Lines 저장 형식, 11/5) | 구현·시험 |
| `replay` | ReplayParser(스키마 0.2) · ReplayTrip · MiniJson · **ReplayPlayer**(가상 시계 재생기, 11/5) | 구현·시험 |
| `engine` | PromiseEngine · EnginePolicy — 약속 5종 상태(타일)와 상태 변화의 말 | v0.1(11/6 작업) 구현·시험 |
| `plan` | SolarCalc(NOAA 일출·일몰·시민 박명) · PreTripPlanner(경로 후보 선택·출발 전 카드 문장) | v0.1(11/6 작업) 구현·시험 |
| `scene` | PromisePhrases(약속 문장 ko/en, 시각 읽기) · **SummaryPhrases**(약속 이름·상태·주행 끝 한 줄) | 구현·시험 |
| `gate` | **QuietGate · GatePolicy** — QuietWindows 구현(침묵구간검증 v0.1 선택값 P5) | v0.1(11/7 작업을 11/5에 당김) 구현·시험 |
| `session` | **TripSession · SessionPolicy** — 상태 기계(PARKED→DRIVING→ENDED)와 부품 배선(앱·리플레이·시험이 같은 코드) · `lastSpoken` · `repeatLast()`(11/5 Q036) | v0.1(11/8 작업을 11/5에 당김) 구현·시험 |
| `ui` | **PresentationMode · Capability · ScreenWhileDriving · ModeResolver** — 화면 모드(정차 화면 / 주행 화면+음성 / 주행 음성만) 결정표·전환 규칙·능력 판단(화면모드전환설계 v0.1, 11/5 Q036 구현) | 구현·시험 |
| `voice` | **VoiceIntent · ListenTrigger · VoiceCommands** — 음성 명령 닫힌 문법(한·영), 모르면 UNKNOWN(11/6 Q040, STT C안) · 대답 문장은 `scene.VoicePhrases` | 구현·시험 |
| `forecast` | ConflictForecaster(경로 앞 고속도로 진입 예고) | 1.3.0: 별도 패키지 대신 `engine.PromiseEngine`의 갈림길 예고(TBT 방면, `highway.ahead`)로 구현 — 경로 전체 ‘검색만’ API는 없어 다음 갈림길 하나만 봄 |

## 2. 모델 (`model`)

| 타입 | 필드 | 비고 |
|---|---|---|
| `GeoFix` | tMillis, lat, lon, speedMps?, bearingDeg?, accuracyM? | 약속 판단의 위치 기준은 FusedLocation/geo fix(내비 엔진 위치는 geo fix를 따르지 않음) |
| `VehicleSnapshot` | tMillis, gear, speedMps?, turnSignal, brakeOn?, steeringDeg?, wiper, headlamp, ignitionOn?, doorsOpen, telltales | `parked` = P 또는 시동 끔 · `driving` = 정차 아님 + 기어를 알거나 움직임 · 신호 대기(D, 속도 0)는 주행 |
| `VehicleDelta` + `merge` | 위와 같은 필드, null = 바뀌지 않음 | 속성별 콜백·리플레이의 부분 변경을 전체 상태로 |
| `NaviEvent` | Tbt(distM, turn, road?) · Caution(kind, distM, raw) · SpeedLimit(kph) · Trip(etaEpochMs, remainM) · Arrived · RouteError(cause) | 모두 tMillis. 회전 문장은 만들지 않음(순정 내비 몫). RouteError는 G1-B v0.7의 CAUSE_SEARCH_ROUTE에서 추가 |
| `Promise` | HomeBeforeDark(marginMin) · AvoidHighway · RainCare · RestEvery(everyMin) · StayInZone(lat, lon, radiusM) | 설정 값만. 판정은 engine(11/6) |
| `PromiseStatus` | ON_TRACK · AT_RISK · KEPT · MISSED · NOT_APPLICABLE · UNKNOWN | 정차 화면 타일 |

## 3. 입력 원천 (`source`)

```kotlin
fun interface Sink<in T> { fun accept(value: T) }
interface Source<out T> { fun start(sink: Sink<T>): AutoCloseable }
interface LocationSource : Source<GeoFix>                 // 앱: PleosFusedLocationSource(주, Pleos FusedLocation 1.1.3) → 안 되면 PleosLocationSource(LocationManager)
interface VehicleSource : Source<VehicleSnapshot>          // 속성별 변경을 합친 전체 상태
interface RouteSource : Source<NaviEvent> {
    fun planRoutes(dest: Place, options: List<RouteOption>, done: Sink<RoutePlans>): AutoCloseable   // 결과(실패 포함) 한 번
}
fun interface Clock { fun nowMs(): Long }
```

**위치 원천 구현(앱 1.1.0)** — 코어 계약은 그대로이고 앱 쪽 어댑터만 바뀜.

| 규칙 | 내용 |
|---|---|
| 켜는 순서 | `isSupported()` → 권한 `pleos.car.permission.FUSED_LOCATION` → `initialize()`(한 번) → `registerFusedLocationCallback` + `getLastKnownLocation` (세션마다 등록·해제, 서비스 끝에 `release()`) |
| 대체 | 지원 안 함 · 권한 없음 · 등록 오류 · SDK `onError`(첫 좌표 전) · 첫 좌표 15초 없음 · 10초 넘게 끊김 → LocationManager(1초)를 함께 켬 |
| 우선 | 둘 다 돌 때 최근 3초 안에 Pleos 좌표가 있으면 대체 좌표는 버림(두 원천이 번갈아 들어와 ‘멀어지는 중’ 판단이 흔들리지 않게) |
| 변환 | `LocationData(latitude, longitude, heading, confidence, …)` → `GeoFix(tMillis=수신 시각, lat, lon, speedMps=null, bearingDeg=heading(0~360일 때), accuracyM=null)` — 속도는 차량 신호, confidence는 거리 단위가 아니라 기록만 |
| 스레드 | SDK 콜백은 모두 메인 Handler로 넘겨 코어(단일 스레드)에 넣음 · 세션 번호(gen)로 닫힌 세션의 늦은 좌표를 버림 |
| 약속 구역 이중 확인 | 판정은 코어(거리). 같은 구역에 Pleos 근접 알림(ENTER/EXIT)을 걸고 전이를 `geofence`·`zone_xcheck(agree)`로 기록·부제에 표시 — 코어 결정은 바꾸지 않음 |
| 기록 | 좌표는 logcat에만 소수 4자리(약 11 m), Ledger에는 넣지 않음(1장 개인정보 원칙 유지) |

`RoutePlans(plans, error)` — 실패면 plans가 비고 error에 원인(예: `CAUSE_SEARCH_ROUTE`).

## 4. 발화 (`speech`)

### 4.1 말 하나 — `Utterance`

| 필드 | 뜻 | 규칙 |
|---|---|---|
| key | 종류 이름(예: `rest.main`) | cancel은 접두사로 |
| priority | SAFETY > PROMISE > INFO | |
| estMs / estShortMs | 예상 길이 / 물러선 뒤 짧은 문장 길이 | 1 ms~4초, 짧은 ≤ 원래 |
| render(SayCtx) | 문장을 말하기 직전에 지금 값으로 | SayCtx = world · repeat · short |
| deadlineAtMs | 마감(상태 지속형은 목표 시각 = 에스컬레이션 시작) | PROMISE 필수 |
| validity | WHILE_STATE · UNTIL_DEADLINE | PROMISE 필수 |
| stillValid(World) | 말하기 직전 재판정 | false → 마감형: 요약(EXPIRED) + followup / 상태형: STATE_ENDED |
| followup(World) | 마감형이 지났을 때 앞을 보는 새 말 | |
| repeatAfterMs / maxRepeats | 끝까지 들려준 뒤 반복 | 반복하려면 간격 필수 |
| speakWhen(World) | 말해도 되는 상태 | false면 버리지 않고 미룸(쉬기 말은 주행 중에만) |

### 4.2 정책 값 — `QuietPolicy` (발화 정책 v0.4)

| 값 | 기본 | 뜻 |
|---|---|---|
| infoMaxWaitMs / infoOverflow | 60 000 / REST_DIGEST | 정보: 60초 안에 못 하면 정차 후 요약 |
| promiseEscalateWithinMs | 60 000 | 약속: 마감 60초 전부터 신호 대기 창도 씀 |
| lateOkUntilDeadlineMs | 0 | 지점·시각 마감형: 지난 말은 하지 않음 |
| keepWhileStateWhileDriving | true | 상태 지속형: 주행 중 버리지 않음(false = G2 대안) |
| maxYieldsBeforeStopWindow | 2 | 두 번 끊긴 말은 신호 대기 창·확실한 틈에서만 |
| longGapExtraMs | 4 000 | 확실한 틈 = 말 길이 + 4초 |
| maxFailures | 3 | TTS 오류 3번이면 요약 |
| msPerCharKo / msPerCharEn / speechPadMs | 165 / 75 / 300 | 1.3.0: 말 길이 = max(선언 길이, 글자 수 × 글자당 시간 + 0.3초) — 11/6 에뮬레이터 실측(한국어 49자 ≈ 8.1초) |
| failRetryMs | 2 000 | 1.3.0: TTS 오류 뒤 2초 기다렸다 다시(바로 재시도해 3번을 한꺼번에 쓰는 일 방지) |
| watchdogFactor / watchdogMinMs | 2.5 / 15 000 | 1.3.0: 재생 끝 알림이 이 시간(실제 시계) 안에 오지 않으면 말을 멈추고 실패로 셈(`VoiceLoop.onWatchdog`) |

`RestPolicy`: targetMin 120 · leadMin 10 · headsUpCutoffMin 2 · repeatMin 15 · maxRepeats 2 · restResetMin 15 · arrivalSoonMin 15 · estMs 3 500 · estShortMs 2 000 · restoreAssumeRestMin 60(1.3.0). 쉬기 간격을 바꾸면(`TripSession.restPolicyFor`) 예고 = min(10분, 간격/3), 최소 cutoff+1분.

### 4.3 말해도 되는 때 — `QuietWindows` (구현: `gate.QuietGate`, 11/5)

```kotlin
interface QuietWindows {
    fun normal(nowMs: Long, needMs: Long): Boolean     // 회전 8초 전(좌회전 10초)~회전 뒤 8초 침묵, 예측 안내(300·100 m 앞 2.5초), 방향지시등 중 침묵
    fun stopWindow(nowMs: Long): Boolean               // 정지선 대기 시작 2초 안(P6) — 1.3.0: 방향지시등 중·급제동 직후는 아님
    fun longGap(nowMs: Long, needMs: Long): Boolean    // 확실한 틈
}
```

### 4.4 스케줄러 — `SpeechScheduler`

API: `submit(u, now)` · `cancel(keyPrefix, now, reason)` · `tick(world, voiceFree, realNowMs): Step` (10 Hz) · `spoken(id, now)` · `yielded(id, now)` · `failed(id, now, cause)` · `watchdog(realNowMs): Long?`(1.3.0) · `needMs(declared, text, lang)`(1.3.0) · `endTrip(now)` · `digest` · `pendingKeys`.

```text
tick(w, voiceFree):
    for p in queue: housekeep(p, w)          # 말할 수 없어도 매번
        PROMISE ∧ (¬stillValid ∨ (UNTIL_DEADLINE ∧ now > deadline + lateOk)) → 제거
            UNTIL_DEADLINE: 요약 EXPIRED + followup / WHILE_STATE: 요약 STATE_ENDED
        INFO ∧ now − created > 60 s → 요약(INFO_TIMEOUT)
    if speaking ∨ ¬voiceFree: return Idle
    for p in order(SAFETY > PROMISE(마감 순) > INFO(먼저 온 순)):
        skip if p.notBefore > now ∨ ¬speakWhen(w)
        text ← render(world, repeat, short)                     # 1.3.0: 먼저 그려서
        need ← max((p.yields > 0) ? estShort : est, len(text) × msPerChar + pad)   # 글자 수로 다시 잼
        ok ← SAFETY
             ∨ (yields ≥ 2 ? stopWindow ∨ longGap(need + 4 s)
                : normal(need) ∨ (PROMISE ∧ now ≥ deadline − 60 s ∧ stopWindow))
        if ok: return Speak(id, key, text, …, estMs = need)   # startedReal ← realNowMs (감시 타이머)
    return Idle
spoken(id): 기록 Spoken · 반복이 남았으면 now + repeatAfter 뒤로 다시 넣음
yielded(id): yields + 1, 다시 대기(다음엔 짧은 문장)
failed(id): failures + 1, notBefore ← now + 2 s (3번이면 요약)
watchdog(realNow): 말하는 중 ∧ realNow − startedReal > max(15 s, need × 2.5) → 그 id
```

### 4.5 내비에 물러서기 — `VoiceArbiter` (G1-B v0.6 실측)

| 규칙 | 값 | 근거 |
|---|---|---|
| 재생 용도 | NAVIGATION_GUIDANCE (2순위 NOTIFICATION, v0.7 확인 중) | ASSISTANT는 내비 포커스를 막아 안내 누락(REQUEST_FAILED) |
| 감지 | 재생 목록의 내비 재생기 수(첫 효과음 = 내비 말 요청 −62 ms) → 포커스 LOSS_TRANSIENT(+68 ms) | 빠른 쪽 먼저 |
| 멈춤 | `Yield(stopWithinMs = 250)` | 실측 겹침 217 ms, 목표 ≤ 300 ms |
| 다시 말하기 | 내비 재생기가 **모두** 끝나고 1.5초 + GAIN(오지 않으면 3초 더) | 안내 1번 = 재생기 2개(효과음 ≈1.05초 + 말 ≈2.1초) — 첫 재생기만 보면 너무 일찍(v0.6 결함) |
| 다시 말할 문장 | 처음부터 짧게, 두 번 끊기면 신호 대기 창·확실한 틈에서만 | 스케줄러 |

배선(앱 서비스와 시험이 같은 `VoiceLoop`): 10 Hz `tick(world)` · 재생 목록 콜백 → `onNavPlayers(now, 우리 앱이 아닌 내비 재생기 수)` · 포커스 변화 → `onFocus` · TTS 끝/오류 → `onDone`/`onError`. `Speaker.speak(id, text, usage)` · `Speaker.stop(id)`는 플랫폼(Pleos TextToSpeech ON_DEVICE).

## 5. 2시간마다 쉬기 — `RestTracker`

| 시점 | 말 | 종류 |
|---|---|---|
| 1:50 | “10분쯤 뒤면 두 시간이에요. 다음 쉴 곳에서 쉬어 가요.” | PROMISE · UNTIL_DEADLINE(1:58까지) · 주행 중에만 |
| 2:00 | “두 시간째예요. 약속대로 쉬어 가요.” (늦으면 그때 값: “두 시간 3분째예요…”) · 음성 인식을 쓸 수 있으면(`ask`, 1.2.0) 첫 말만 “두 시간째예요. 쉬어 갈까요?” — 끝나면 5초 듣기(8.8). 듣기 차례는 실제로 ‘?’로 끝난 문장에만(`Phrases.isQuestion`, 1.2.1) | PROMISE · WHILE_STATE · 주행 중에만 |
| 말한 뒤 +15·+30분 | “쉬지 않고 두 시간 15분째예요. 가까운 곳에 잠깐 세워요.” | 반복 최대 2번 |
| 도착 15분 안 | “곧 도착이에요. 도착하면 꼭 쉬어요.” — 질문이 아니므로 듣지 않음(1.2.1, S81) | 설계 후보(G2 수용성) |
| 쉼 뒤 출발 | “잘 쉬셨어요. 약속 지키셨습니다.” | INFO, 예고·본 알림이 있었던 구간만 |
| 쉬지 못하고 도착 | “오늘은 두 시간 40분을 쉬지 않고 운전하셨어요. 다음엔 한 시간 50분쯤 쉬어 가요.” | `endTrip()`이 돌려줌(요약 담당이 말함) |
| 두 시간을 넘겼지만 알림 뒤 쉼(1.3.0) | “오늘은 두 시간을 넘겨 운전했지만, 알림 뒤 쉬셨어요.” — 판정은 Q027대로 ‘지킴’(알림 뒤 P 15분), 넘긴 사실은 따로 말함 | `endTrip()` · `overTargetEver` |

쉼 = 기어 P 또는 시동 끔 15분 이상(앱이 꺼져 있던 시간 포함, `state()`/`restore()`). 1.3.0: 앱이 꺼진 시간은 저장 때 세워져 있었거나(`parkedAtSave`) 60분 이상일 때만 쉼으로 셈 — 주행 중 앱이 죽었다 살아난 15분을 쉼으로 세지 않음. 쉬기 약속을 끄면(`enabled=false`) 말·판정 모두 하지 않음. 신호 대기는 운전 시간에 넣음. 시는 고유어(두 시간)·분은 숫자(5분)로 써서 TTS가 바르게 읽게.

## 6. 기록과 지표 — `ledger`

| 이벤트 | 뜻 |
|---|---|
| Due(key, drivingMs) | 약속 알림 시각이 됨(노출) |
| Spoken(key, repeat, yields, endMs, lateMs) | 끝까지 들려줌(tMillis = 시작) |
| Yielded(key, count) · Dropped(key, reason) · Digested(key, reason) | 물러섬 · 버림(expired·state_ended·rested·cancel·tts_failed) · 정차 후 요약 |
| VoiceCommand(trigger, intent, question) | 음성 명령의 뜻만(원문 없음) — trigger BUTTON·QUESTION, question은 대답한 질문 key(예: `rest.main`) 또는 빈칸(11/6) |
| RestTaken(restStartMs, segmentDrivingMs) · TripEnded(drivingMs) | 쉼 인정 · 주행 끝 |
| StatusChanged(promiseId, from, to, note) | 약속 상태 변화(PromiseEngine) — 타일·요약·순응 계산 |

`RestCompliance.compute(events, windowMin = 20)` → 운전 구간(쉼과 쉼 사이)마다 셈:

| 지표 | 정의 | 지표 사다리 |
|---|---|---|
| deliveryRate | 본 알림 시각에 닿은 구간 중 본 알림을 주행 중 끝까지 들려준 비율 | L0 전달 |
| complianceRate | 본 알림을 들려준 구간 중 들려준 뒤 20분 안에 쉼을 시작한 비율 | L4 순응(주 지표) |
| keptRate | 본 알림 시각 뒤 20분 안에 쉼을 시작한 비율(들었는지와 무관) | 약속 지킴 |
| keptOnHeadsUp | 예고 뒤 본 알림 전에 쉰 구간 수 | 예고 효과 |
| medianDeliveryLateMs | 본 알림 시각 → 들려준 시각 중앙값 | 전달 지연 |

한계: 쉼은 P 15분 이상으로만 판단(주유·볼일과 구분 못 함). 분모가 0이면 비율은 null(0이 아님).

**‘쉼’ 정의(결정, 11/4 Q032)**: 순응률의 ‘쉼’은 **P(또는 시동 끔) 15분 이상 정차** 그대로 둡니다 — 쉬기 약속의 목적은 ‘운전에서 15분 떨어지기’이고(운수 규정의 휴식도 장소를 묻지 않음), 주유·볼일 정차도 운전에서 떨어진 시간입니다. 휴게소·졸음쉼터로 좁히면 집 도착·주차장·카페 정차가 빠지고 장소 데이터가 필요해 더 틀립니다. 문서에는 ‘15분 이상 정차’로 이름을 정직하게 씁니다.

## 7. 리플레이 스키마 0.2 — `replay`

```json
{
  "meta": {"name": "병원→집", "schema": "drivepromise.trip/0.2", "speedup": 3.0, "simulated": true,
           "origin": [37.5665, 126.9780], "start": "2026-11-14T16:50:00+09:00"},
  "location": [{"t": 0, "lat": 37.5665, "lon": 126.978, "speedKph": 0}],
  "navi": [{"t": 0, "type": "trip", "etaLocal": "17:28", "remainM": 8200},
           {"t": 12, "type": "tbt", "turn": "LEFT", "distM": 700},
           {"t": 40, "type": "caution", "kind": "SCHOOL_ZONE", "distM": 200},
           {"t": 150, "type": "arrived"}],
  "vehicle": [{"t": 0, "gear": "D", "speedKph": 0}, {"t": 60, "turnSignal": "LEFT"}]
}
```

| 항목 | 규칙 |
|---|---|
| t | 트립 시작부터 초(소수 가능, 0 이상). 시각 = 기준 + t, 기준 = `parse(json, baseEpochMs)` 또는 meta.start 또는 0 |
| meta.schema | `drivepromise.trip/0.2` (계획서 표기 `0.2`도 받음) |
| navi.type | trip(etaSec 또는 etaLocal — etaLocal은 meta.start 필요) · tbt · caution · speedLimit · arrived · routeError |
| vehicle | 부분 변경: gear(P·R·N·D) · speedKph/speedMps · turnSignal · wiper · headlamp · brake · ignition · steeringDeg · doorsOpen |
| 오류 | 모르는 값은 위치와 함께 거절(예: `리플레이 navi[3].type: 알 수 없는 값`) |

## 8. 시험 (`./gradlew :core:test`)

스펙 97개(S01~S41 `Specs.kt`, S42~S56·S76 `EngineSpecs.kt`, S57~S75·S77~S83 `SessionSpecs.kt`, S84~S97 `ReviewSpecs.kt` — 1.3.0 외부 검토 반영) — 같은 목록을 JUnit 5(`CoreSpecsTest`, 동적 시험)와 의존성 없는 점검 러너가 돌립니다. S72는 `demo/trips/*.json`(시연 트립 4개, 앱 assets와 같은 파일)을 읽습니다.

| 묶음 | 스펙 |
|---|---|
| 모델·계약 | S01~S02 |
| 스케줄러(순서·창·LATE_OK·반복·실패·요약·그때 값) | S03~S17 |
| 물러서기(실측 타이밍 회귀 3715 ms·v0.6 결함·GAIN 없음·포커스만·새 포커스 잃음·통합) | S18~S24 |
| 2시간 쉬기(전 주기·그때 값·쉼 인정·짧은 정차·예고 마감·도착 임박·이어 가기·신호 대기) | S25~S33 |
| 지표(정의·엔드투엔드) | S34~S35 |
| 리플레이·JSON·문장(ko/en) | S36~S41 |
| 일몰 계산(서울·울산 7날짜 ±60초 · 극지방) | S42~S43 |
| 약속 엔진(해 지기 전·고속도로·비·쉬기 상태·구역) | S44~S51 |
| 리플레이 통합(계획서 예시 트립) · 출발 전 계획 · 시각 읽기 | S52~S56 |
| 게이트(회전 앞·뒤·좌회전·끝낼 시간·내비 피하기·방향지시등·급제동·회전 통과·신호 대기 창·안전 즉시) | S57~S63 |
| 세션(상태 기계·끝 조건·리플레이 통합·출발 전 계획·이어 가기·물러서기 배선) · 재생기 · 기록 JSON | S64~S71 |
| 시연 트립 4개(병원→집 · 두 시간 쉬기 · 약속 구역 경계 · 고속도로·비) — 기대 발화 열·판정·요약 문장 | S72 |
| 화면 모드(결정표 7경우 · 전환 규칙 히스테리시스 · 능력 판단과 차단 기억) · ‘다시 들려줘’(1회·연타 1회·침묵 구간 대기) | S73~S75 |
| 약속 구역 경계 히스테리시스 | S76 |
| 음성 명령: 뜻 풀이(한·영 표·조사) · 질문 뒤 듣기(질문형 문장·듣기 요청 1회·응/아니·‘다시’ 뒤 한 번 더) · 버튼(상태·조용히·도움말) · 듣는 동안 말 보류 | S77~S80 |
| 질문일 때만 듣기(‘곧 도착’ 문장 뒤엔 듣지 않음) · 주행 끝 순간 말하던 문장은 반복 예약 없음 · 요약까지 말해야 한가함(`pendingAfterEnd`) | S81 |
| 리플레이 멈춤(듣는 동안 가상 시계 정지)·주행 끝 뒤 실시간 · 시연 트립 ‘두 시간 쉬기’를 STT 켜고 60배속으로: 질문 → 5초 멈춤 → ‘응’ → 대답 → 도착 → 요약 2줄 | S82 |
| ‘약속 알려 줘’ 대답: 판단 전 약속은 ‘모두’에 넣지 않음 | S83 |
| 1.3.0 검토 반영: 일몰 뒤 도착 후 밤 문장 없음(C01) · 일몰 문장(C02) · 신호 없음 UNKNOWN·위치 TTL·요약(C03) · 신호 대기 창과 방향지시등·급제동(C04) · 글자 수 길이(C05) · 다음 주행 준비(C06) · 조사 문법(C08) · 구역 GPS 흔들림 0/50·실제 접근 1(C09) · 상태 대답 꼬리(C10) · 경로 취소 뒤 값 비움(C11) · 쉬기 설정 90분 → 80/90(C12) · 넘김+쉼·복원 규칙(C13) · 감시 타이머 3번(C14) · 고속도로 갈림길 예고(ADV-01) | S84~S97 |

## 8.5 약속 엔진 (`engine` · `plan` · `scene`, 11/6 작업)

```kotlin
class PromiseEngine(promises: List<Promise>, scheduler: SpeechScheduler, ledger: LedgerSink, policy: EnginePolicy)
    fun onNavi(e: NaviEvent)                 // Trip(ETA)·SpeedLimit·Tbt(road)·Arrived·RouteError
    fun tick(w: World)                        // 10 Hz — 상태 판정 + 상태가 바뀔 때 말을 스케줄러에
    fun endTrip(w: World): Map<String, PromiseStatus>   // 지킴/못 지킴으로 닫음
    val statuses: Map<String, PromiseStatus>  // 정차 화면 타일
object SolarCalc { fun at(epochMs, lat, lon, zone): SunTimes; fun forDate(date, lat, lon, zone): SunTimes; fun untilSunsetMs(…) }
object PreTripPlanner { fun plan(Input(nowMs, originLat, originLon, plans, promises, zone, lang, departAtMs)): Plan }
```

| 약속 | 상태 규칙 | 말(한 번, 상태가 바뀔 때) |
|---|---|---|
| 해 지기 전 귀가(margin 30) | 도착 예정 ≤ 일몰−30분 ON_TRACK · 넘으면 AT_RISK(되돌릴 땐 5분 여유) · 해 진 뒤 주행 MISSED · 해 지기 전 도착 KEPT | AT_RISK: “이대로면 5시 28분 도착, 해는 5시 22분에 져요.”(1.3.0: ‘쉬지 말고 바로 가요’ 삭제 — 쉬기 약속과 충돌 · 짧은 문장은 도착이 일몰 뒤일 때만 “해 진 뒤 도착이에요.”, 아니면 “해 지기 직전 도착이에요.”) (UNTIL_DEADLINE=일몰, 지나면 밤 문장 — 1.3.0: 도착 전일 때만) · 해 진 뒤: “해가 졌어요. 약속보다 늦었지만, 전조등 켜고 천천히 가요.” |
| 고속도로 대신 | 제한속도 ≥ 100 또는 도로 이름(고속도로·고속국도·Expressway)이면 MISSED(그 주행 내내) · 도로 신호를 한 번도 못 받으면 UNKNOWN(1.3.0) | 들어서기 전(1.3.0, ADV-01): TBT가 EXIT·MERGE이고 150~1 500 m 앞, 방면(`toward`)에 IC·JC·고속도로·톨게이트·나들목이 있으면 방면마다 한 번 “앞 갈림길은 판교IC 방면이에요. 약속대로 일반도로로 가요.”(`highway.ahead`) · 들어서면: “고속도로에 들어섰어요. 약속과 달라요. 다음 나들목에서 내려갈지 정해요.” |
| 비 오는 날 | 와이퍼 60초 이상 → 비 · 비 중 제한속도의 80%(젖은 노면 20% 감속) 넘으면 AT_RISK · 와이퍼 5분 멈추면 끝 · 와이퍼 신호를 한 번도 못 받으면 UNKNOWN(1.3.0) | 비 시작: “비가 와요. 약속대로 평소보다 천천히…” · 30초 넘게 빠르면: “빗길에 70예요. 48 아래로 줄여요.” |
| 2시간 쉬기 | 운전 ≥ 110분 AT_RISK · ≥ 120분 MISSED(말은 RestTracker) | — |
| 약속 구역(중심·반경) | 밖 MISSED(나갈 때는 경계, 돌아올 때는 경계 안쪽 100 m — 히스테리시스) · 경계 300 m 안쪽에서 바깥으로 움직이면 AT_RISK(1.3.0: 움직이는 중 ∧ 3초 이상 기록 ∧ 10초 동안 15 m 넘게 멀어짐 — GPS 흔들림 오경보 방지) · 안 ON_TRACK · 위치가 5분 끊기면 UNKNOWN(1.3.0) | 예고(UNTIL_DEADLINE 60초): “약속 구역 끝이 가까워요…” · 밖: “약속 구역 밖이에요. 안전한 곳에서 돌아가요.” · 돌아오면(INFO): “약속 구역으로 돌아왔어요.” |

EnginePolicy 기본값: rainOnMs 60 000 · rainOffMs 300 000 · rainSpeedFactor 0.8 · rainDefaultLimitKph 60 · rainFastHoldMs 30 000 · highwaySpeedLimitKph 100 · zoneEdgeM 300 · zoneBackM 100 · darkHysteresisMs 300 000 · zone Asia/Seoul · (1.3.0) fixTtlMs 300 000 · zoneEdgeWindowMs 10 000 · zoneEdgeMoveM 15 · highwayAhead true · highwayAheadMinM 150 · highwayAheadMaxM 1 500. 경로 취소(RouteError)·도착은 제한속도·도로 이름을 비움(다음 경로에 옛 값이 남지 않게, 1.3.0). 주행 끝 요약은 UNKNOWN 약속을 ‘지킴’에 넣지 않고 “…은 판단할 신호가 없었어요.”로 따로 말함(1.3.0). 약속 경고(PROMISE)는 `speakWhen = 주행 중`(세워 둔 동안 미룸) — ‘돌아왔어요’(INFO)는 세워 둔 동안에도 말함.

PreTripPlanner 선택 규칙: 위반 0인 후보 중 가장 빠른 것 → 없으면 해 지기 전에 도착하는 것 → 없으면 가장 빠른 것. 문장: 선택 경로·소요 시간 / 해 지기 전(정상·직전·늦음 — 늦으면 출발 시한 또는 ‘다른 날·동승’) / 고속도로 유무 / 2시간 넘으면 쉬기 안내. 경로가 없으면 오류 문장.

## 8.6 게이트 · 세션 · 재생기 (`gate` · `session` · `replay.ReplayPlayer`, 11/5 작업)

```kotlin
class QuietGate(policy: GatePolicy = GatePolicy()) : QuietWindows
    fun update(now: Long, v: VehicleSnapshot?)   // 10 Hz — 속도·방향지시등·정차·급제동
    fun onNavi(e: NaviEvent)                     // Tbt(거리·회전 종류) · Arrived/RouteError(경로 없음)
    fun quiet(now: Long): Boolean                // 지금 침묵인가(회전 앞·뒤·방향지시등·급제동)
    val timeToTurnMs: Long?                      // 다가오는 회전까지(없거나 지났으면 null)
class TripSession(promises, speaker: Speaker, clock: Clock, lang, policy: SessionPolicy, externalLedger: LedgerSink, listener: Listener)
    fun attach(location?, vehicle?, route?) · fun attachReplay(ReplayPlayer) · fun close()
    fun onFix/onVehicle/onNavi · fun onNavPlayers/onFocus/onSpeechDone/onSpeechError   // 플랫폼 → 세션
    fun tick(): Step · fun pumpReplay(ReplayPlayer): Step                            // 10 Hz
    fun end(reason): Summary · fun plan(dest, route, departAtMs?): AutoCloseable
    fun restState()/restoreRest(State) · val phase · statuses · drivingMs · plan · summary
class ReplayPlayer(trip: ReplayTrip, real: Clock, speedup = trip.meta.speedup)   // clock(가상) · location/vehicle/route(원천) · pump(virtualNow) · finished
    fun hold(on: Boolean) · val held · val heldMs · fun realTimeAfterEnd()   // 1.2.1: 듣는 동안 멈춤(기울기 0) · 주행 끝 뒤 실시간(기울기 1)
object LedgerJson { fun encode(LedgerEvent): String; fun decode(line): LedgerEvent?; fun decodeAll(text): List<LedgerEvent> }
```

| 게이트 값(GatePolicy) | 기본 | 근거 |
|---|---|---|
| preMs · preLeftMs · postMs | 8 000 · 10 000 · 8 000 | 침묵구간검증 v0.1 선택값 P5(문헌 범위 + 시뮬레이션 강건성) |
| minSpeedMps | 5.0 | 남은 시간 = 거리 ÷ max(속도, 5 m/s) — 정지·저속에서 회전이 ‘곧’이라고 보지 않게 |
| turnReachedM · tbtAdvanceM | 20 · 60 | 회전 통과 판단: TBT 거리 ≤ 20 m 또는 새 TBT가 추정보다 60 m 이상 멀어짐 |
| navPromptAtM · navPromptMs · navGuardPadMs | [300, 100] · 2 500 · 500 | 순정 내비 안내 지점·길이(가정 — G1-B에서 측정해 교체) |
| stopWindowMs | 2 000 | 신호 대기 시작 2초(스케줄러가 마감 임박·두 번 끊긴 말에만 씀) |
| hardBrakeMps2 · hardBrakeHoldMs | 3.0 · 2 000 | 급제동(0.3~1.5초 전 표본과 비교) 뒤 침묵 |
| tbtStaleMs | 60 000 | TBT 소식이 끊기면 회전 예측을 접음 |

세션 상태 기계: `PARKED`(세워 둠·모름) → 차가 움직이면(기어 R·N·D 또는 속도 > 0.5 m/s) `DRIVING` → `ENDED`. 끝 조건: 도착 소식 뒤 정차(P·시동 끔) · 시동 끔 · 도착 뒤 5분 · 도착 없이 30분 정차 · 수동. 끝나면 약속 판정을 닫고(engine.endTrip) 요약 문장(쉬기 요약 · “약속 n개 중 m개를 지켰어요…”)을 세워 둔 동안 INFO로 말합니다. 반복 예약(이미 한 말의 15분 뒤 반복)은 못 전한 말로 세지 않고, 주행 끝 순간 말하던 문장이 끝나도 새 반복을 걸지 않습니다(`SpeechScheduler.tripEnded`, 1.2.1).

리플레이 가상 시계(1.2.1): 구간마다 기울기가 다른 직선 — 주행 중 = 배속, 듣는 동안(`TripSession.listening`) = 0, 주행이 끝난 뒤 = 1. `pumpReplay`가 `hold(listening)`·`realTimeAfterEnd()`를 부릅니다. 까닭(11/6 실행): 60배속에서 5초 듣기는 가상 5분이라 질문 뒤 장면이 사라지고, 요약 첫 줄을 말하는 4초가 가상 4분이 되어 둘째 줄이 늦은 알림 규칙(INFO 60초)에 걸려 사라짐(S82가 옛 코드에서 실패로 확인). `TripSession.pendingAfterEnd` = 끝났는데 말하는 중·듣는 중이거나 요약·대답·다시·계획이 남음 — 앱은 이것이 false가 된 뒤(최소 4초·최대 45초) 실차 세션으로 돌아갑니다.

## 8.7 화면 모드 (`ui`, 11/5 Q036 — DO 인정 가정)

```kotlin
enum class PresentationMode { PARKED, DRIVE_DO, DRIVE_VOICE }   // 정차 화면 · 주행 화면+음성 · 주행 음성만
enum class Capability { YES, NO, UNKNOWN }                       // 플랫폼이 주행 중 화면을 허용하는가
enum class ScreenWhileDriving { ON, VOICE_ONLY }                 // 사용자 설정(기본 ON)
object ModeResolver {
    fun resolve(driving: Boolean, cap: Capability, user: ScreenWhileDriving): PresentationMode
        // !driving → PARKED · (cap YES ∧ user ON) → DRIVE_DO · 그 밖(NO·UNKNOWN·VOICE_ONLY) → DRIVE_VOICE
    fun next(current: PresentationMode, resolved: PresentationMode, driving: Boolean): PresentationMode
        // 내려가기(DRIVE_DO→DRIVE_VOICE)는 즉시 · 올라가기(DRIVE_VOICE→DRIVE_DO)는 세워 둔 동안(PARKED 에서 출발)만 · PARKED ↔ 주행은 언제나
    fun capability(platformOptimized: Boolean?, assumeDo: Boolean, blockedSeen: Boolean): Capability
        // 차단을 봤으면 NO > (플랫폼 YES ∨ 가정) → YES > 플랫폼 NO → NO > UNKNOWN
}
class TripSession { val lastSpoken: Step.Speak?; val lastSpokenAtMs: Long?; fun repeatLast(): Boolean /* INFO ‘repeat’, 게이트·물러서기 적용, 연타는 앞 요청 취소 */ }
```

앱 배선(계약 밖, `app/`): `ScreenModeController`(입력: 세션 단계·`CarPackageManager.isActivityDistractionOptimized`·DO 인정 가정 설정·차단 감지·사용자 설정·디버그 강제) → `mode` · `launchSeq`. 정차 화면(`MainActivity`, DO 선언 없음)이 `launchSeq` 가 늘 때 주행 화면(`DriveActivity`, `distractionOptimized=true`)을 한 번 띄우고, 주행 화면은 모드가 `DRIVE_DO` 가 아니면 스스로 닫힘. 주행 화면이 UX 제한(`requiresDistractionOptimization`) 중에 가려지면(onStop) 차단으로 보고 서비스 수명 동안 음성만. 주행 화면 내용: 약속 5개 상태(색) · 마지막 문장(60초, 플랫폼 허용 길이 `maxRestrictedStringLength` 안) · 연속 운전 시간 · 큰 버튼 둘(‘다시 들려줘’ → `repeatLast` · 1.2.0부터 ‘🎤 말하기’ → 최대 6초 듣기, 8.8). 스크롤·목록·키보드·설정 없음.

## 8.8 음성 명령 (`voice` · `session`, 11/6 Q040 — STT C안: A 버튼 + B 물을 때만)

```kotlin
enum class VoiceIntent { REPEAT, STATUS, QUIET, YES, NO, UNKNOWN }
enum class ListenTrigger { BUTTON, QUESTION }   // A ‘말하기’를 누름 · B 앱이 물은 말이 끝남
object VoiceCommands { fun normalize(s: String): String; fun match(text: String, lang: Lang): VoiceIntent }
class TripSession {
    var voiceInput: Boolean        // 앱이 들을 수 있음(STT 준비 ∧ ‘질문 뒤 듣기’ 켬) → 2시간 본 알림 첫 말이 질문형
    var listening: Boolean         // 듣는 중·듣기 직전 → 새 말을 시작하지 않음(큐 정리는 계속)
    fun takeListenRequest(): ListenTrigger?   // 질문을 끝까지 들려줬을 때 한 번만 QUESTION
    fun onVoice(text: String, trigger: ListenTrigger): VoiceIntent   // 뜻 → 행동·대답(INFO ‘voice.*’) + Ledger VoiceCommand
    val lastIntent: VoiceIntent?
}
class SpeechScheduler { var muteInfoUntilMs: Long }   // ‘조용히’ — INFO만 미룸(repeat·voice.* 제외), 약속 경고는 그대로
```

| 규칙 | 값 / 동작 |
|---|---|
| 문법 우선순위 | QUIET > REPEAT > STATUS > NO > YES. 설정 언어 문법을 먼저, 다른 언어도 봄(섞어 말해도). 한 음절 대답(응·네·예)은 낱말일 때만(‘네비’에 안 걸림) · ‘약속’만 말하면 STATUS, ‘약속대로 쉴게’는 YES |
| 질문 맥락 | trigger = QUESTION 이거나, 질문이 끝나고 `answerWindowMs`(30초) 안의 버튼 대답 → YES/NO는 그 질문의 대답(기록에 질문 key). 질문 밖의 응/아니는 도움말 |
| ‘다시’ | 마지막 문장을 INFO `repeat`로 · 질문 중이면 질문을 다시 들려주고 한 번 더 들음(같은 질문에서 한 번만, 새 질문마다 0으로) |
| ‘조용히’ | `quietMs`(10분) 동안 INFO만 미룸 — 미룬 INFO는 늦은 알림 규칙대로(말하거나 ‘못 전한 말’) |
| 대답 | 짧은 한 문장(`VoicePhrases`, 1.2~4초 추정), 앞의 대답이 남아 있으면 지우고 새로 |
| 질문 뒤 침묵 | 아무것도 하지 않음(앱이 ‘대답 없음’으로만 기록 — Ledger에는 남기지 않음) |

앱 배선(계약 밖, `app/platform/PleosStt.kt` · `DrivePromiseService`): Pleos SpeechToText 3.3.1 ON_DEVICE, 모든 호출은 리플렉션(SDK 판이 달라도 컴파일 유지, 없으면 듣기만 꺼짐). 버튼은 최대 6초, 질문 뒤는 5초 · 우리 말·내비 안내 중이면 끝날 때까지(버튼 6초·질문 2초까지) 기다림 · ‘띵’(ToneGenerator 120 ms) 250 ms 뒤 `request()` · 내비 재생기가 나타나면 `stop()` · 결과는 첫 최종(`onUpdated(…, completed=true)`) 또는 시간이 다 된 뒤 부분 결과 · 시험용 PCM 주입(`sendAudio` 3,200 B/100 ms 실시간 + 0.3초 묵음 → `completeAudioSend`, `request()` 없이 — 공식 문서: 첫 버퍼로 인식 시작, `onReady`·`onUpdatedRms` 없음). 1.2.1: 시간이 다 돼 `stop()`한 뒤 마지막 결과를 기다리는 시간 1.5 → 2.5초.

## 9. 변경 기록

| 대상 | 계획서 초안(v0.2)·발화 정책 v0.4 | 코어 계약 v0.1 | 까닭 |
|---|---|---|---|
| 입력 원천 | `Flow<T>`, `suspend planRoutes` | 콜백 `Source.start(Sink)`, `planRoutes(…, done)` | 코어 의존성 0, JVM 시험만으로 검증(플랫폼이 Flow로 감싸도 됨) |
| 침묵 구간 | `data class QuietWindow(reason, untilMillis)` | `interface QuietWindows` | 판단 질문(말해도 되나)을 계약으로, 계산은 gate 구현 |
| 말하기 | `Speaker.say(Utterance)` | `Speaker.speak(id, text, usage)` + `VoiceLoop` | 물러서기·반복·기록을 위해 id로 추적 |
| 예고 | 2:00까지 유효 | **1:58까지**(`headsUpCutoffMin` 2) | 예고와 본 알림이 붙어 들리지 않게 |
| 미룸 조건 | 없음 | **`speakWhen`** | 세워 둔 차에 ‘가까운 곳에 세워요’를 말하지 않게(시험 설계 중 발견) |
| 결정 | Speak(…) | **`Step.Speak.estMs`** | 플랫폼 시간 초과 감시·시험 |
| 내비 이벤트 | tMillis 없음 | **tMillis + RouteError** | 결정적 리플레이 · G1-B v0.7 CAUSE_SEARCH_ROUTE |
| 시각 | `Clock.now()` | `Clock.nowMs()` | 단위 명시 |
| 기록 | 7종 | **+ StatusChanged**(11/6 작업) | 약속 상태 변화 기록(기본값 있는 추가) |
| 약속 엔진 | 11/6 예정 | **engine·plan·scene 구현**(11/4 밤에 당김) | 계약 변경 없이 추가 |
| 게이트·세션 | 11/7·11/8 예정 | **gate·session·ReplayPlayer·LedgerJson 구현**(11/5 새벽에 당김, 스펙 S57~S72) | 계약 변경 없이 추가 |
| 모듈 구성 | `:platform` · `:replay` 따로 | **`:app` 안 패키지(app.drivepromise.platform) + `core/replay/ReplayPlayer`** | 모듈 수를 줄여 빌드 실패 지점을 줄임(11/5 결정: 운영사 문의 없이 우리 기본값으로 진행) |
| 주행 끝 판정 | 구역: 끝 시점 안이면 지킴 · 비: 끝 시점 과속 아니면 지킴 | **한 번이라도 구역 밖·빗길 과속이 있었으면 그 주행은 못 지킴**(고속도로와 같은 규칙) | 약속의 뜻(‘구역 안에서만’·‘빗길 조심’)에 맞게 — 시연 트립 검증 중 발견 |
| 주행 끝 요약 | 남은 말 모두 요약 | **반복 예약(repeat > 0)은 버림**(`trip_end_repeat`) | 이미 들려준 말의 반복은 ‘못 전한 말’이 아님 |
| 차량 신호 원천(앱) | AAOS CarPropertyManager 7속성 + Vehicle SDK 방향지시등 보조 | **방향지시등·와이퍼·전조등은 Pleos Vehicle SDK 2.3.2(registerTurnSignalState·registerWiperState·registerLow/HighBeamLightState)를 주 원천으로, AAOS는 되는 차종에서 보조** | 11/5 에뮬레이터 실측: CarPropertyManager는 GEAR_SELECTION·PERF_VEHICLE_SPEED만 등록되고 나머지 5개는 "propertyId is not in carPropertyConfig list"(VHAL에 없음). 시동 상태는 원천 없음 → null(세션은 기어 P·도착·시간으로 끝냄). 계약(VehicleSnapshot)은 그대로 |
| 제한속도(앱) | onDrivingInfo/onRouteStateInfo의 speedLimit 필드 | **+ `onCurrentRoadSpeedLimit`(매초, 0=없음)** → `NaviEvent.SpeedLimit`(0보다 크고 바뀔 때만) | 11/5 실측에서 확인한 콜백 이름. 계약 변경 없음 |
| 화면 모드(11/5 Q036) | 주행 중 화면 없음(음성만, DO 선언 안 함 — 11/5 기본값) | **DO 인정을 가정해 주행 화면(DriveActivity, DO 선언) 추가 — `ui.ModeResolver`(결정표·히스테리시스·능력 판단)·`TripSession.repeatLast()`·스펙 S73~S75** | 사용자 요청(Q036). 가정이 틀린 환경에서는 차단 감지로 음성만(바닥 모드) — 판단·발화 계약은 그대로 |
| 어댑터 수명(앱) | 세션마다 start/close | **차량·내비 어댑터는 서비스 수명 동안 한 번만 초기화, 세션은 받는 쪽만 교체**(`shutdown()`은 서비스 종료 때) | 실측: startReal이 3회 불려(생성·권한 결과·리플레이 끝) Car·Vehicle SDK·NaviHelper가 매번 다시 초기화됨 — 낭비·중복 로그 |
| 제출본(11/5 Q038) | versionCode 1 · 0.1 · release 서명 없음 | **1.0.0(versionCode 2) · release는 저장소 밖 키(`~/.drivepromise/signing.properties`)로 서명, 난독화 없음 · `apk/DrivePromiseTeam_DrivePromise_release.apk` · README(심사용 4항목)·`docs/MANUAL.md`** | 공모전 제출 안내(README 필수 항목·`apk/` 경로·release APK). 코어·앱 동작 변경 없음(스펙 75개 그대로) |
| 음성 명령(11/6 Q040) | STT는 2차 계획 | **STT C안(A 버튼 + B 물을 때만 듣기) — `voice` 패키지·`VoicePhrases`·`TripSession.voiceInput/listening/takeListenRequest/onVoice`·`SpeechScheduler.muteInfoUntilMs`·Ledger `VoiceCommand`·RestTracker 질문형 문장(`ask`)·스펙 S77~S80 · 앱 1.2.0(versionCode 4)** | 사용자 결정(C안). 늘 듣지 않음(웨이크워드 없음)·닫힌 문법·모르면 행동하지 않음 — 잘못 알아듣고 행동하는 것보다 한 번 더 묻는 쪽이 안전. STT가 없으면 1.1.0과 같은 문장·동작 |
| 근접 알림 비교(11/6) | 알림 순간에 비교 | **알림 3초 뒤 비교 + 시간 차 `lagMs`** | 1.1.0 실측: 근접 알림이 코어보다 47~88 ms 먼저 와서 0/2 ‘불일치’로 기록됨(판단은 같았음) |
| 11/6 실행 보정(Q041) · 앱 1.2.1(versionCode 5) | 질문 판정 = 본 알림 첫 말 · 리플레이 시계 = 배속 고정 · 실차 복귀 = 끝 15초 뒤 · 끝난 뒤 반복 예약 · 경로 취소 = 실패 · 계획 오류 문장에 코드 | **질문 판정 = ‘?’로 끝난 문장(`Phrases.isQuestion`) · 리플레이 시계 = 듣는 동안 멈춤·끝난 뒤 실시간(`hold`·`realTimeAfterEnd`) · 실차 복귀 = 할 말이 끝난 뒤(`pendingAfterEnd`, 4~45초) · `tripEnded` 뒤 반복 없음 · 요청 뒤 3초 안 취소 = 앞 경로 바뀜(새 경로 15초 기다림) · 오류 코드는 말하지 않음 · 스펙 S81·S82 · 와이퍼 `WindowArea(FRONT_WINDSHIELD)`+`CAR_WINDOWS` · 시연 트립 rest_two_hours v0.2(ETA 15:18 · 2:13:20 도착)** | 11/6 에뮬레이터 실행(run_1106_094037): ‘곧 도착’ 문장 뒤 12초 듣는 사이 15초 복귀가 요약을 끊음(요약 0/2) · 60배속 요약 둘째 줄 소실(S82로 발견) · `onRouteCancelled` 139 ms 뒤 새 경로 5.8초 뒤 · `registerWiperState(WindowArea, …)` 영역 인자를 못 만듦. 음성 시험 실패는 앱이 아닌 실행 스크립트(adb가 반복문 입력을 먹어 0.19초만 주입) |
| 11/7 외부 검토 반영(Q047·Q048) · 앱 1.3.0(versionCode 7) | 1.2.2 동작 | **일몰 문장 정정·도착 뒤 밤 문장 없음 · 신호 없음 = UNKNOWN(고속도로·비·구역 TTL 5분) · 신호 대기 창에서 방향지시등·급제동 제외 · 글자 수 말 길이·감시 타이머·실패 뒤 2초 · 조사 허용 문법(한국어 모드 = 한국어 표만) · 구역 예고 = 움직이며 10초 15 m · 경로 취소/도착 때 제한속도·도로 비움 · 쉬기: 넘김 따로 말함·복원은 저장 때 정차였거나 60분 이상·끄기 · 고속도로 갈림길 예고(TBT `toward`) · 약속 정하기 화면·설정 명령 · 시동 자동 시작(BootReceiver)·주행 끝 뒤 다음 실차 세션 · 서비스 DUMP 보호·DriveActivity 비공개 · 위치 로그 소수 3자리 · 스펙 S84~S97 → 97개** | codex 검토 17건 사실 확인(F07은 adb 명령을 지키려 DUMP 권한으로, F09는 Q027 정책 유지 + 넘김 따로 기록) · antigravity 검토 일부 반영(오류 6건 제외). 문법 C18(질문 맥락)은 G2 녹음 뒤 LOPO로 미룸 |
| 11/6 두 번째 실행 보정(Q043) · 앱 1.2.2(versionCode 6) | ‘약속 알려 줘’ = 판단된 약속 수 + ‘모두’ · 와이퍼 등록 = 한 인자 꼴이 없으면 먼저 `vsdk_reg_missing` | **판단 전(UNKNOWN) 약속이 있으면 ‘모두’라 하지 않고 하나면 이름(“해 지기 전 귀가는 아직 몰라요”), 둘 이상이면 개수 · 위험과 섞이면 ‘나머지’ 대신 개수(`VoicePhrases.status`, 스펙 S83 → 83개) · `vsdk_reg_missing`은 모든 등록 꼴이 실패했을 때만** | run_1106_135232: 화면엔 약속 5개인데 대답이 “약속 4개 모두 지키는 중이에요.”(목적지가 없어 해 지기 전 귀가가 UNKNOWN) · 와이퍼가 WindowArea 꼴로 등록되고 첫 값도 받았는데 판정이 ‘실패’로 셈 |
| 위치(11/5 Q039) | LocationManager(fused/gps) | **Pleos FusedLocation 1.1.3 주 · LocationManager 대체 · 약속 구역 근접 알림 이중 확인(기록만) · 경계 히스테리시스 `zoneBackM` 100 m(S76) · 앱 1.1.0(versionCode 3)** | 실제 위치를 Pleos SDK에서 받고(기술 활용), 플랫폼 근접 알림으로 구역 판정을 한 번 더 확인. 히스테리시스가 없으면 경계에 선 채 GPS가 ±30 m 흔들릴 때 ‘돌아왔어요/밖이에요’를 되풀이함(S76이 옛 코드에서 실패로 확인) |

## 10. 다음(이 계약 위에)

| 날짜 | 할 일 |
|---|---|
| 11/6 | ~~PromiseEngine · SolarCalc · PreTripPlanner~~ → 11/4 밤 완료(스펙 S42~S56) |
| 11/7 | ~~gate: QuietWindows 구현~~ → 11/5 완료(S57~S63) · ~~ConflictForecaster~~ → 11/8 갈림길 예고(S97) · NaviHelper 경로 ‘검색만’ API — 공개 API에 없음(옵션별 후보는 2차 이후) |
| 11/8~ | 1.3.0 에뮬레이터 실행(GUARD·NEXT·SETTINGS·말 길이 실측) → 시연 영상 v0.4 · 11/10 G2 녹음(문법 1.3.0으로, 사전 등록에 문법 판 기록) → LOPO로 질문 맥락 문법(C18) |
| 11/7~ | 음성 명령: 에뮬레이터 실측(STT 초기화·음성 파일 주입·질문 뒤 듣기) 뒤 문법·대기 시간 조정 · RECORD_AUDIO 필요 여부 확인 · 실차 마이크·소음에서 다시 |
| 11/8 | ~~어댑터·상태 머신·배선~~ → 11/5 `:app`(platform 패키지·DrivePromiseService·MainActivity) 구현 — 11/5 10:48·10:56 에뮬레이터 빌드·스모크 통과(병원→집: dark.risk 0초 → 요약 50초, TTS onDone 2/2) · 11/5 v0.2: 차량 신호를 Vehicle SDK로, 제한속도 콜백, 어댑터 1회 초기화 · 11/5 v0.3(Q036): DO 인정 가정 + 주행 화면(DriveActivity)·화면 모드·‘다시 들려줘’ · 남은 것: 와이퍼·전조등 열거형 이름 실측 뒤 표 좁힘, TBT 도로 이름·주의 지점·도착 콜백 보정, 주행 화면이 에뮬레이터 기어 D에서 가려지는지(차단 감지) 실측 |
