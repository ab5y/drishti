package app.drishti.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.drishti.core.AnswerSource
import app.drishti.core.AppConfig
import app.drishti.core.AxisEvent
import app.drishti.core.AxisGate
import app.drishti.core.AxisLatch
import app.drishti.core.CardCalibration
import app.drishti.core.ConfigException
import app.drishti.core.DisplaySize
import app.drishti.core.FixedDistance
import app.drishti.core.FlowScreen
import app.drishti.core.InputResult
import app.drishti.core.KeyDebouncer
import app.drishti.core.Readiness
import app.drishti.core.RemoteAction
import app.drishti.core.ScreenCalibration
import app.drishti.core.ScreeningFlow
import app.drishti.core.TestGate
import app.drishti.data.CalibrationStore
import app.drishti.data.ConfigRepository
import app.drishti.input.AmbientLightSensor
import app.drishti.input.AxisValue
import app.drishti.input.KeyMap
import app.drishti.input.MotionFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class Screen { HOME, CALIBRATION, DEBUG, FLOW, KEY_CAPTURE }

/** One logged input on the key capture screen, for mapping a real remote or gamepad. */
sealed interface Captured {
    data class Key(
        val keyCode: Int,
        val keyName: String,
        val scanCode: Int,
        val deviceName: String,
        val mapsTo: RemoteAction?,
    ) : Captured

    /** A motion that produced something: a press, a diagonal or an unmapped axis push. */
    data class Motion(val deviceName: String, val control: String, val values: String, val result: String) : Captured
}

/** The latest joystick values, shown live on the key capture screen. */
data class LiveMotion(
    val deviceName: String,
    val hat: Pair<Double, Double>,
    val stick: Pair<Double, Double>,
    val others: List<AxisValue>,
)

sealed interface UiState {
    data object Loading : UiState
    /** config.json is invalid: the app refuses to test until it is fixed. */
    data class ConfigError(val errors: List<String>) : UiState
    data class Ready(
        val config: AppConfig,
        val screen: Screen = Screen.HOME,
        val calibration: ScreenCalibration? = null,
        val readiness: Readiness = Readiness.NotCalibrated,
        val lastKey: RemoteAction? = null,
        val notice: String? = null,
        val flow: FlowScreen? = null,
        val registerErrors: List<String> = emptyList(),
        val lux: Double? = null,
        val lightSensorAvailable: Boolean = false,
        val captured: List<Captured> = emptyList(),
        val liveMotion: LiveMotion? = null,
        /** "Unmapped: KEYCODE_x" or why a press was not used; shown for [KeyMap.NOTICE_MS] (D44). */
        val inputNotice: String? = null,
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CalibrationStore(app)
    private val light = AmbientLightSensor(app)
    private val display = MutableStateFlow<DisplaySize?>(null)
    private val debouncer = KeyDebouncer(KeyMap.DEBOUNCE_MS)
    private val axes = HashMap<Int, DeviceAxes>()
    private var deviceId: String? = null
    private var flow: ScreeningFlow? = null
    private var noticeJob: Job? = null

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Hat and left stick gates plus a latch per other axis, for one input device (D40). */
    private inner class DeviceAxes {
        val hat = AxisGate(debouncer, KeyMap.AXIS_TRIGGER, KeyMap.AXIS_RELEASE)
        val stick = AxisGate(debouncer, KeyMap.AXIS_TRIGGER, KeyMap.AXIS_RELEASE)
        val others = HashMap<Int, AxisLatch>()

        fun latch(axis: Int) = others.getOrPut(axis) { AxisLatch(KeyMap.AXIS_TRIGGER, KeyMap.AXIS_RELEASE) }
    }

    init {
        val config = try {
            ConfigRepository(app).load()
        } catch (e: ConfigException) {
            null.also { _state.value = UiState.ConfigError(e.errors) }
        }
        if (config != null) {
            _state.value = UiState.Ready(config, lightSensorAvailable = light.available)
            viewModelScope.launch {
                val id = store.deviceId().also { deviceId = it }
                combine(store.calibration, display.filterNotNull()) { cal, d -> cal to TestGate.check(cal, id, d) }
                    .collect { (cal, readiness) ->
                        updateReady { s ->
                            // Leave the debug screen if calibration stops being valid.
                            val screen = if (s.screen == Screen.DEBUG && readiness !is Readiness.Ready) Screen.HOME else s.screen
                            s.copy(calibration = cal, readiness = readiness, screen = screen)
                        }
                    }
            }
            viewModelScope.launch { light.lux.collect { lux -> updateReady { it.copy(lux = lux) } } }
        }
    }

    fun setDisplay(size: DisplaySize) {
        display.value = size
    }

    fun open(screen: Screen) = updateReady { s ->
        if (screen == Screen.DEBUG && s.readiness !is Readiness.Ready) {
            s.copy(notice = "Calibrate the screen first.")
        } else {
            val capture = screen == Screen.KEY_CAPTURE
            s.copy(
                screen = screen,
                notice = null,
                captured = if (capture) emptyList() else s.captured,
                liveMotion = if (capture) null else s.liveMotion,
            )
        }
    }

    fun saveCalibration(matchedEdgePx: Int, xdpi: Double) {
        val id = deviceId ?: return
        val d = display.value ?: return
        val cal = ScreenCalibration(
            deviceId = id,
            pxPerMm = CardCalibration.pxPerMm(matchedEdgePx.toDouble()),
            display = d,
            xdpi = xdpi,
            calibratedAtEpochMs = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            store.save(cal)
            updateReady { it.copy(screen = Screen.HOME, notice = "Saved: %.3f px/mm".format(cal.pxPerMm)) }
        }
    }

    // ---- screening flow ------------------------------------------------------------------

    /** The test flow refuses to start without a valid card calibration. */
    fun startTest() {
        val s = _state.value as? UiState.Ready ?: return
        val r = s.readiness
        if (r !is Readiness.Ready) {
            updateReady {
                it.copy(
                    notice = if (r is Readiness.DisplayChanged) {
                        "Cannot start: display resolution changed since calibration. Recalibrate."
                    } else {
                        "Cannot start: calibrate the screen with a card first."
                    },
                )
            }
            return
        }
        flow = ScreeningFlow(
            s.config, r.pxPerMm, FixedDistance.from(s.config), Random.Default,
            clock = SystemClock::uptimeMillis,
            // Second light reading when the first eye's test starts (D38); the sensor runs until the result.
            readLux = { light.lux.value },
            promptMinShowMs = KeyMap.PROMPT_MIN_SHOW_MS,
        )
        light.start()
        updateReady { it.copy(screen = Screen.FLOW, notice = null, registerErrors = emptyList()) }
        publishFlow()
    }

    fun setTestViewport(widthPx: Int, heightPx: Int) {
        flow?.setViewport(widthPx, heightPx)
    }

    fun register(token: String, age: String) {
        val f = flow ?: return
        val errors = f.register(token, age, light.lux.value)
        updateReady { it.copy(registerErrors = errors) }
        publishFlow()
    }

    /** On-screen Continue / Ready, for the operator. */
    fun flowOk() = padPress(RemoteAction.OK)

    /** The operator answer pad (D43), and other on-screen equivalents of a remote press. */
    fun padPress(action: RemoteAction) {
        val f = flow ?: return
        report(f.onAction(action, AnswerSource.PAD), prefix = null)
        publishFlow()
    }

    fun useOnScreenAnswers() {
        flow?.useOnScreenAnswers()
        publishFlow()
    }

    fun pollFlow() {
        flow?.poll()
        publishFlow()
    }

    fun exitFlow() {
        flow = null
        light.stop()
        updateReady { it.copy(screen = Screen.HOME, flow = null) }
    }

    private fun publishFlow() {
        val screen = flow?.screen
        if (screen is FlowScreen.Result) light.stop()
        updateReady { it.copy(flow = screen) }
    }

    /** The flow screen where presses go to the flow (registration keeps normal key handling). */
    private fun flowTakesPresses(s: UiState.Ready): ScreeningFlow? =
        flow?.takeIf { s.screen == Screen.FLOW && it.screen != FlowScreen.Register }

    // ---- keys --------------------------------------------------------------------------

    /**
     * All key events from the activity. Returns true if consumed (D44).
     * Only key-down with repeat count 0 does anything. Keys from the phone's own
     * UI (navigation-bar Back, the on-screen keyboard) are left to the system.
     */
    fun onKey(
        keyCode: Int,
        keyName: String,
        scanCode: Int,
        deviceName: String,
        fromPhoneUi: Boolean,
        isDown: Boolean,
        repeatCount: Int,
        eventTimeMs: Long,
    ): Boolean {
        val s = _state.value as? UiState.Ready ?: return false
        val action = KeyMap.TABLE[keyCode]
        if (s.screen == Screen.KEY_CAPTURE) {
            if (isDown && repeatCount == 0) capture(Captured.Key(keyCode, keyName, scanCode, deviceName, action))
            return true // capture everything, including back and volume
        }
        if (fromPhoneUi) return false
        val f = flowTakesPresses(s)
        // During the flow every physical key is consumed so it cannot reach other apps (media keys etc.).
        val consume = f != null || action != null
        if (!isDown || repeatCount != 0) return consume

        if (action == null) {
            val label = "Unmapped: $keyName"
            if (f != null && debouncer.accept(eventTimeMs)) report(f.onOtherPress(), label) else notify(label)
            return consume
        }
        if (debouncer.accept(eventTimeMs)) press(action)
        return true
    }

    /** A mapped press from the remote, by key or axis (already debounced). */
    private fun press(action: RemoteAction) {
        val s = _state.value as? UiState.Ready ?: return
        val f = flowTakesPresses(s)
        when {
            f != null -> {
                report(f.onAction(action, AnswerSource.REMOTE), prefix = null)
                publishFlow()
            }
            s.screen == Screen.FLOW -> notify(ScreeningFlow.USE_SCREEN)
        }
        updateReady { it.copy(lastKey = action) }
    }

    // ---- joystick and hat ------------------------------------------------------------------

    /** Joystick motion from the activity (D40). Always consumed so Android does not also make D-pad keys from it. */
    fun onMotion(deviceId: Int, deviceName: String, frames: List<MotionFrame>): Boolean {
        val s = _state.value as? UiState.Ready ?: return true
        val gates = axes.getOrPut(deviceId) { DeviceAxes() }
        val capture = s.screen == Screen.KEY_CAPTURE
        for (fr in frames) {
            val events = listOf(
                Triple("Hat", fr.hatX to fr.hatY, gates.hat.update(fr.hatX, fr.hatY, fr.eventTimeMs)),
                Triple("Left stick", fr.stickX to fr.stickY, gates.stick.update(fr.stickX, fr.stickY, fr.eventTimeMs)),
            )
            for ((control, xy, event) in events) {
                if (event == null) continue
                if (capture) {
                    capture(Captured.Motion(deviceName, control, xyText(control, xy), describe(event)))
                } else {
                    onAxisEvent(event, fr.eventTimeMs)
                }
            }
            for (v in fr.others) {
                if (!gates.latch(v.axis).update(v.value)) continue
                if (capture) {
                    capture(Captured.Motion(deviceName, v.name, "%s %.2f".format(v.name, v.value), "not mapped"))
                } else {
                    otherPress("Unmapped: ${v.name}", fr.eventTimeMs)
                }
            }
        }
        if (capture) {
            val last = frames.last()
            updateReady {
                it.copy(liveMotion = LiveMotion(deviceName, last.hatX to last.hatY, last.stickX to last.stickY, last.others))
            }
        }
        return true
    }

    private fun onAxisEvent(event: AxisEvent, eventTimeMs: Long) = when (event) {
        is AxisEvent.Press -> press(event.action)
        AxisEvent.Diagonal -> otherPress(DIAGONAL, eventTimeMs)
    }

    /** A remote press with no mapping: it may continue a prompt (D41); always shown (D44). */
    private fun otherPress(label: String, eventTimeMs: Long) {
        val s = _state.value as? UiState.Ready ?: return
        val f = flowTakesPresses(s)
        if (f != null && debouncer.accept(eventTimeMs)) report(f.onOtherPress(), label) else notify(label)
    }

    // ---- notices -----------------------------------------------------------------------

    private fun report(result: InputResult, prefix: String?) {
        publishFlow()
        val why = (result as? InputResult.NotUsed)?.why
        val text = listOfNotNull(prefix, why).joinToString(" · ")
        if (text.isNotEmpty()) notify(text)
    }

    private fun notify(text: String) {
        noticeJob?.cancel()
        updateReady { it.copy(inputNotice = text) }
        noticeJob = viewModelScope.launch {
            delay(KeyMap.NOTICE_MS)
            updateReady { it.copy(inputNotice = null) }
        }
    }

    private fun capture(c: Captured) = updateReady { it.copy(captured = (listOf(c) + it.captured).take(MAX_CAPTURED)) }

    override fun onCleared() {
        light.stop()
    }

    private fun updateReady(f: (UiState.Ready) -> UiState.Ready) {
        _state.update { s -> if (s is UiState.Ready) f(s) else s }
    }

    companion object {
        private const val MAX_CAPTURED = 50
        const val DIAGONAL = "Diagonal: press one direction"

        fun xyText(control: String, xy: Pair<Double, Double>): String {
            val (x, y) = if (control == "Hat") "AXIS_HAT_X" to "AXIS_HAT_Y" else "AXIS_X" to "AXIS_Y"
            return "%s %.2f, %s %.2f".format(x, xy.first, y, xy.second)
        }

        fun describe(event: AxisEvent?): String = when (event) {
            is AxisEvent.Press -> event.action.name
            AxisEvent.Diagonal -> "nothing (diagonal)"
            null -> "nothing"
        }
    }
}
