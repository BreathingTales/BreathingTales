package com.breathingtales.ui.play

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.port.SeatMove
import com.breathingtales.domain.safety.SafetyMonitor
import com.breathingtales.domain.safety.SeatLiftOutcome
import com.breathingtales.ui.UiTexts
import com.breathingtales.ui.theme.Pleos
import com.breathingtales.viewmodel.AvdStatus
import kotlin.math.max
import kotlin.math.min

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
        monitor.seatLift?.let { SeatLiftRows(it) } ?: HudRow("시트", null)
        HudRow("실내등", monitor.cabinLight?.let(::lampText))
        HudRow("독서등", monitor.readingLight?.let(::lampText))
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

/**
 * 시트 들썩임. 맨 윗줄은 진행·결과, 그 아래 축(높이·방석 기울기)마다 게이지 한 줄.
 * 게이지는 시작 위치(흰 눈금)~올릴 상한(밝은 칸) 사이에서 지금 위치가 어디인지 보여 준다.
 * 움직이는 동안은 위치를 읽을 때마다(50ms) 갱신된다
 */
@Composable
private fun SeatLiftRows(lift: SafetyMonitor.SeatLiftControl) {
    val count = if (lift.repeat > 0) "  ${lift.done}/${lift.repeat}번" else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("시트")
        Text(
            seatLiftReason(lift.outcome) + count,
            style = Pleos.type.labelMedium,
            color = when (lift.outcome) {
                SeatLiftOutcome.IN_PROGRESS -> SeatColors.up
                SeatLiftOutcome.UNRESTORED -> SeatColors.warn
                else -> Color.White
            },
        )
    }
    lift.height?.let { SeatAxisRow("높이", it, lift.outcome) }
    lift.tilt?.let { SeatAxisRow("기울기", it, lift.outcome) }
}

private object SeatColors {
    val track = Color(0x33FFFFFF)
    val band = Color(0x407FD48A)
    val up = Color(0xFFFFB547)
    val down = Color(0xFF5AB4FF)
    val ok = Color(0xFF7FD48A)
    val warn = Color(0xFFFF5A5F)
}

@Composable
private fun SeatAxisRow(name: String, axis: SafetyMonitor.SeatAxisTrace, outcome: SeatLiftOutcome) {
    val target = axis.target
    // 건너뛴 경우는 시작 위치만 있다
    if (target == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(72.dp))
            Text("$name ${axis.start}", style = Pleos.type.labelSmall, color = Color(0xFF9EA3AA))
        }
        return
    }
    val moving = outcome == SeatLiftOutcome.IN_PROGRESS
    val now = axis.now ?: axis.end ?: axis.start
    val outOfRange = now < axis.start || now > target
    val color = when {
        outOfRange -> SeatColors.warn
        moving && axis.move == SeatMove.UP -> SeatColors.up
        moving && axis.move == SeatMove.DOWN -> SeatColors.down
        else -> SeatColors.ok
    }
    val shown by animateFloatAsState(now.toFloat(), tween(durationMillis = 60, easing = LinearEasing), label = "seat-$name")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(72.dp))
        Text(name, style = Pleos.type.labelSmall, color = Color(0xFF9EA3AA), modifier = Modifier.width(64.dp))
        SeatGauge(axis.start, target, axis.peak, shown, color, Modifier.width(200.dp).height(18.dp))
        Spacer(Modifier.width(14.dp))
        if (moving) {
            // 멈춤은 STOP 뒤 멈춘 위치를 확인하는 사이 (올리고 내리는 중간에 잠깐씩 나온다)
            val phase = when (axis.move) {
                SeatMove.UP -> "올림 ▲"
                SeatMove.DOWN -> "되돌림 ▼"
                SeatMove.STOP -> "멈춤"
            }
            Text("$now", style = Pleos.type.labelMedium, color = color, modifier = Modifier.width(34.dp))
            Text(phase, style = Pleos.type.labelSmall, color = color, modifier = Modifier.width(66.dp))
            Text("범위 ${axis.start}~$target", style = Pleos.type.labelSmall, color = Color(0xFF9EA3AA), maxLines = 1)
        } else {
            val path = listOfNotNull(axis.start, axis.peak, axis.end).joinToString(" → ")
            // 끝 위치를 시작 위치와 견줘 그대로 적는다 (같으면 ✓, 위면 몇 위인지, 아래면 경고)
            val note = axis.end?.let { end ->
                when {
                    end == axis.start -> " ✓"
                    end > axis.start -> " · 시작보다 ${end - axis.start} 위"
                    else -> " · ⚠ 시작보다 ${axis.start - end} 아래"
                }
            }.orEmpty()
            Text(path + note, style = Pleos.type.labelMedium, color = if (outOfRange) SeatColors.warn else Color.White)
        }
    }
}

/** 시작 위치 아래·상한 위도 조금 보이게 그려, 지나치면 빨갛게 바깥으로 나간 것이 보인다 */
@Composable
private fun SeatGauge(start: Int, target: Int, peak: Int?, now: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val pad = max(1f, (target - start) / 4f)
        val lo = start - pad
        val hi = target + pad
        fun x(v: Float) = ((v.coerceIn(lo, hi) - lo) / (hi - lo)) * size.width
        val barH = size.height * 0.5f
        val top = (size.height - barH) / 2
        val r = CornerRadius(barH / 2)
        drawRoundRect(SeatColors.track, Offset(0f, top), Size(size.width, barH), r)
        drawRoundRect(SeatColors.band, Offset(x(start.toFloat()), top), Size(x(target.toFloat()) - x(start.toFloat()), barH), r)
        // 지금까지 가장 높이 간 데까지 옅게 남긴다 (끝난 뒤에도 얼마나 움직였는지 보인다)
        peak?.let { drawRoundRect(color.copy(alpha = 0.3f), Offset(x(start.toFloat()), top), Size(x(it.toFloat()) - x(start.toFloat()), barH), r) }
        // 시작에서 지금 위치까지 채운다
        val a = min(x(start.toFloat()), x(now))
        val b = max(x(start.toFloat()), x(now))
        drawRoundRect(color.copy(alpha = 0.85f), Offset(a, top), Size(b - a, barH), r)
        peak?.let { drawLine(Color(0x99FFFFFF), Offset(x(it.toFloat()), top - 2f), Offset(x(it.toFloat()), top + barH + 2f), strokeWidth = 2f) }
        drawLine(Color.White, Offset(x(start.toFloat()), 0f), Offset(x(start.toFloat()), size.height), strokeWidth = 3f)
        drawLine(Color(0x80FFFFFF), Offset(x(target.toFloat()), 0f), Offset(x(target.toFloat()), size.height), strokeWidth = 2f)
        drawCircle(color, radius = size.height / 2.6f, center = Offset(x(now), size.height / 2))
    }
}

private fun seatLiftReason(outcome: SeatLiftOutcome): String = when (outcome) {
    SeatLiftOutcome.IN_PROGRESS -> "조수석 들썩이는 중"
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
