# 숨쉬는 동화 (Breathing Tales)

> 차가 멈춰 있는 동안, 차 안이 동화의 무대가 되는 오감 동화 앱

AAOS만의 특화된 서비스, 자동차의 차량 상태 제어를 활용한 이야기를 차량을 통해 무대화합니다.

## APK

| 파일 | 위치 |
|---|---|
| release APK (서명됨, 버전 0.1.0) | [`apk/BreathingTales_BreathingTales_release.apk`](apk/BreathingTales_BreathingTales_release.apk) |

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator (Android 14, AAOS, EV 구성) + Virtual Console |
| 사용 SDK | Pleos Vehicle 2.3.2, TextToSpeech 3.3.1, SpeechToText 3.3.1, AAOS `android.car` |
| 앱 요구 사항 | minSdk 31, targetSdk 35 |
| 마이크 (선택) | 아이 참여(외치기·숨 불기)를 시험할 때. 인식되지 않아도 화면의 대체 버튼으로 진행할 수 있음 |

## 실행 방법

### 1. 에뮬레이터 준비
1. Pleos Connect Emulator를 켭니다.
2. PC에서 Virtual Console 포트를 연결합니다. 에뮬레이터를 다시 켜면 다시 실행해야 합니다.
   ```
   adb forward tcp:8088 tcp:8088
   ```
3. 브라우저에서 Virtual Console(`http://127.0.0.1:8088/`)을 열고, 상단의 **`AVD`가 `Enabled`**인지 확인합니다.
4. **Virtual Console 탭을 화면에 보이게 둡니다.** 탭이 가려지면 3초 뒤 AVD가 꺼지고, 그동안 앱의 차량 명령이 오류 없이 무시됩니다. 탭을 가려야 한다면 터미널에서 아래를 띄워 두세요.
   ```
   while true; do curl -s -X POST "http://127.0.0.1:8088/browser/session?client=cli-keepalive" >/dev/null; sleep 1; done
   ```
5. Virtual Console에서 **기어 P, 속도 0, 모든 도어 닫힘** 상태인지 확인합니다. 이 조건을 모두 만족해야 이야기가 시작됩니다.

### 2. 설치와 실행
```
adb install apk/BreathingTales_BreathingTales_release.apk
adb shell am start --user 10 -n com.breathingtales/.MainActivity
```
- AAOS 앱은 사용자 10으로 실행됩니다. 런처에서 "숨쉬는 동화"를 눌러 실행해도 됩니다.
- 처음 실행하면 **마이크**와 **차량 속도** 권한을 묻습니다. 모두 허용해 주세요.
- 다른 서명의 같은 앱이 설치되어 있으면 설치가 실패합니다. 이때는 `adb uninstall com.breathingtales` 후 다시 설치합니다.

### 3. 앱 설정과 이야기 시작
1. 홈 화면에서 **아이 이름**을 입력하고 저장합니다. 이야기 속에서 Pleos TTS가 이 이름을 불러 줍니다.
2. **연출 강도**(약·중·강)를 고릅니다. 강하게 골라도 안전 범위를 넘지 않습니다.
3. (선택) 오른쪽 위 **설정**에서 숨 불기 민감도, 시트 들썩임을 바꿀 수 있습니다. 시트 들썩임은 기본 꺼짐입니다.
4. **이야기 시작**을 누릅니다. 재생 중 위쪽 버튼으로 일시정지, 다음 장면, 그만하기를 할 수 있습니다.

### 확인해 볼 동작
- **차량 연출**: 장면에 따라 Virtual Console의 풍량·풍향·온도·무드등·실내등·독서등·뒷좌석 열선이 바뀝니다.
- **복원**: 이야기가 끝나거나 "그만하기"를 누르면 실내가 시작 전 상태로 돌아옵니다.
- **안전 중단**: 재생 중 Virtual Console에서 기어를 D로 바꾸거나 도어를 열면 즉시 멈추고 복원합니다.
- **아이 참여**: 참여 장면에서 외치기·숨 불기를 하거나, 화면의 대체 버튼을 누릅니다. 반응이 없으면 시간이 지난 뒤 이야기가 이어집니다.

### 알아 둘 점
- **시트 들썩임은 에뮬레이터에서 동작하지 않습니다.** 에뮬레이터 SDK가 시트 위치를 늘 0으로 돌려주어, 위치를 확인할 수 없으면 움직이지 않도록 안전하게 건너뜁니다. 시연 영상의 시트 동작과 안전 장치 표시(오른쪽 위 HUD)는 개발용 빌드의 시연 모드로 찍었고, 제출한 release APK에는 없습니다.
- 차량 상태는 `curl http://127.0.0.1:8088/vehicle/state`로도 확인할 수 있습니다.

## 소스에서 빌드
요구 사항: JDK 17 이상, Android SDK Platform 37

```
./gradlew test               # 단위 테스트
./gradlew assembleDebug      # 개발용 APK
./gradlew assembleRelease    # release APK
```
릴리스 서명 키(`keystore.properties`, `keystore/`)는 저장소에 없습니다. 없으면 서명되지 않은 release APK가 만들어집니다. 자세한 내용은 [시작하기](developer/getting-started.md)와 [빌드와 릴리스](developer/build-release.md)에 있습니다.

## 문서
- [개발자 문서](developer/README.md): 구조, 안전 설계, 동화 포맷, 차량 연동, 테스트
- [안전 설계](developer/safety.md): 정차 조건, 차량 제어 허용 범위, 복원, 시트 움직임 규칙
- [변경 기록](developer/CHANGELOG.md)

## 구조
| 모듈 | 역할 |
|---|---|
| `:domain` | 순수 Kotlin. 동화·효과 모델, 안전 정책(SafetyGuard), 재생 엔진 |
| `:data` | Pleos Vehicle/TTS/STT SDK, AAOS 주행 제한, Media3, 동화 JSON 파싱 |
| `:app` | Jetpack Compose UI, ViewModel, Hilt DI |

## 콘텐츠 출처
- 음성: Qwen3-TTS (Apache-2.0, 글로 설계한 목소리. 「해님과 바람, 그리고 작은 차의 나그네」 대사 음원)
- 그림: FLUX.1-schnell (Apache-2.0)로 생성한 장면 일러스트 12장과 표지. 실제 차종·기존 작품 화풍을 흉내 내지 않도록 프롬프트로 지정
- 아이 이름 부르기: Pleos TTS
- 효과음: 숨쉬는 동화 팀 자체 제작 (직접 합성)
- 대본·음원 제작 도구는 저장소에 넣지 않았습니다. 앱이 쓰는 결과물은 `app/src/main/assets/stories/`에 있습니다.
- 「해와 바람」은 이솝 우화(공유 저작물)를 새로 각색했습니다.
