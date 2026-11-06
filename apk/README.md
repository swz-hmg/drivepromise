# apk/ — 제출용 release APK

| 파일 | 내용 |
|---|---|
| `DrivePromiseTeam_DrivePromise_release.apk` | 운전약속(DrivePromise) release 빌드 — 난독화 없음, 출품용 전용 키로 서명(인증서 CN=DrivePromise Team). 에뮬레이터 시험(코어 스펙 · 위치 · 음성 명령 · 시연 주행 4개)을 통과한 빌드만 이 폴더에 올립니다 |
| `SHA256SUMS.txt` | 위 APK의 SHA-256 체크섬 — `shasum -a 256 -c SHA256SUMS.txt` 로 확인 |

버전은 이 APK를 올린 커밋 메시지(`Release APK x.y.z for submission`)에 적혀 있고, 설치한 뒤에는 아래 마지막 줄로 확인할 수 있습니다.
설치·실행은 저장소 첫 화면 [README](../README.md) 2장, 문제 해결은 [docs/MANUAL.md](../docs/MANUAL.md) 4장을 봐 주세요.
Pleos 에뮬레이터에서는 앱이 운전자 사용자로 돌기 때문에 권한을 줄 때 `--user current`를 붙입니다.

```bash
adb install -r -g apk/DrivePromiseTeam_DrivePromise_release.apk
adb shell pm grant --user current app.drivepromise pleos.car.permission.TTS_SERVICE
adb shell pm grant --user current app.drivepromise pleos.car.permission.FUSED_LOCATION
adb shell pm grant --user current app.drivepromise pleos.car.permission.STT_SERVICE
adb shell pm grant --user current app.drivepromise android.permission.RECORD_AUDIO
adb shell pm grant --user current app.drivepromise android.car.permission.CAR_SPEED
adb shell am start -n app.drivepromise/.MainActivity
adb shell dumpsys package app.drivepromise | grep versionName
```

파일 이름은 공모전 안내의 `팀명_앱이름_release.apk` 형식을 따르되, 블라인드 심사를 위해 팀명 자리에 중립 별칭(DrivePromiseTeam)을 썼습니다.
