package com.breathingtales

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.breathingtales.data.vehicle.CarVehicleStateRepository
import com.breathingtales.ui.AppNavHost
import com.breathingtales.ui.theme.BreathingTalesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Fake 빌드에서는 만들지 않는다 (만들면 Car 서비스에 연결한다) */
    @Inject lateinit var carVehicleState: Provider<CarVehicleStateRepository>

    /**
     * - 마이크(RECORD_AUDIO): 없으면 듣기가 실패하고 대체 버튼만 동작한다 (08)
     * - 속도(CAR_SPEED): 없으면 속도를 몰라 정차로 보지 않는다. 받으면 다시 구독한다 (05)
     */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[CAR_SPEED] == true && !BuildConfig.FAKE_VEHICLE) carVehicleState.get().onPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = listOf(Manifest.permission.RECORD_AUDIO, CAR_SPEED)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())

        setContent {
            BreathingTalesTheme {
                AppNavHost()
            }
        }
    }

    private companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
