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
| `ledger` | LedgerEvent · LedgerSink · MemoryLedger · RestCompliance · RestMetrics | 구현·시험 |
| `replay` | ReplayParser(스키마 0.2) · ReplayTrip · MiniJson | 구현·시험 |
| `engine` · `plan` · `gate` · `forecast` · `scene` | PromiseEngine · SolarCalc · PreTripPlanner · QuietWindows 구현 · ConflictForecaster · PromiseComposer | 11/6~11/7 |

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
interface LocationSource : Source<GeoFix>
interface VehicleSource : Source<VehicleSnapshot>          // 속성별 변경을 합친 전체 상태
interface RouteSource : Source<NaviEvent> {
    fun planRoutes(dest: Place, options: List<RouteOption>, done: Sink<RoutePlans>): AutoCloseable   // 결과(실패 포함) 한 번
}
fun interface Clock { fun nowMs(): Long }
```

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

`RestPolicy`: targetMin 120 · leadMin 10 · headsUpCutoffMin 2 · repeatMin 15 · maxRepeats 2 · restResetMin 15 · arrivalSoonMin 15 · estMs 3 500 · estShortMs 2 000.

### 4.3 말해도 되는 때 — `QuietWindows` (구현은 11/7 gate)

```kotlin
interface QuietWindows {
    fun normal(nowMs: Long, needMs: Long): Boolean     // 회전 8초 전(좌회전 10초)~회전 뒤 8초 침묵, 예측 안내(300·100 m 앞 2.5초), 방향지시등 중 침묵
    fun stopWindow(nowMs: Long): Boolean               // 정지선 대기 시작 2초 안(P6)
    fun longGap(nowMs: Long, needMs: Long): Boolean    // 확실한 틈
}
```

### 4.4 스케줄러 — `SpeechScheduler`

API: `submit(u, now)` · `cancel(keyPrefix, now, reason)` · `tick(world, voiceFree): Step` (10 Hz) · `spoken(id, now)` · `yielded(id, now)` · `failed(id, now, cause)` · `endTrip(now)` · `digest` · `pendingKeys`.

```text
tick(w, voiceFree):
    for p in queue: housekeep(p, w)          # 말할 수 없어도 매번
        PROMISE ∧ (¬stillValid ∨ (UNTIL_DEADLINE ∧ now > deadline + lateOk)) → 제거
            UNTIL_DEADLINE: 요약 EXPIRED + followup / WHILE_STATE: 요약 STATE_ENDED
        INFO ∧ now − created > 60 s → 요약(INFO_TIMEOUT)
    if speaking ∨ ¬voiceFree: return Idle
    for p in order(SAFETY > PROMISE(마감 순) > INFO(먼저 온 순)):
        skip if p.notBefore > now ∨ ¬speakWhen(w)
        need ← (p.yields > 0) ? estShort : est
        ok ← SAFETY
             ∨ (yields ≥ 2 ? stopWindow ∨ longGap(need + 4 s)
                : normal(need) ∨ (PROMISE ∧ now ≥ deadline − 60 s ∧ stopWindow))
        if ok: return Speak(id, key, render(world, repeat, short), …, estMs = need)
    return Idle
spoken(id): 기록 Spoken · 반복이 남았으면 now + repeatAfter 뒤로 다시 넣음
yielded(id): yields + 1, 다시 대기(다음엔 짧은 문장)
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
| 2:00 | “두 시간째예요. 약속대로 쉬어 가요.” (늦으면 그때 값: “두 시간 3분째예요…”) | PROMISE · WHILE_STATE · 주행 중에만 |
| 말한 뒤 +15·+30분 | “쉬지 않고 두 시간 15분째예요. 가까운 곳에 잠깐 세워요.” | 반복 최대 2번 |
| 도착 15분 안 | “곧 도착이에요. 도착하면 꼭 쉬어요.” | 설계 후보(G2 수용성) |
| 쉼 뒤 출발 | “잘 쉬셨어요. 약속 지키셨습니다.” | INFO, 예고·본 알림이 있었던 구간만 |
| 쉬지 못하고 도착 | “오늘은 두 시간 40분을 쉬지 않고 운전하셨어요. 다음엔 한 시간 50분쯤 쉬어 가요.” | `endTrip()`이 돌려줌(요약 담당이 말함) |

쉼 = 기어 P 또는 시동 끔 15분 이상(앱이 꺼져 있던 시간 포함, `state()`/`restore()`). 신호 대기는 운전 시간에 넣음. 시는 고유어(두 시간)·분은 숫자(5분)로 써서 TTS가 바르게 읽게.

## 6. 기록과 지표 — `ledger`

| 이벤트 | 뜻 |
|---|---|
| Due(key, drivingMs) | 약속 알림 시각이 됨(노출) |
| Spoken(key, repeat, yields, endMs, lateMs) | 끝까지 들려줌(tMillis = 시작) |
| Yielded(key, count) · Dropped(key, reason) · Digested(key, reason) | 물러섬 · 버림(expired·state_ended·rested·cancel·tts_failed) · 정차 후 요약 |
| RestTaken(restStartMs, segmentDrivingMs) · TripEnded(drivingMs) | 쉼 인정 · 주행 끝 |

`RestCompliance.compute(events, windowMin = 20)` → 운전 구간(쉼과 쉼 사이)마다 셈:

| 지표 | 정의 | 지표 사다리 |
|---|---|---|
| deliveryRate | 본 알림 시각에 닿은 구간 중 본 알림을 주행 중 끝까지 들려준 비율 | L0 전달 |
| complianceRate | 본 알림을 들려준 구간 중 들려준 뒤 20분 안에 쉼을 시작한 비율 | L4 순응(주 지표) |
| keptRate | 본 알림 시각 뒤 20분 안에 쉼을 시작한 비율(들었는지와 무관) | 약속 지킴 |
| keptOnHeadsUp | 예고 뒤 본 알림 전에 쉰 구간 수 | 예고 효과 |
| medianDeliveryLateMs | 본 알림 시각 → 들려준 시각 중앙값 | 전달 지연 |

한계: 쉼은 P 15분 이상으로만 판단(주유·볼일과 구분 못 함). 분모가 0이면 비율은 null(0이 아님).

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

스펙 41개(S01~S41) — `core/src/test/.../Specs.kt`. 같은 목록을 JUnit 5(`CoreSpecsTest`, 동적 시험)와 의존성 없는 점검 러너가 돌립니다.

| 묶음 | 스펙 |
|---|---|
| 모델·계약 | S01~S02 |
| 스케줄러(순서·창·LATE_OK·반복·실패·요약·그때 값) | S03~S17 |
| 물러서기(실측 타이밍 회귀 3715 ms·v0.6 결함·GAIN 없음·포커스만·새 포커스 잃음·통합) | S18~S24 |
| 2시간 쉬기(전 주기·그때 값·쉼 인정·짧은 정차·예고 마감·도착 임박·이어 가기·신호 대기) | S25~S33 |
| 지표(정의·엔드투엔드) | S34~S35 |
| 리플레이·JSON·문장(ko/en) | S36~S41 |

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

## 10. 다음(이 계약 위에)

| 날짜 | 할 일 |
|---|---|
| 11/6 | PromiseEngine(해 지기 전·고속도로 대신·비·2시간 쉬기·구역) · SolarCalc(NOAA, KASI ±2분) · PreTripPlanner |
| 11/7 | gate: QuietWindows 구현(침묵구간검증 v0.1 값) · ConflictForecaster · PromiseComposer · Ledger 저장 |
| 11/8 | :platform 어댑터(Pleos Vehicle·NaviHelper·TTS·재생 목록·포커스) · :app 상태 머신 · VoiceLoop 배선 |
