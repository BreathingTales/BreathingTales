# 개발자 문서

숨쉬는 동화의 구조와 동작 규칙을 설명합니다. 순서대로 읽지 않아도 됩니다. 필요한 문서만 골라 읽으세요.

| 문서 | 내용 |
|---|---|
| [시작하기](getting-started.md) | 개발 환경, 빌드, AAOS 에뮬레이터와 Virtual Console, Fake 차량 |
| [아키텍처](architecture.md) | 모듈 의존 구조, 패키지, 연출 한 번의 흐름, 재생 상태 전이 |
| [안전 설계](safety.md) | 연출 시작·중단 조건, 차량 제어 허용 범위, 상태 저장과 복원, 시트 움직임 규칙 |
| [동화 포맷](story-format.md) | `story.json` 스키마, 효과 이름과 값 범위, 무드등 전환, 새 동화 추가 방법 |
| [차량 연동](vehicle-integration.md) | 사용하는 Pleos·AAOS API, SDK 주의점, 권한, 에뮬레이터와 다른 점 |
| [빌드와 릴리스](build-release.md) | debug와 release 차이, 서명, 출품용 APK 확인 목록 |
| [테스트](testing.md) | 단위·계측 테스트, 손으로 확인하는 시나리오, 시연 모드, 문제 해결 |
| [설계 결정](decisions.md) | 주요 결정과 그 이유, 남은 과제 |
| [변경 기록](CHANGELOG.md) | 버전별 변경 사항 |

처음이라면 **시작하기 → 아키텍처 → 안전 설계** 순서를 추천합니다.
