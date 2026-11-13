# 빌드와 릴리스

## 1. 빌드 명령

```
./gradlew assembleDebug                          # debug APK (Pleos 어댑터)
./gradlew assembleDebug -PfakeVehicle=true       # debug APK (Fake 차량)
./gradlew assembleRelease                        # release APK
```

결과물은 `app/build/outputs/apk/<빌드 종류>/`에 생깁니다.

## 2. debug와 release 차이

| 항목 | debug | release |
|---|---|---|
| 차량 구현 | Pleos 어댑터, `-PfakeVehicle=true`면 Fake | 항상 Pleos 어댑터 |
| Fake 차량·조작 패널 | 포함 (`src/debug`) | 없음 |
| 확인용 화면 `ProbeActivity` | 포함 | 없음 |
| 시연 모드 (안전 장치 HUD) | 부모 설정에서 켤 수 있음 | 늘 꺼짐. 토글이 없고, 저장된 설정과 관계없이 `BuildConfig.DEBUG`로 막음 |
| 에뮬레이터 시트 값 읽기 (Virtual Console) | 에뮬레이터에서만 | 같음 (에뮬레이터에서만. 실차에서는 꺼짐) |
| 창문·`CAR_DRIVE` 권한 | 확인용 화면 때문에 있음 | 없음 |
| 서명 | 디버그 키 | 로컬 릴리스 키 (아래) |
| 난독화 | 없음 | 없음 (`isMinifyEnabled = false`) |

debug 전용 코드는 `src/debug` 소스 세트에만 둡니다. 같은 클래스의 release 쪽 구현(`VehicleBindingModule`, `DebugOverlay`)은 `src/release`에 따로 있습니다.

## 3. 릴리스 서명

서명 키는 저장소에 없습니다. `keystore.properties`와 `keystore/`는 git에서 제외되어 있어 각자 로컬에 둡니다.

- 루트에 `keystore.properties`가 있으면 그 키로 서명합니다.
- 없으면 **서명되지 않은** release APK가 만들어집니다. 빌드는 실패하지 않습니다.

`keystore.properties` 형식:
```properties
storeFile=keystore/<파일 이름>.jks
storePassword=<비밀번호>
keyAlias=<별칭>
keyPassword=<비밀번호>
```

`storeFile`은 저장소 루트 기준 경로입니다. 키를 잃어버리면 같은 서명으로 업데이트할 수 없으므로 따로 백업해 두세요.

서명 확인:
```
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 4. 버전

`app/build.gradle.kts`의 `versionCode`, `versionName`에서 관리합니다. 바꾸면 [변경 기록](CHANGELOG.md)에도 적습니다.

라이브러리 버전은 `gradle/libs.versions.toml` 한 곳에서만 관리합니다. AGP는 Android Studio가 지원하는 최고 버전에 맞춰 두었습니다.

## 5. 출품용 APK 만들기 전 확인

- [ ] `./gradlew test` 통과
- [ ] 에뮬레이터에서 처음부터 끝까지 재생하고, 끝난 뒤 실내가 시작 전 값으로 돌아왔는지 `curl http://127.0.0.1:8088/vehicle/state`로 확인
- [ ] 연출 중 기어 D, 도어 열기에서 즉시 중단·복원되는지 확인
- [ ] `./gradlew assembleRelease` 후 서명 확인
- [ ] release APK에 Fake·확인용 화면 코드가 없는지 확인 (예: `apkanalyzer dex packages app-release.apk | grep -i fake`)
- [ ] 최종 권한에 창문 권한이 없는지 확인 (`apkanalyzer manifest permissions app-release.apk`)
- [ ] `versionCode`·`versionName`을 올리고 [변경 기록](CHANGELOG.md) 작성
- [ ] 서명된 APK를 `apk/BreathingTales_BreathingTales_release.apk`로 복사 (공모전 제출 규정: `팀명_앱이름_release.apk`, 저장소의 `apk/` 폴더). `.gitignore`는 `apk/` 안의 APK만 추적합니다
- [ ] 에뮬레이터에 release APK를 설치해 실행 확인 (서명이 다른 debug 앱이 있으면 먼저 지움)
