# 테스트

안전 규칙과 재생 엔진은 순수 Kotlin이라 에뮬레이터 없이 단위 테스트로 검증합니다. 실제 SDK 동작은 Pleos 에뮬레이터에서 계측 테스트로, 화면과 전체 흐름은 손으로 확인합니다.

## 1. 단위 테스트

```
./gradlew :domain:test      # 도메인만 (가장 빠름)
./gradlew test              # 전체
```

| 모듈 | 테스트 | 확인하는 것 |
|---|---|---|
| `:domain` | `ParkedRuleTest` | 시작 조건, 속도 잡음 허용치 |
| | `ObserveParkedStateUseCaseTest` | 연출 중 정차 감시, 주차 브레이크 해제 |
| | `SafetyPolicyTest`, `SafetyLimitsTest` | 허용 범위, 강도 배율 |
| | `SafetyGuardTest` | 저장·복원 순서, 취소 중 복원, 무드등 전환 값, 시연 HUD 기록 |
| | `SeatLiftTest` | 시트 움직임 조건, 되풀이, 명령 지연이 있을 때 시작 위치 정확히 복귀 |
| | `StoryValidatorTest` | 동화 범위·파일 검사 |
| | `PlayStoryUseCaseTest` | 장면 진행, 효과 시각, 참여, 일시정지, 중단, 건너뛰기, 숨 맞추기, 무드등 전환 |
| `:data` | `StoryParserTest` | `story.json` 파싱, 오류 보고, **실제 동화 파일이 검사를 통과하는지** |
| | `VoiceRulesTest` | 키워드 판정, 숨 불기 판정, 성공 중복 거르기 |
| | `FakeVehicleRoundTripTest` (`testDebug`) | Fake 차량으로 바꾸고 되돌리기 |
| `:app` | `UiTextsTest` | 화면 문구 (중단 이유 안내 등) |

### 가상 시계
`PlayStoryUseCaseTest`는 `kotlinx-coroutines-test`의 가상 시계로 돌아갑니다. 엔진이 시간을 `TimeSource`로 받으므로, 몇 분짜리 장면도 바로 검증할 수 있습니다. `advanceTimeBy`로 시간을 보내고, 차량 호출은 `RecordingPort.calls`에 문자열로 남습니다.

```kotlin
advanceTimeBy(2_500); runCurrent()
assertEquals("fanSpeed=2", rig.port.calls.last())
```

## 2. 계측 테스트 (에뮬레이터)

```
./gradlew :data:connectedDebugAndroidTest
```

준비: 에뮬레이터 실행, Virtual Console `AVD` = `Enabled`, 기어 P·문 닫힘 ([시작하기](getting-started.md) 3장).

| 테스트 | 확인하는 것 |
|---|---|
| `PleosVehicleAdapterTest` | 지원 범위, 공조·조명·열선 바꾸고 복원, 실내등·독서등, 정차 상태 읽기, 무드등 전환 호출 시간 |
| `Media3AudioPlayerTest` | 대사 재생, 오디오 포커스 손실 시 일시정지 |
| `PleosTtsSpeechTest` | TTS가 끝까지 말하고 반환, 앱 포커스를 가져가지 않음, 취소 |
| `MicVoiceInputTest` | 녹음 파일로 외치기·숨 불기 판정 (`androidTest/assets/stories/voice-test/`) |

테스트 하나만 돌리려면:
```
./gradlew :data:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.breathingtales.data.vehicle.PleosVehicleAdapterTest#ambient_fade_frames"
```

## 3. 손으로 확인하는 시나리오

에뮬레이터 + Virtual Console에서 확인합니다. 결과는 `curl http://127.0.0.1:8088/vehicle/state`로 봅니다.

- [ ] 정상 흐름: 시작 → 바람 장면 풍량 상승 → 해님 장면 온기·빛 → 끝난 뒤 복원 → 가족 대화 카드
- [ ] 연출 중 기어 P → D: 즉시 중단, 복원
- [ ] 연출 중 도어 열기: 즉시 중단, 복원
- [ ] 오디오 포커스 손실(내비 안내·전화): 일시정지, 부모가 누르면 재개
- [ ] 아이 반응 없음: 시간 초과 뒤 이야기 계속
- [ ] 실제 목소리로 외치기·숨 불기 (유선 마이크 권장)
- [ ] 시트 열선이 없는 차량 설정: 나머지 연출 정상

연출 중 주차 브레이크 해제는 Virtual Console에 조작 수단이 없어 단위 테스트로만 확인합니다.

## 4. 시연 모드

부모 설정 → **시연 모드 (개발용)**을 켜면(debug 빌드만), 재생 화면 오른쪽 위에 SafetyGuard가 처리한 제어가 "동화 요청 → 차량에 보낸 값"으로 나옵니다.

- 정차 조건(기어·속도·도어·UX 제한·주차 브레이크)
- 바람(단계 → 풍량, 허용 최대), 풍향, 온도(요청 → 영역별 시작·보낸 온도)
- 열선, 무드등, 시트 움직임 결과(건너뛴 이유 포함), 복원 결과

## 5. 문제 해결

| 증상 | 원인과 해결 |
|---|---|
| 앱이 보낸 공조 명령이 반영되지 않음 (오류 없음) | Virtual Console의 AVD가 꺼짐. `availability.available`이 `false`인지 확인하고 탭을 화면에 보이게 두거나 keepalive를 띄움 |
| 재생 뒤 "되돌리지 못했어요 (cabinLight)", logcat에 `실내등 OFF 실패: 50001` | 위와 같음. AVD가 꺼지면 실내등 끄기만 오류로 돌아옴 |
| 연출이 끝났는데 공조·조명이 바뀐 채 남음 | 연출 중 앱을 다시 설치했거나 강제 종료됨. 복원은 앱 안에서 실행되므로 Virtual Console에서 직접 되돌림 |
| 외치기·숨 불기가 인식되지 않음 | 에뮬레이터를 마이크 연결 **뒤에** 다시 켰는지 확인. 숨 불기는 부모 설정 민감도 조절. 대체 버튼은 늘 동작 |
| 시트가 움직이지 않음 | 에뮬레이터 SDK는 위치를 늘 0으로 읽어 건너뜀. 시연 모드를 켜고 Virtual Console에서 조수석 착석·높이를 설정 |
| 동화가 홈 목록에 없음 | 검증 오류. 홈 화면 오류 문구 확인, `StoryParserTest`의 실제 파일 검사 실행 |
| TTS가 소리 없이 끝남 | 목소리 설정이 잘못 저장됨. 말할 때마다 `seoyoon`을 지정하는지 확인 |
| adb로 바꾼 권한·데이터가 적용되지 않음 | AAOS 앱은 사용자 10. `--user 10`을 붙임 |
| 확인용 SDK 로그 | `adb logcat -s BTProbe` (확인용 화면), `BTFade` (무드등 전환 계측) |
