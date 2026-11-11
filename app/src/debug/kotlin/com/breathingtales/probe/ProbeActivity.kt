package com.breathingtales.probe

import android.Manifest
import com.breathingtales.data.voice.MicCapture
import com.breathingtales.data.voice.MicVoiceInput
import com.breathingtales.data.voice.PleosStt
import com.breathingtales.domain.model.InteractionType
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import com.breathingtales.ui.theme.BreathingTalesTheme
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * 02 기술 확인 화면 (debug 빌드 전용 런처).
 * 버튼 하나가 API 하나를 호출하고, 결과는 오른쪽 로그와 logcat `BTProbe` 태그에 남는다.
 */
class ProbeActivity : ComponentActivity() {
    private val log = ProbeLog()
    private lateinit var vehicle: VehicleProbe
    private lateinit var carApi: CarApiProbe
    private lateinit var voice: VoiceProbe
    private lateinit var seatMove: SeatMoveProbe
    private lateinit var extras: CabinExtrasProbe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = VehicleProbe(this, log, lifecycleScope)
        carApi = CarApiProbe(this, log)
        voice = VoiceProbe(this, log, lifecycleScope)
        seatMove = SeatMoveProbe(this, log, lifecycleScope)
        seatMove.initialize()
        extras = CabinExtrasProbe(this, log, lifecycleScope)
        extras.initialize()
        if (intent.getBooleanExtra("extrasCheck", false)) extras.runAll()
        if (intent.getBooleanExtra("seatCheck", false)) seatMove.runAll()
        if (intent.getBooleanExtra("seatNoStop", false)) seatMove.runNoStop()
        if (intent.getBooleanExtra("seatRead", false)) seatMove.runReadOnly()
        vehicle.initialize()

        setContent {
            BreathingTalesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        vehicle.release()
        seatMove.release()
        extras.release()
        carApi.disconnect()
        voice.release()
        super.onDestroy()
    }

    private fun missingPermissions(): Array<String> {
        val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        return declared.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    @Composable
    private fun ProbeScreen() {
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            result.forEach { (name, granted) -> log.add("권한", "${name.substringAfterLast('.')}: ${if (granted) "허용" else "거부"}") }
            prepare()
        }
        LaunchedEffect(Unit) {
            val missing = missingPermissions()
            log.add("권한", "미허용 ${missing.size}개: ${missing.joinToString { it.substringAfterLast('.') }}")
            if (missing.isNotEmpty()) permissionLauncher.launch(missing) else prepare()
        }

        Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                modifier = Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Controls()
            }
            Column(modifier = Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveState()
                LogPanel(Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun Controls() {
        Section("0. 준비 (화면을 열면 자동 실행, 다시 하고 싶을 때만 누름)") {
            Button(onClick = ::logPermissionStatus) { Text("권한 상태") }
            Button(onClick = vehicle::checkCapabilities) { Text("지원 범위 확인") }
            Button(onClick = vehicle::readAll) { Text("현재값 모두 읽기") }
            Button(onClick = vehicle::registerListeners) { Text("기어·도어 구독") }
            Button(onClick = carApi::connect) { Text("AAOS 연결") }
        }
        Section("1. 공조 (바람·온도)") {
            OutlinedButton(onClick = { vehicle.setHvacPower(true) }) { Text("공조 켜기") }
            listOf(1, 3, 5, 8).forEach { level -> OutlinedButton(onClick = { vehicle.setFanSpeed(level) }) { Text("풍량 $level") } }
            OutlinedButton(onClick = { vehicle.setFanDirection(FanDirectionType.FACE) }) { Text("풍향 얼굴") }
            OutlinedButton(onClick = { vehicle.setFanDirection(FanDirectionType.FLOOR) }) { Text("풍향 발") }
            OutlinedButton(onClick = { vehicle.shiftTemperature(-2f) }) { Text("온도 -2℃") }
            OutlinedButton(onClick = { vehicle.shiftTemperature(+2f) }) { Text("온도 +2℃") }
        }
        Section("2. 앰비언트 라이트 (빛·색)") {
            OutlinedButton(onClick = { vehicle.setAmbientSwitch(true) }) { Text("스위치 ON") }
            OutlinedButton(onClick = vehicle::disableAmbientAutoByDriveMode) { Text("주행모드 자동색 끄기") }
            OutlinedButton(onClick = { vehicle.setAmbient(WIND_BLUE, 60) }) { Text("바람 파랑") }
            OutlinedButton(onClick = { vehicle.setAmbient(SUN_ORANGE, 20) }) { Text("해님 주황 어둡게") }
            OutlinedButton(onClick = { vehicle.setAmbient(SUN_ORANGE, 100) }) { Text("해님 주황 밝게") }
            OutlinedButton(onClick = { vehicle.setAmbient(SOFT_WHITE, 40) }) { Text("은은한 흰색") }
        }
        Section("3. 시트 열선 (뒷좌석)") {
            OutlinedButton(onClick = { vehicle.setRearSeatHeat(1) }) { Text("열선 1단") }
            OutlinedButton(onClick = { vehicle.setRearSeatHeat(0) }) { Text("열선 끄기") }
            OutlinedButton(onClick = { seatMove.runAll() }) { Text("시트 높이·기울기 이동 확인") }
            OutlinedButton(onClick = { extras.runAll() }) { Text("통풍·실내등·독서등·스마트 글라스 확인") }
        }
        Section("4. 스냅샷·복원") {
            OutlinedButton(onClick = vehicle::takeSnapshot) { Text("현재 상태 저장") }
            OutlinedButton(onClick = vehicle::restoreSnapshot) { Text("저장 상태로 복원") }
        }
        VoiceControls()
    }

    @Composable
    private fun VoiceControls() {
        var ttsText by remember { mutableStateOf("안녕, 하늘아! 오늘은 우리 차가 이야기 극장이 될 거야.") }
        var speaker by remember { mutableStateOf("seoyoon") } // 에뮬레이터 시스템 앱(ambientai)이 쓰는 목소리. male, 1은 key not found
        val micDb by voice.micLevelDb.collectAsStateWithLifecycle()

        Section("5. TTS (이름 부르기)") {
            OutlinedButton(onClick = { voice.initTts(TtsMode.ON_DEVICE) }) { Text("초기화 ON_DEVICE") }
            OutlinedButton(onClick = { voice.initTts(TtsMode.HYBRID) }) { Text("초기화 HYBRID") }
            OutlinedButton(onClick = { voice.speak(ttsText, speaker) }) { Text("말하기") }
            OutlinedButton(onClick = voice::stopTts) { Text("멈춤") }
        }
        OutlinedTextField(value = ttsText, onValueChange = { ttsText = it }, label = { Text("TTS 문장") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = speaker, onValueChange = { speaker = it }, label = { Text("speakerType (값 미공개, 시험 입력)") }, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { voice.setSpeakerType(speaker) }) { Text("적용") }
        }

        Section("6. STT (외치기: 바람아 불어라 / 해님 반짝)") {
            OutlinedButton(onClick = { voice.initStt(SttMode.ON_DEVICE) }) { Text("초기화 ON_DEVICE") }
            OutlinedButton(onClick = { voice.initStt(SttMode.HYBRID) }) { Text("초기화 HYBRID") }
            OutlinedButton(onClick = voice::startStt) { Text("인식 시작") }
            OutlinedButton(onClick = voice::stopStt) { Text("인식 멈춤") }
        }

        Section("7. 숨 불기 (마이크 음량)") {
            OutlinedButton(onClick = ::startMicIfAllowed) { Text("마이크 시작") }
            OutlinedButton(onClick = voice::stopMic) { Text("마이크 멈춤") }
        }
        Section("8. 참여 듣기 (08 MicVoiceInput, 30초)") {
            OutlinedButton(onClick = { listenFor(InteractionType.SHOUT_OR_BLOW, listOf("바람아 불어라", "바람아")) }) { Text("외치기+불기") }
            OutlinedButton(onClick = { listenFor(InteractionType.SHOUT, listOf("해님 반짝")) }) { Text("외치기만") }
            OutlinedButton(onClick = { listenJob?.cancel() }) { Text("멈춤") }
        }
        Text("현재 음량: %.1f dBFS".format(micDb))
        LinearProgressIndicator(progress = { ((micDb + 90f) / 90f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }

    /** 0번 준비 단계를 자동으로 실행한다. Vehicle 서비스 바인딩이 끝날 시간을 잠깐 둔다. */
    private fun prepare() {
        lifecycleScope.launch {
            delay(1_500)
            vehicle.checkCapabilities()
            vehicle.registerListeners()
            carApi.connect()
        }
    }

    private var listenJob: Job? = null

    /** 화면이 떠 있는 상태에서 실제 듣기 구성(앱 마이크 + STT sendAudio)을 확인한다. 상세는 logcat BTVoice */
    private fun listenFor(type: InteractionType, keywords: List<String>) {
        listenJob?.cancel()
        voice.stopMic()
        val input = MicVoiceInput(MicCapture(), PleosStt(applicationContext)) // 실제 마이크
        log.add("Listen", "듣기 시작 $type $keywords")
        listenJob = lifecycleScope.launch {
            runCatching {
                withTimeoutOrNull(30_000) { input.listen(type, keywords).collect { log.add("Listen", "✅ $it") } }
            }.onFailure { log.add("Listen", "❌ ${it.message}") }
            log.add("Listen", "듣기 끝")
        }
    }

    private fun startMicIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.startMic()
        } else {
            log.add("Mic", "RECORD_AUDIO 권한이 없습니다")
        }
    }

    private fun logPermissionStatus() {
        val missing = missingPermissions()
        log.add("권한", if (missing.isEmpty()) "모두 허용됨" else "미허용: ${missing.joinToString { it.substringAfterLast('.') }}")
    }

    @Composable
    private fun LiveState() {
        val v by vehicle.live.collectAsStateWithLifecycle()
        val c by carApi.live.collectAsStateWithLifecycle()
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("정차 판단 입력값 (실시간)", style = MaterialTheme.typography.titleMedium)
                Text("Pleos 기어: ${v.gear}   |   AAOS 기어: ${c.gear}")
                Text("주차 브레이크(AAOS): ${c.parkingBrake}   |   브레이크 페달(Pleos): ${v.brakePedal}")
                Text("속도(AAOS): ${c.speed}   |   UX 제한: ${c.uxRestricted}")
                Text("도어: ${v.doors.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "-" }}")
                Text("시동(AAOS): ${c.ignition}   |   공조 전원(Pleos): ${v.hvacPower}")
                Text("AAOS 연결: ${c.connected}")
            }
        }
    }

    @Composable
    private fun LogPanel(modifier: Modifier) {
        val lines by log.lines.collectAsStateWithLifecycle()
        Card(modifier = modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("로그 (최신순, logcat 태그 BTProbe)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = log::clear) { Text("지우기") }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn {
                    items(lines) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
    }

    private companion object {
        // 기획서 7장 연출 색 (ARGB, 알파는 SDK가 0xFF로 고정)
        const val WIND_BLUE = 0xFF4A90E2.toInt()
        const val SUN_ORANGE = 0xFFFF9A3C.toInt()
        const val SOFT_WHITE = 0xFFFFF4E0.toInt()
    }
}
