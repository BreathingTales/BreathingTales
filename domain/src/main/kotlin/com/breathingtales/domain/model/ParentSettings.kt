package com.breathingtales.domain.model

import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.usecase.PlaySettings

/** 부모 설정 (09). 앱을 다시 켜도 남는다 */
data class ParentSettings(
    /** 비어 있으면 홈 화면이 처음 한 번 묻는다 */
    val childName: String = "",
    val intensity: Intensity = Intensity.MEDIUM,
    val blowSensitivity: BlowSensitivity = BlowSensitivity.NORMAL,
    /** 「붕 떠오르는」 장면에서 앞좌석을 살짝 들썩일지 (기획서 5장 7번). 기본 꺼짐 */
    val seatMotion: Boolean = false,
    /** 시연 모드: 재생 화면에 SafetyGuard 제어 값(요청 → 보낸 값)을 띄운다. debug 빌드에서만 켤 수 있다 (11) */
    val demoHud: Boolean = false,
) {
    val hasChildName: Boolean get() = childName.isNotBlank()

    fun toPlaySettings() = PlaySettings(intensity = intensity, childName = childName.trim(), seatMotion = seatMotion)
}

/** 숨 불기 민감도. 마이크마다 음량이 달라 부모가 고른다. 실제 기준(dBFS)은 음성 어댑터가 정한다 */
enum class BlowSensitivity { LOW, NORMAL, HIGH }
