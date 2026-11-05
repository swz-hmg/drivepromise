# apk/ — 제출용 release APK

| 파일 | 내용 |
|---|---|
| `DrivePromiseTeam_DrivePromise_release.apk` | 운전약속(DrivePromise) 1.0.0 (versionCode 2) — release 빌드, 난독화 없음, 출품용 전용 키로 서명(인증서 CN=DrivePromise Team) |
| `SHA256SUMS.txt` | 위 APK의 SHA-256 체크섬 — `shasum -a 256 -c SHA256SUMS.txt` 로 확인 |

설치·실행은 저장소 첫 화면 [README](../README.md) 2장, 문제 해결은 [docs/MANUAL.md](../docs/MANUAL.md) 4장을 봐 주세요.

```bash
adb install -r -g apk/DrivePromiseTeam_DrivePromise_release.apk
adb shell pm grant app.drivepromise pleos.car.permission.TTS_SERVICE
adb shell am start -n app.drivepromise/.MainActivity
```

파일 이름은 공모전 안내의 `팀명_앱이름_release.apk` 형식을 따르되, 블라인드 심사를 위해 팀명 자리에 중립 별칭(DrivePromiseTeam)을 썼습니다.
