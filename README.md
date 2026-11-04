# 숨쉬는 동화 (Breathing Tales)

> 온 가족이 함께 느끼는 오감 동화

차가 멈춰 있는 동안 차량 자체가 동화의 무대가 됩니다. 바람 장면에서는 송풍구에서 바람이 불고, 해님 장면에서는 실내가 따뜻한 빛과 온기로 채워집니다.
Pleos(AAOS) 차량용 앱으로, 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 문서
- [개발자 문서](developer/README.md): 구조, 안전 설계, 동화 포맷, 빌드
- [안전 설계](developer/safety.md): 정차 조건, 차량 제어 허용 범위, 복원, 시트 움직임 규칙
- [변경 기록](developer/CHANGELOG.md)

## 빌드
요구 사항: JDK 17 이상, Android SDK Platform 37

```
./gradlew assembleDebug
./gradlew test
./gradlew assembleRelease
```
릴리스 서명 키(`keystore.properties`, `keystore/`)는 저장소에 없습니다. 없으면 서명되지 않은 릴리스 APK가 만들어집니다.

## 콘텐츠 출처
- 음성: Qwen3-TTS (Apache-2.0, 글로 설계한 목소리. 「해님과 바람, 그리고 작은 차의 나그네」 대사 음원)
- 그림: FLUX.1-schnell (Apache-2.0)로 생성한 장면 일러스트 12장과 표지. 실제 차종·기존 작품 화풍을 흉내 내지 않도록 프롬프트로 지정
- 아이 이름 부르기: Pleos TTS
- 효과음: 숨쉬는 동화 팀 자체 제작 (직접 합성)
- 대본·음원 제작 도구는 저장소에 넣지 않았습니다. 앱이 쓰는 결과물은 `app/src/main/assets/stories/`에 있습니다.
- 「해와 바람」은 이솝 우화(공유 저작물)를 새로 각색했습니다.

## 구조
| 모듈 | 역할 |
|---|---|
| `:domain` | 순수 Kotlin. 동화·효과 모델, 안전 정책(SafetyGuard), 유스케이스 |
| `:data` | Pleos Vehicle/TTS/STT SDK, AAOS 주행 제한, Media3, 동화 JSON 파싱 |
| `:app` | Jetpack Compose UI, ViewModel, Hilt DI |
