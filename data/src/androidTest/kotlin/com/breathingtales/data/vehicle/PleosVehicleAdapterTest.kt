package com.breathingtales.data.vehicle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breathingtales.domain.model.Airflow
import com.breathingtales.domain.model.AmbientMode
import com.breathingtales.domain.model.CabinSnapshot
import com.breathingtales.domain.model.Effect
import com.breathingtales.domain.model.Gear
import com.breathingtales.domain.model.LampSwitch
import com.breathingtales.domain.model.VehicleState
import com.breathingtales.domain.repository.VehicleStateRepository
import com.breathingtales.domain.safety.Intensity
import com.breathingtales.domain.safety.ParkedRule
import com.breathingtales.domain.safety.ParkedState
import com.breathingtales.domain.safety.SafetyGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pleos Connect Emulator에서 실제 SDK로 확인한다. 준비: 에뮬레이터 실행, Virtual Console 상단 `AVD` = `Enabled`,
 * 기어 P·문 닫힘 (docs/dev/02_기술_확인.md 7장). 실행: `./gradlew :data:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class PleosVehicleAdapterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pleos = PleosVehicle(context)
    private val adapter = PleosVehicleAdapter(pleos, EmulatorSeatState { false })

    /** 정차 판단은 따로 확인하고, 여기서는 제어만 본다 */
    private val parked = object : VehicleStateRepository {
        override val state = MutableStateFlow(
            VehicleState(uxRestricted = false, gear = Gear.PARK, speedMps = 0f, doorsClosed = true),
        )
    }

    @Test
    fun capabilities_match_02_findings() = runBlocking {
        val caps = adapter.capabilities()
        assertEquals(1..8, caps.fanSpeedRange)
        assertEquals(0.5f, caps.temperatureStepC)
        assertEquals(0..100, caps.ambientBrightnessRange)
        assertEquals(2, caps.seatHeatZones.size)
        assertEquals(3, caps.seatHeatMaxLevel)
        // 10 대본 재작성: 실내등(켜기·끄기), 뒷좌석 독서등 2곳
        assertTrue(caps.cabinLight)
        assertEquals(2, caps.spotlightZones.size)
    }

    /**
     * 무드등 전환(fadeSec): SDK에 전환 기능이 없어 엔진이 0.1초마다 중간값을 보낸다. 그 빈도로 보내도
     * 호출이 밀리지 않고 끝값이 반영되는지 본다. 호출 시간은 logcat `BTFade`에 남는다.
     */
    @Test
    fun ambient_fade_frames() = runBlocking {
        val before = adapter.readSnapshot()
        android.util.Log.i("BTFade", "before mode=${before.ambientMode} color=${before.ambientColorRgb?.let { "%06X".format(it) }} brightness=${before.ambientBrightness}")
        val guard = SafetyGuard(adapter, parked)
        assertTrue(guard.begin(Intensity.HIGH))
        val target = Effect.Ambient(0x0000FF, 0.8f, fadeSec = 3f)
        val costs = mutableListOf<Long>()
        var restored: List<String>? = null
        try {
            val fade = guard.ambientFade(target)!!
            android.util.Log.i("BTFade", "from=${fade.from} to=${fade.to}")
            val start = System.nanoTime()
            var progress = 0f
            while (progress < 1f) {
                progress = ((System.nanoTime() - start) / 3e9).toFloat().coerceAtMost(1f)
                val t0 = System.nanoTime()
                assertTrue(guard.applyAmbientFade(fade, progress))
                costs += (System.nanoTime() - t0) / 1_000_000
                delay(100)
            }
            android.util.Log.i("BTFade", "frames=${costs.size} max=${costs.max()}ms avg=${costs.average().toInt()}ms all=$costs")
            eventually("전환 끝값 반영") {
                val now = adapter.readSnapshot()
                now.ambientColorRgb?.and(0xFFFFFF) == 0x0000FF && now.ambientBrightness == fade.to.brightness
            }
        } finally {
            restored = guard.restore()
        }
        assertEquals(emptyList<String>(), restored)
        assertTrue("호출 시간이 간격(100ms)을 넘음: $costs", costs.max() < 100)
    }

    @Test
    fun lights_change_restore() = runBlocking {
        val before = adapter.readSnapshot()
        assertTrue("실내등 시작 상태: ${before.cabinLight}", before.cabinLight != null)
        assertEquals(2, before.readingLights.size)
        val guard = SafetyGuard(adapter, parked)
        assertTrue(guard.begin(Intensity.HIGH))
        val cabinTarget = before.cabinLight != LampSwitch.ON
        var restored: List<String>? = null
        try {
            assertTrue(guard.apply(Effect.CabinLight(cabinTarget)))
            assertTrue(guard.apply(Effect.Spotlight(true)))
            eventually("조명 반영") {
                val now = adapter.readSnapshot()
                now.cabinLight == (if (cabinTarget) LampSwitch.ON else LampSwitch.OFF) &&
                    now.readingLights.values.all { it == LampSwitch.ON }
            }
        } finally {
            restored = guard.restore()
        }
        assertEquals(emptyList<String>(), restored)
        eventually("조명 복원 (시작 전: $before)") {
            val now = adapter.readSnapshot()
            now.cabinLight == before.cabinLight && now.readingLights == before.readingLights
        }
    }

    @Test
    fun snapshot_change_restore() = runBlocking {
        val before = adapter.readSnapshot()
        val guard = SafetyGuard(adapter, parked)
        assertTrue(guard.begin(Intensity.HIGH))
        // 확인이 실패해도 차량을 연출 상태로 남기지 않는다. 엔진(07)도 같은 이유로 finally에서 복원한다
        var restored: List<String>? = null
        try {
            assertTrue(guard.apply(Effect.Ambient(0x4A90E2, 0.6f)))
            assertTrue(guard.apply(Effect.FanLevel(5)))
            assertTrue(guard.apply(Effect.FanDirection(Airflow.FACE)))
            // 기준 온도가 20℃(하한)면 낮출 수 없으므로 바꿀 수 있는 방향으로 요청한다
            val delta = if (before.temperaturesC.values.all { it <= 23f }) 2f else -2f
            assertTrue("기준 온도: ${before.temperaturesC}", guard.apply(Effect.TemperatureDelta(delta)))
            assertTrue(guard.apply(Effect.SeatHeat(3)))

            eventually("연출 값 반영") {
                val now = adapter.readSnapshot()
                now.fanSpeed == 6 && now.airflow == Airflow.FACE && now.ambientMode == AmbientMode.ON &&
                    now.ambientColorRgb == 0x4A90E2 && now.ambientBrightness == 60 &&
                    now.seatHeatLevels.values.all { it == 1 }
            }
        } finally {
            restored = guard.restore()
        }
        assertEquals(emptyList<String>(), restored)
        eventually("시작 전 상태로 복원 (시작 전: $before)") { adapter.readSnapshot() == before }
    }

    @Test
    fun parked_state_is_read_from_aaos_and_door() = runBlocking {
        // 속도는 런타임 권한이라 설치만으로는 허용되지 않는다. 앱에서는 09 UI가 요청한다
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.car.permission.CAR_SPEED")
        val repository = CarVehicleStateRepository(context, pleos)
        val state = withTimeoutOrNull(10_000) {
            repository.state.first { it.gear != null && it.speedMps != null && it.doorsClosed != null && it.uxRestricted != null }
        } ?: fail("10초 안에 차량 상태를 모두 받지 못함: ${repository.state.value}")
        assertEquals("$state", ParkedState.Parked, ParkedRule.evaluate(state as VehicleState))
    }

    private suspend fun eventually(what: String, condition: suspend () -> Boolean) {
        repeat(25) {
            if (condition()) return
            delay(200)
        }
        val last: CabinSnapshot = adapter.readSnapshot()
        fail("$what: 5초 안에 확인되지 않음. 현재 $last")
    }
}
