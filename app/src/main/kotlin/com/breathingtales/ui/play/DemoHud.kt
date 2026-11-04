package com.breathingtales.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.safety.SafetyMonitor
import com.breathingtales.domain.safety.SeatLiftOutcome
import com.breathingtales.ui.UiTexts
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.viewmodel.AvdStatus

/**
 * 시연 HUD (11). SafetyGuard가 마지막으로 처리한 제어를 「동화 요청 → 실제로 보낸 값」으로 보여 주고,
 * 정차 조건을 함께 띄운다. 부모 설정의 시연 모드(debug 빌드 전용)를 켰을 때만 재생 화면에 나온다.
 * 에뮬레이터에서는 Virtual Console AVD 상태([avd])를 맨 위에 띄운다. 꺼져 있으면 차량 명령이 오류 없이 무시되기 때문이다.
 */
@Composable
fun BoxScope.DemoHud(monitor: SafetyMonitor, vehicle: VehicleState, avd: AvdStatus? = null) {
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(24.dp)
            .width(620.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xD9131417))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        avd?.let { AvdBanner(it) }
        Text("안전 장치 · 동화 요청 → 차량에 보낸 값", style = Pleos.type.labelMedium, color = Color(0xFF9EA3AA))
        HudRow("정차", parkedText(vehicle))
        HudRow("강도", monitor.intensity?.let { "${UiTexts.intensity(it)} (허용 범위 × ${it.scale})" } ?: "-")
        HudRow("바람", monitor.fan?.let { fan ->
            val sent = fan.speed?.let { "풍량 $it" } ?: "보내지 않음"
            "${fan.requestedLevel}단 → $sent" + (fan.allowedMaxSpeed?.let { " (최대 $it)" } ?: "")
        })
        HudRow("풍향", monitor.airflow?.let { if (it == Airflow.FACE) "얼굴" else if (it == Airflow.FLOOR) "발" else it.name })
        HudRow("온도", monitor.temperature?.let { t ->
            val zones = t.zones.values.joinToString("  ") { (base, sent) -> "${c(base)}→${sent?.let(::c) ?: "그대로"}" }
            "${signed(t.requestedDeltaC)}℃ → $zones"
        })
        HudRow("열선", monitor.seatHeat?.let { "${it.requestedLevel}단 → ${it.level?.let { l -> "${l}단" } ?: "지원 안 함"}" })
        monitor.ambient?.let { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("조명")
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF000000 or a.colorRgb.toLong())))
                Value("  #%06X · %d%% → 밝기 %s".format(a.colorRgb, (a.requestedBrightness * 100).toInt(), a.brightness ?: "-"))
            }
        } ?: HudRow("조명", null)
        HudRow("시트", monitor.seatLift?.let(::seatLiftText))
        HudRow("실내등", monitor.cabinLight?.let(::lampText))
        HudRow("독서등", monitor.spotlight?.let(::lampText))
        Text("시트 위치·착석은 시연 모드에서 Virtual Console 값으로 읽어요 (에뮬레이터 SDK는 늘 0)", style = Pleos.type.labelSmall, color = Color(0xFF9EA3AA))
        monitor.restoreFailures?.let {
            HudRow("복원", if (it.isEmpty()) "시작 전 상태로 되돌림 ✓" else "되돌리지 못함: ${it.joinToString()}")
        }
    }
}

@Composable
private fun HudRow(label: String, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label)
        Value(value ?: "-")
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = Pleos.type.labelMedium, color = Color(0xFF9EA3AA), modifier = Modifier.width(72.dp))
}

@Composable
private fun Value(text: String) {
    Text(text, style = Pleos.type.labelMedium, color = Color.White)
}

private fun parkedText(v: VehicleState): String {
    fun mark(ok: Boolean?) = if (ok == true) "✓" else "✗"
    val gear = when (v.gear) {
        Gear.PARK -> "P"
        Gear.REVERSE -> "R"
        Gear.NEUTRAL -> "N"
        Gear.DRIVE -> "D"
        Gear.OTHER -> "?"
        null -> "-"
    }
    val kmh = v.speedMps?.let { "%.0fkm/h".format(it * 3.6f) } ?: "-"
    return "기어 $gear${mark(v.gear == Gear.PARK)}  $kmh${mark(v.speedMps == 0f)}  " +
        "도어${mark(v.doorsClosed)}  UX${mark(v.uxRestricted == false)}  주차브레이크 ${if (v.parkingBrakeOn == true) "ON" else "OFF"}"
}

@Composable
private fun AvdBanner(status: AvdStatus) {
    val (text, warning) = when (status) {
        AvdStatus.ON -> "AVD 켜짐 ✓ 차량 명령이 에뮬레이터에 전달돼요" to false
        AvdStatus.OFF -> "⚠ AVD 꺼짐: 차량 명령이 무시돼요. Virtual Console 창을 화면에 보이게 두세요" to true
        AvdStatus.UNREACHABLE -> "⚠ Virtual Console 상태를 읽지 못함 (에뮬레이터 안 127.0.0.1:8088). 차량 명령이 전달되는지 알 수 없어요" to true
    }
    Text(
        text,
        style = Pleos.type.labelMedium,
        color = if (warning) Color.White else Color(0xFF7FD48A),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (warning) Color(0xFFD9363E) else Color.Transparent)
            .padding(horizontal = if (warning) 12.dp else 0.dp, vertical = if (warning) 6.dp else 0.dp),
    )
}

private fun seatLiftText(lift: SafetyMonitor.SeatLiftControl): String {
    val positions = listOfNotNull(lift.start, lift.peak, lift.end).takeIf { it.size > 1 }?.joinToString(" → ")
    val count = if (lift.repeat > 0) "  ${lift.done}/${lift.repeat}번" else ""
    // 끝 위치를 시작 위치와 견줘 그대로 적는다 (같으면 ✓, 위면 몇 위인지, 아래면 경고)
    val endNote = lift.start?.let { start -> lift.end?.let { end -> start to end } }?.let { (start, end) ->
        when {
            end == start -> ""
            end > start -> " · 시작보다 ${end - start} 위에 멈춤"
            else -> " · ⚠ 시작보다 ${start - end} 아래로 내려감"
        }
    }.orEmpty()
    return seatLiftReason(lift.outcome) + count + (positions?.let { "  (높이 $it)" } ?: lift.start?.let { "  (높이 $it)" } ?: "") + endNote
}

private fun seatLiftReason(outcome: SeatLiftOutcome): String = when (outcome) {
    SeatLiftOutcome.DONE -> "조수석 들썩임, 시작 위치로 ✓"
    SeatLiftOutcome.DISABLED -> "건너뜀: 부모 설정에서 꺼짐"
    SeatLiftOutcome.ALREADY_USED -> "건너뜀: 이번 이야기에서 이미 함"
    SeatLiftOutcome.UNSUPPORTED -> "건너뜀: 이 차량은 지원 안 함"
    SeatLiftOutcome.NOT_OCCUPIED -> "건너뜀: 조수석 착석·벨트 없음"
    SeatLiftOutcome.POSITION_UNREADABLE -> "건너뜀: 위치를 읽을 수 없음"
    SeatLiftOutcome.AT_TOP -> "건너뜀: 이미 맨 위"
    SeatLiftOutcome.UNRESTORED -> "⚠ 제자리로 되돌리지 못함"
}

private fun lampText(lamp: SafetyMonitor.LampControl): String =
    (if (lamp.requestedOn) "켜기" else "끄기") + if (lamp.sent) " ✓" else " → 지원 안 함 (또는 시작 상태를 못 읽어 바꾸지 않음)"

private fun c(celsius: Float) = "%.1f".format(celsius)

private fun signed(v: Float) = (if (v > 0) "+" else "") + "%.1f".format(v)
