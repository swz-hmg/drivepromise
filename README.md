# 운전약속 DrivePromise

시니어 운전자가 스스로 정한 운전 약속(예: 해 지기 전 귀가, 고속도로 피하기, 2시간마다 쉬기)을 지키도록 돕는 Pleos Connect 차량용 앱입니다.
A Pleos Connect in-vehicle companion that helps older drivers keep the driving promises they set for themselves.

> 본 앱 소스(`app/`)는 개발 중입니다. 지금은 기술 확인용 프로브만 있습니다.

## 구성

| 경로 | 내용 |
|---|---|
| `probes/g1b/` | G1-B 프로브 앱 — 주행 중 화면 차단(DO)·음성(TTS)·차량 신호·경로 API를 에뮬레이터에서 확인 |
| `app/` (예정) | DrivePromise 앱 |
| `apk/` (제출 때) | 심사용 release APK |

## 빌드 (프로브)

JDK 17, Android SDK 35, Pleos Playground Maven 저장소(토큰 불필요)를 씁니다.

```bash
cd probes/g1b
./gradlew assembleDebug
```

## 설계 원칙 (요약)

- 주행 중에는 음성 중심, 화면은 정차·출발 전 (플랫폼이 허용하면 작은 상태 표시만)
- 회전 앞뒤 ‘침묵 구간’에는 말하지 않음 — 안전 경고만 예외
- 판정·발화 로직은 화면과 분리된 포그라운드 서비스에서 동작

## 비밀 정보

API 키·서명 키는 저장소에 넣지 않습니다(`local.properties`, `secrets.properties`, `*.jks`는 `.gitignore`).
