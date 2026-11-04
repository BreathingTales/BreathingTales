package com.breathingtales.ui.debug

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import com.breathingtales.BuildConfig

/** debug 빌드: `-PfakeVehicle=true`면 Fake 차량 조작 패널을 띄운다 */
@Composable
fun BoxScope.DebugOverlay() {
    if (BuildConfig.FAKE_VEHICLE) FakeVehicleOverlay()
}
