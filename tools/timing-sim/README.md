# tools/timing-sim — 발화 타이밍 시뮬레이션

판단 코어(`core/`)의 `gate.QuietGate`·`speech.SpeechScheduler`를 수정 없이 불러, 같은 가상 주행·같은 말 도착에 정책을 바꿔 비교합니다(공통 난수). 앱 빌드(`settings.gradle.kts`의 `:core`·`:app`)에는 들어가지 않습니다. 결과와 해석은 [`docs/TIMING_EVAL.md`](../../docs/TIMING_EVAL.md).

| 파일 | 내용 |
|---|---|
| `TimingSim.kt` | 주행 생성(회전·신호 정지·차선 변경 방향지시등·급제동·TBT 5초마다) · 정답 창 · 말 도착(포아송) · 정책 실행 |
| `Report.kt` | 도로 3종 × 정책 지표 표(TSV) — `ReportKt <주행 수> <분당 말 수>` |
| `Hist.kt` | 도심 밀집에서 회전 뒤 말 시작 시각 분포 |
| `Variants.kt` | 회전 통과 기준 20 · 10 · 0 m 비교 |
| `DemoCmp.kt` | 시연 트립 4개(`demo/trips`)의 말 순서·시각 — 회전 통과 기준 20 m vs 0 m |

```bash
kotlinc -jvm-target 17 -d /tmp/core.jar core/src/main/kotlin
kotlinc -jvm-target 17 -cp /tmp/core.jar -d /tmp/sim.jar tools/timing-sim/*.kt
java -cp /tmp/sim.jar:/tmp/core.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar ReportKt 300 0.5
```
