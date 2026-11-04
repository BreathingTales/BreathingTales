# 동화 포맷

숨쉬는 동화에서 **동화는 데이터이고, 앱은 엔진**입니다. 대사, 그림, 소리, 차량 연출, 아이 참여를 모두 `story.json` 한 파일에 적고, 앱은 그 파일을 읽어 재생합니다. 새 동화를 추가할 때 앱 코드는 고치지 않습니다.

## 1. 폴더 구조

```
app/src/main/assets/stories/
└── <id>/                    폴더 이름 = 동화 id
    ├── story.json
    ├── cover.webp           홈 목록 표지 (2:1)
    ├── scene_<장면>.webp    장면 그림
    ├── <화자>_<번호>.mp3     고정 대사 음원
    └── sfx_<이름>.mp3        효과음
```

- 앱은 `stories/` 아래에서 `story.json`이 있는 폴더를 모두 동화로 읽습니다(`AssetStoryRepository`).
- `story.json`의 `id`는 폴더 이름과 같아야 합니다.
- 파일 경로는 모두 **동화 폴더 기준 상대 경로**입니다.

## 2. 전체 구조

```json
{
  "id": "sun-and-wind",
  "title": "해님과 바람, 그리고 작은 차의 나그네",
  "ageRange": "4-7",
  "durationSec": 500,
  "cover": "cover.webp",
  "familyTalkCards": ["해님이랑 바람 중에, 누가 나그네 마음을 움직였을까?"],
  "scenes": [ ... ]
}
```

| 필드 | 필수 | 내용 |
|---|---|---|
| `id` | 예 | 폴더 이름과 같은 id |
| `title` | 예 | 제목 |
| `ageRange` | 예 | 권장 나이 (예: `"4-7"`) |
| `durationSec` | 예 | 대략적인 재생 시간(초). 홈 화면 표시용 |
| `cover` | | 표지 그림 |
| `familyTalkCards` | | 동화가 끝난 뒤 가족 대화 카드 문장. `{childName}`을 쓸 수 있음 |
| `scenes` | 예 | 장면 목록. 적힌 순서대로 재생 |

## 3. 장면

```json
{
  "id": "little-car",
  "image": "scene_little-car.webp",
  "lines": [ ... ],
  "effects": [ ... ],
  "interaction": { ... }
}
```

| 필드 | 필수 | 내용 |
|---|---|---|
| `id` | 예 | 동화 안에서 겹치지 않는 장면 id |
| `image` | | 장면 그림 |
| `lines` | * | 대사 목록. 차례대로 재생 |
| `effects` | | 장면 시작 기준 시각에 실행할 효과 |
| `interaction` | * | 대사가 끝난 뒤의 아이 참여 |

\* `lines`와 `interaction` 중 하나는 있어야 합니다.

### 진행 순서
1. 장면이 시작되면 **대사**와 **효과 시계**가 함께 출발합니다.
2. 대사를 차례대로 재생합니다. `pauseAfterSec`가 있으면 그만큼 쉽니다.
3. 대사가 끝나면 `interaction`이 있을 때 참여를 기다립니다.
4. 장면이 끝나면 그 시각까지 도달한 효과는 마저 실행하고, 그 뒤 시각의 효과는 버립니다.

효과는 대사와 별개로 `at` 시각에 나갑니다. 같은 시각의 효과는 적힌 순서대로 실행합니다. 부모가 장면을 건너뛰면 남은 효과음과 시트 움직임은 버리고, 나머지 차량 효과는 바로 적용해 장면 끝의 실내 상태로 맞춥니다.

## 4. 대사

```json
{ "speaker": "narrator", "audio": "narrator_01.mp3", "text": "옛날, 작은 마을 하나가 있었어요." }
{ "speaker": "narrator", "tts": true, "text": "{childName}, 반가워!", "pauseAfterSec": 2.5 }
```

| 필드 | 내용 |
|---|---|
| `speaker` | 화자 이름 (예: `narrator`, `sun`, `wind`) |
| `text` | 화면에 보이는 문장. 비어 있으면 안 됨 |
| `audio` | 미리 만든 음원 파일 |
| `tts` | `true`면 Pleos TTS로 읽음 |
| `pauseAfterSec` | 대사 뒤 쉬는 시간 0~10초. 아이가 흉내 낼 틈을 줌 |

**`audio`와 `tts`는 둘 중 하나만 씁니다.** 고정 대사는 미리 만든 음원으로, 아이 이름처럼 매번 달라지는 문장만 TTS로 읽습니다. TTS 문장의 `{childName}`은 부모 설정의 아이 이름으로 바뀝니다.

## 5. 효과

효과는 `type`으로 구분합니다. 장면 `effects`에서는 `at`(장면 시작 기준 초, 0 이상)을 함께 씁니다. 참여의 `onSuccess`, `onInhale`, `onExhale`에서는 `at`을 쓰지 않습니다.

값은 모두 **차량과 무관한 추상 값**입니다. 실제 차량 값으로 바꾸고 허용 범위로 줄이는 일은 `SafetyGuard`가 합니다([안전 설계](safety.md) 2장). 차량이 지원하지 않는 효과는 건너뜁니다.

| `type` | 필드 | 범위 | 내용 |
|---|---|---|---|
| `fanLevel` | `value` | 0~5 | 바람 세기. 0은 최저 풍량 |
| `fanLevelStep` | `value`, `max` | -5~5, 0~5 (기본 5) | 지금 바람 세기에서 단계만큼 바꿈. `max`를 넘지 않음 |
| `fanDirection` | `value` | `face`, `floor` | 풍향 |
| `temperatureDelta` | `value` | -2~2 (℃) | 시작 온도 기준 상대값 |
| `seatHeat` | `value` | 0~3 | 뒷좌석 열선. 실제로는 1단까지 |
| `seatLift` | `durationSec`, `repeat` | 0.1~1초, 1~3 (기본 1) | 조수석을 살짝 올렸다 되돌림. 조건은 [안전 설계](safety.md) 4장 |
| `ambient` | `color`, `brightness`, `fadeSec` | `#RRGGBB`, 0~1, 0 이상 | 무드등 색과 밝기. `fadeSec` 동안 서서히 바꿈 |
| `ambientBrightnessStep` | `value`, `max`, `fadeSec` | -1~1, 0~1 (기본 1), 0 이상 (기본 0) | 지금 밝기에서 더하거나 뺌. 앞에서 `ambient`로 색을 정한 뒤에만 동작 |
| `cabinLight` | `value` | `on`, `off` | 실내등. 시작에 꺼서 극장 불이 꺼지는 느낌을 줌 |
| `spotlight` | `value` | `on`, `off` | 뒷좌석 독서등 |
| `sound` | `audio`, `loop`, `volume` | 파일, 기본 `false`, 0~1 | 효과음·배경음 |
| `restore` | `fadeSec` | 0 이상 | 실내를 시작 전 상태로 되돌림. `fadeSec` 동안 무드등을 먼저 서서히 돌림 |

```json
{ "at": 0.0,  "type": "cabinLight", "value": "off" }
{ "at": 0.0,  "type": "ambient", "color": "#1E2A44", "brightness": 0.15, "fadeSec": 3.0 }
{ "at": 13.4, "type": "sound", "audio": "sfx_coat_rustle.mp3", "volume": 0.4 }
```

알아 둘 점:
- 모르는 `type`은 파싱 오류가 됩니다.
- `restore`를 실행하면 그 연출의 차량 제어가 끝납니다. 그 뒤의 차량 효과는 적용되지 않으므로 동화 마지막에만 씁니다. 동화가 끝나거나 중단되면 앱이 따로 복원하므로 `restore`를 꼭 적을 필요는 없습니다.

### 무드등 전환 (`fadeSec`)
`ambient`의 `fadeSec`는 무드등이 지금 색·밝기에서 새 색·밝기로 바뀌는 데 걸리는 시간(초)입니다. 0이면 바로 바뀝니다.

- Pleos SDK에 전환 시간을 받는 조명 API가 없어서, 앱이 0.1초마다 중간값을 보냅니다. 색은 R·G·B를 따로, 밝기는 차량 단위로 이어 갑니다.
- 출발점은 연출이 마지막으로 보낸 조명입니다. 아직 보낸 적이 없으면 시작 전 조명(켜져 있었을 때만), 그것도 없으면 목표 색의 가장 어두운 밝기에서 출발합니다.
- 전환은 따로 진행되어 같은 시각의 다른 효과를 기다리게 하지 않습니다.
- 일시정지 중에는 전환도 멈추고, 재개하면 남은 시간만큼 이어 갑니다.
- 전환 중에 다음 `ambient`나 `ambientBrightnessStep`이 오면 이전 전환을 끊고 새 효과를 따릅니다. 밝기 단계는 진행 중인 전환의 **목표** 밝기에 더합니다.
- `ambientBrightnessStep`에도 `fadeSec`를 쓸 수 있습니다. 0이면 바로, 있으면 지금 보이는 밝기에서 새 밝기로 서서히 바꿉니다. 숨 맞추기에서 들이쉬는 동안 천천히 밝아지게 할 때 씁니다.
- 장면을 건너뛰면 남은 조명 효과는 전환 없이 바로 적용합니다.
- 정차가 깨지면 전환을 멈추고 바로 복원합니다.

`restore`의 `fadeSec`는 무드등을 시작 전 조명으로 서서히 돌린 뒤(시작 전에 꺼져 있었다면 가장 어둡게 낮춘 뒤) 나머지를 복원합니다. 공조·시트 같은 다른 항목은 전환 없이 복원합니다.

## 6. 아이 참여

대사가 끝난 뒤 아이가 외치거나 숨을 불면 효과가 나옵니다.

```json
"interaction": {
  "type": "shout",
  "keywords": ["준비 완료", "완료"],
  "onSuccess": [
    { "type": "sound", "audio": "sfx_ref_whistle.mp3", "volume": 0.6 },
    { "type": "ambientBrightnessStep", "value": 0.15, "max": 0.6 }
  ],
  "fallbackButton": "준비 완료!",
  "timeoutSec": 15,
  "repeat": 1
}
```

| 필드 | 내용 |
|---|---|
| `type` | `shout`(외치기, 음성 인식 키워드), `blow`(숨 불기, 마이크 음량), `shoutOrBlow`(둘 중 하나), `breathe`(숨 맞추기) |
| `keywords` | 인식할 단어. `shout`, `shoutOrBlow`에 필요 |
| `onSuccess` | 성공할 때마다 실행할 효과 |
| `fallbackButton` | 늘 함께 보이는 터치 버튼 문구. 인식이 안 될 때 대신 누름 |
| `timeoutSec` | 기다리는 시간(1 이상). 지나면 다음 장면으로 |
| `repeat` | 몇 번 성공하면 넘어갈지 (기본 1) |
| `breath` | `breathe` 전용 |

`repeat`번 성공하거나 `timeoutSec`이 지나면 다음 장면으로 넘어갑니다. 인식이 안 돼도 이야기가 멈추지 않습니다.

### 숨 맞추기 (`breathe`)
듣지 않고, 박자에 맞춰 빛과 바람을 바꾸며 아이의 숨쉬기를 이끕니다.

```json
"interaction": {
  "type": "breathe",
  "fallbackButton": "그만하기",
  "repeat": 3,
  "breath": {
    "inhaleSec": 4, "exhaleSec": 4,
    "onInhale": [{ "type": "ambientBrightnessStep", "value": 0.08, "fadeSec": 3.5 }],
    "onExhale": [{ "type": "fanLevel", "value": 2 }]
  }
}
```

- 한 번 들이쉬고 내쉴 때마다 성공 한 번으로 셉니다. `repeat`은 최대 5입니다.
- `inhaleSec`, `exhaleSec`은 1~10초입니다.
- `timeoutSec`은 쓰지 않고, `fallbackButton`은 "그만하기" 버튼이 됩니다.

## 7. 검증

앱은 동화를 읽을 때 형식(`StoryParser`)과 내용(`StoryValidator`)을 검사합니다. 범위 밖 값, 없는 파일, 겹치는 장면 id 같은 문제를 **한 번에 모아** `InvalidStoryException`으로 알립니다. 문제 하나를 고칠 때마다 다시 실행하지 않아도 됩니다.

여기서 검사하는 범위는 "동화가 요청할 수 있는 값"입니다. 차량에 맞춰 줄이는 일은 재생 중에 `SafetyGuard`가 따로 합니다.

## 8. 새 동화 추가하기

1. `app/src/main/assets/stories/<id>/` 폴더를 만듭니다.
2. `story.json`을 쓰고 그림·음원·효과음을 같은 폴더에 둡니다.
3. 앱을 실행하면 홈 목록에 나타납니다. 검증에 걸리면 목록에 나타나지 않고, 홈 화면에 "동화 파일에 문제가 있어요 (N건)"이 표시됩니다. 문제 목록 전체는 `InvalidStoryException.issues`에 담겨 있습니다.

이 저장소의 「해님과 바람」은 대본 원본에서 `story.json`을 만들고, 대사 음원은 Qwen3-TTS로 미리 만들었습니다. 이 제작 도구는 로컬 전용이라 저장소에 없고, 저장소에는 결과물만 있습니다. 직접 `story.json`을 써도 앱은 똑같이 동작합니다.
