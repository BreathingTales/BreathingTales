# 차량 연동

숨쉬는 동화가 쓰는 차량·음성 API와, SDK를 쓰면서 확인한 주의점입니다. 확인 환경은 Pleos Connect Emulator(Android 14, EV 구성) + Virtual Console, Vehicle SDK 2.3.2, TTS·STT 3.3.1입니다.

## 1. 사용하는 SDK

MVP는 Pleos SDK 세 개와 AAOS 표준 API만 씁니다.

| 의존성 | 용도 |
|---|---|
| `ai.pleos.playground:Vehicle:2.3.2` | 공조, 조명, 시트, 도어 |
| `ai.pleos.playground:TextToSpeech:3.3.1` | 아이 이름 부르기 같은 동적 문장 |
| `ai.pleos.playground:SpeechToText:3.3.1` | 외치기 인식 |
| AAOS `android.car` (`useLibrary`) | 주행 UX 제한, 기어, 속도, 주차 브레이크 |

## 2. 정차 판단

| 조건 | 읽는 방법 | 권한 |
|---|---|---|
| 주행 UX 제한 없음 | AAOS `CarUxRestrictionsManager` | 없음 |
| 기어 P | AAOS `CarPropertyManager` `GEAR_SELECTION` | `android.car.permission.CAR_POWERTRAIN` |
| 속도 0 | AAOS `PERF_VEHICLE_SPEED` (m/s) | `android.car.permission.CAR_SPEED` (런타임) |
| 도어 닫힘 | Pleos `Door.registerDoorPosition` | `pleos.car.permission.CAR_DOORS` |
| 주차 브레이크 (중단 신호만) | AAOS `PARKING_BRAKE_ON` | `CAR_POWERTRAIN` |

정차 판단에 Pleos SDK를 쓰지 않는 이유:
- Pleos Gear 도메인은 deprecated이고, SDK가 AAOS `GEAR_SELECTION`을 쓰라고 안내합니다.
- Pleos SDK에는 속도 API가 없습니다.
- Brake 도메인은 브레이크 페달만 제공합니다.

구현은 `data/.../vehicle/CarVehicleStateRepository.kt`입니다. 판단 규칙은 [안전 설계](safety.md) 1장에 있습니다.

## 3. 차량 제어

| 연출 | API (Vehicle SDK) | 에뮬레이터 범위 |
|---|---|---|
| 공조 전원 | `HVAC.setHvacPower` | 전원 영역은 `checkHvacPowerCapabilities`로 따로 받음 |
| 풍량 | `HVAC.setHvacFanSpeed` | 1~8 |
| 풍향 | `HVAC.setHvacFanDirection` | `FACE`, `FLOOR` |
| 온도 | `HVAC.setHvacTemperature` | 15~30℃, 0.5℃ 단위, 운전석·조수석 영역이 따로 |
| 무드등 색·밝기·스위치 | `Light.setAmbientLightBrightnessColor`, `setAmbientLightBrightness`, `setAmbientLightSwitch` | 밝기 0~100 |
| 주행 모드 자동색 | `Light.setAmbientColorAutoByDriveModeEnabled` | EV 구성 에뮬레이터에는 없음 → 건너뜀 |
| 실내등·독서등 | `Light.setCabinLightSwitch`, `setReadingLightSwitch` | 켜기·끄기 |
| 시트 열선 | `Seat.setHvacSeatTemperature` | 뒷좌석 0~3 |
| 시트 높이 | 시트 높이 이동 (`MoveControl`) | 연속 이동, 아래 5장 |
| 창문 | **쓰지 않음** | 아이 손 끼임 위험 |

### 구현 규칙
1. **영역을 하드코딩하지 않습니다.** 기능마다 영역 구성이 달라서, `check…Capabilities`로 받은 영역을 씁니다. 예를 들어 공조 전원 영역과 풍량 영역은 다릅니다.
2. **공조 전원을 먼저 켭니다.** 전원이 꺼져 있으면 풍량·온도·풍향을 바꿀 수 없습니다(오류 코드 2, `NOT_AVAILABLE`). `set…`의 완료 콜백은 요청을 받았다는 뜻일 뿐이므로, 전원 이벤트(`registerHvacPowerState`)로 실제로 켜진 것을 확인한 뒤 다음 제어를 합니다.
3. **전원이 꺼져 있으면 설정 온도를 읽을 수 없습니다.** 전원을 켠 직후, 아무것도 바꾸기 전에 읽어 복원 기준값으로 씁니다.
4. **모든 호출은 비동기 콜백입니다.** 어댑터에서 `suspendCancellableCoroutine`과 `callbackFlow`로 감쌉니다(`PleosCalls.kt`).
5. **SDK 예외는 타입으로 잡을 수 없습니다.** 배포 AAR에서 `VehicleException`이 난독화되어 있어 `Exception.message`로 구분합니다. 실패한 호출은 로그로 남깁니다.
6. 조명 색은 ARGB로 보내고, 읽으면 알파 없이 RGB만 옵니다. 비교할 때는 `and 0xFFFFFF`를 씁니다.
7. **조명 SDK에는 전환 시간을 받는 API가 없습니다.** `fadeSec`는 앱이 0.1초 간격으로 중간값을 보내서 구현합니다. 에뮬레이터에서 조명 호출 한 번은 평균 5~8ms, 최대 33ms 걸렸습니다.

## 4. TTS·STT

### TTS
- 모드는 `ON_DEVICE`를 씁니다. 앱 등록이 필요 없습니다.
- 언어는 **`Locale.KOREA`**(ko-KR)입니다. `Locale.KOREAN`(ko)은 엔진 오류가 납니다.
- 목소리는 **말할 때마다** `setSpeakerType("seoyoon")`으로 지정합니다. 목소리 설정은 음성 서비스에 앱별로 저장되어, 잘못된 값이 한 번 들어가면 이후 모든 `speak`가 실패합니다.
- 리스너는 **`initialize` 성공 콜백 안에서** 붙입니다. 먼저 붙이면 SDK 내부 오류로 앱이 종료됩니다.
- `speak(text)`를 씁니다. `speak(text, false)`는 소리 없이 끝납니다.
- TTS는 앱의 오디오 포커스를 가져가지 않습니다.
- 속도·감정을 세밀하게 조절할 수 없어서, 고정 대사는 미리 만든 음원으로, 이름이 들어가는 짧은 문장만 TTS로 읽습니다.

### STT
- 리스너와 언어 규칙은 TTS와 같습니다.
- 서비스가 직접 여는 마이크는 에뮬레이터에서 무음입니다. 앱이 녹음해 `sendAudio`로 넘깁니다.
- `sendAudio`는 **100ms(3200바이트) 단위로, `onReady` 뒤에만** 넘깁니다. 어기면 엔진 오류가 납니다.
- `request()` 한 번에 발화 하나를 받습니다. 끝나면 다시 요청하고, 그 사이 소리는 모아 두었다 넘깁니다.

### 아이 참여 판정
| 구성 | 내용 |
|---|---|
| `KeywordMatcher` | 띄어쓰기·문장부호를 무시하고, 한글을 자모로 풀어 비교합니다. 키워드가 길면 자모 몇 개 틀려도 성공으로 봅니다("바람아" → "바라마") |
| `BlowRule` | 마이크 음량(dBFS)이 기준 이상인 상태가 0.5초 이어지면 불기 한 번. 길게 불어도 한 번만 셉니다. 기준은 부모 설정의 민감도로 바꿉니다 |
| `SuccessFilter` | 외침 한 번이 불기와 외치기로 두 번 세어지지 않게, 소리 덩어리마다 한 번만 셉니다 |

## 5. 시트 높이

- SDK 시트 이동은 목표 위치를 받지 않고, 방향(위·아래)을 정하면 멈춤 명령까지 계속 움직입니다. 완료 콜백은 명령을 받았다는 뜻일 뿐입니다.
- 앱은 50ms마다 위치를 읽다가 목표나 시간 상한에서 멈춤 명령을 보냅니다.
- 착석은 `Seat.getCurrentSeatOccupancy`, 안전벨트는 `Seat.getCurrentSeatBeltBuckledState`로 읽습니다.
- **에뮬레이터 SDK는 시트 위치를 늘 0으로, 안전벨트를 늘 false로 돌려줍니다.** 어댑터는 에뮬레이터(`Build.HARDWARE` = `ranchu`)의 위치 0을 "읽지 못함"으로 바꿔 시트 연출을 건너뜁니다.
- debug 빌드의 시연 모드에서는 `EmulatorSeatState`가 에뮬레이터 안에서 Virtual Console(`127.0.0.1:8088/vehicle/state`)의 실제 위치·착석을 읽습니다. 벨트 정보는 없어 착석으로 대신합니다.

안전 조건은 [안전 설계](safety.md) 4장에 있습니다.

## 6. 권한

`:data` 매니페스트에 선언하고 앱 매니페스트로 합쳐집니다.

| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_CLIMATE` / `CONTROL_CAR_CLIMATE` | 공조 조회 / 제어 |
| `pleos.car.permission.CAR_LIGHTS` / `CONTROL_CAR_LIGHTS` | 조명 조회·설정 / 주행 모드 자동색 해제 |
| `pleos.car.permission.CAR_SEATS` / `CONTROL_CAR_SEATS` | 시트 열선·높이 |
| `pleos.car.permission.CAR_SAFETY` | 안전벨트 |
| `pleos.car.permission.CAR_DOORS` | 도어 상태 |
| `pleos.car.permission.TTS_SERVICE` / `STT_SERVICE` | TTS / STT |
| `android.car.permission.CAR_POWERTRAIN` | 기어, 주차 브레이크 |
| `android.car.permission.CAR_SPEED` | 속도 (런타임 요청) |
| `android.permission.RECORD_AUDIO` | 숨 불기·외치기 (런타임 요청) |

창문 권한은 release 빌드에 없습니다. debug 매니페스트에만 SDK 확인 화면용으로 둡니다.

## 7. 에뮬레이터에서 알아 둘 점

- Virtual Console의 `AVD`가 `Disabled`면 앱의 공조 명령이 **오류 없이 무시됩니다.** 탭을 가리면 3초 뒤 저절로 꺼집니다. 해결 방법은 [시작하기](getting-started.md) 3장에 있습니다.
- 무드등 스위치가 Virtual Console에서는 켜짐(`On`)으로 보이는데 SDK는 꺼짐(`OFF`)으로 돌려주는 경우가 있습니다. 앱은 SDK 값을 따르므로 첫 무드등 전환이 어둠에서 시작합니다.
- `IGNITION_STATE`는 에뮬레이터에 없습니다. 시동 상태는 판단 조건에 넣지 않습니다.
- 에뮬레이터는 user 빌드라 `cmd car_service get-property-value`가 막혀 있습니다. `dumpsys car_service`와 logcat으로 진단합니다.
