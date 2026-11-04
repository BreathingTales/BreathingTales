package com.breathingtales.data.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 앱 전체가 함께 쓰는 Vehicle SDK 연결. 제어 어댑터와 도어 구독이 같은 연결을 쓴다 */
@Singleton
class PleosVehicle @Inject constructor(@ApplicationContext context: Context) {
    val vehicle: Vehicle = Vehicle(context.applicationContext).apply { initialize() }
}
