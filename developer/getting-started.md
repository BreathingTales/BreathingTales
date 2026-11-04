# 시작하기

저장소를 받아 빌드하고, AAOS 에뮬레이터에서 앱을 실행하기까지의 순서입니다.

## 1. 준비물

| 항목 | 버전 |
|---|---|
| JDK | 17 이상 |
| Android Studio | AGP 9.1을 지원하는 버전 |
| Android SDK Platform | 37 (compileSdk 37, targetSdk 35, minSdk 31) |
| 에뮬레이터 | Pleos Connect Emulator (Android 14, AAOS) + Virtual Console |

라이브러리 버전은 `gradle/libs.versions.toml` 한 곳에서만 관리합니다. Pleos SDK(`ai.pleos.playground:Vehicle`, `TextToSpeech`, `SpeechToText`)는 공개 Maven 저장소에 있어 인증 없이 받아집니다. 저장소 주소는 `settings.gradle.kts`에 등록되어 있습니다.

AGP는 Android Studio가 지원하는 최고 버전에 맞춰 두었습니다. Studio를 업데이트하기 전에는 올리지 마세요.

## 2. 빌드

```
./gradlew assembleDebug        # debug APK
./gradlew :domain:test         # 도메인 단위 테스트 (에뮬레이터 불필요)
./gradlew test                 # 전체 단위 테스트
```

release 빌드와 서명은 "빌드와 릴리스" 문서에서 다룰 예정입니다.

## 3. 에뮬레이터 준비

에뮬레이터를 켤 때마다 아래를 합니다.

1. **Virtual Console 연결**: PC에서 포트를 연결합니다. 에뮬레이터를 다시 켜면 연결이 사라집니다.
   ```
   adb forward tcp:8088 tcp:8088
   ```
2. **AVD 켜기**: 브라우저에서 `http://127.0.0.1:8088/`(Virtual Console)을 열고, 상단 상태 줄의 `AVD`가 `Enabled`인지 확인합니다. `Disabled`면 앱의 공조 명령이 **오류 없이 무시됩니다.**
3. **Virtual Console 탭을 화면에 보이게 둡니다.** 페이지는 1초마다 `POST /browser/session` 신호를 보내고, 서버는 신호가 3초 끊기면 AVD를 끕니다. 탭을 가리거나 최소화하면 브라우저가 타이머를 늦춰 AVD가 꺼집니다. 탭을 가려야 한다면 터미널에서 같은 신호를 보내 둡니다.
   ```
   while true; do curl -s -X POST "http://127.0.0.1:8088/browser/session?client=cli-keepalive" >/dev/null; sleep 1; done
   ```
4. **차량 상태 확인**: 앱이 바꾼 값이 실제로 반영됐는지 여기서 봅니다. `availability.available`이 `false`면 AVD가 꺼진 것입니다.
   ```
   curl http://127.0.0.1:8088/vehicle/state
   ```

### 마이크
아이 참여(외치기, 숨 불기)를 시험하려면 에뮬레이터에 PC 마이크가 연결되어 있어야 합니다. 에뮬레이터는 **켤 때** 마이크 연결 여부를 정하므로, 마이크를 연결한 뒤에 에뮬레이터를 다시 켭니다.

## 4. 설치와 실행

AAOS 앱은 **사용자 10**으로 실행됩니다. adb로 앱 데이터나 권한을 다룰 때는 `--user 10`을 붙입니다.

```
./gradlew installDebug
adb shell am start --user 10 -n com.breathingtales/.MainActivity
```

연출 중에 앱을 다시 설치하면 공조·조명이 바뀐 채로 남습니다([안전 설계](safety.md) 5장). **다시 설치하기 전에 연출을 먼저 멈춰 주세요.**

## 5. Fake 차량으로 실행

에뮬레이터 없이도 UI와 연출 흐름을 볼 수 있도록, debug 빌드에는 가짜 차량이 들어 있습니다.

```
./gradlew installDebug -PfakeVehicle=true
```

- 조작 패널에서 정차(P)·주행(D), 문 열기·닫기, 주차 브레이크를 바꿔 정차 조건이 깨졌을 때의 동작을 확인할 수 있습니다. 패널은 현재 실내 상태(공조, 온도, 조명, 열선)와 앱이 보낸 제어 명령 기록도 보여 줍니다.
- 바꾸는 지점은 `app/src/debug/.../di/VehicleBindingModule.kt` 한 곳입니다. release 빌드(`src/release`)는 항상 실제 Pleos 어댑터를 쓰고, Fake 코드는 release APK에 들어가지 않습니다.

## 6. 확인용 화면 (probe)

SDK 동작을 하나씩 확인하는 debug 전용 화면입니다. 런처 아이콘이 없으므로 adb로 엽니다.

```
adb shell am start --user 10 -n com.breathingtales/com.breathingtales.probe.ProbeActivity
adb logcat -s BTProbe
```

## 7. 처음 읽을 코드

| 위치 | 내용 |
|---|---|
| `domain/.../safety/SafetyGuard.kt` | 모든 차량 제어가 거치는 관문 ([안전 설계](safety.md)) |
| `domain/.../usecase/PlayStoryUseCase.kt` | 동화 재생 엔진. 장면·대사·효과·참여 진행 |
| `data/.../vehicle/PleosVehicleAdapter.kt` | Pleos Vehicle SDK 어댑터 |
| `data/.../story/StoryParser.kt` | `story.json` 파싱 ([동화 포맷](story-format.md)) |
| `app/.../viewmodel/PlayViewModel.kt` | 재생 화면 상태 |
