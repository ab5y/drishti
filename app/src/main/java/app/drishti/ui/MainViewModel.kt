package app.drishti.ui

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.drishti.core.AnswerSource
import app.drishti.core.AppConfig
import app.drishti.core.AxisEvent
import app.drishti.core.AxisGate
import app.drishti.core.AxisInputs
import app.drishti.core.AxisLatch
import app.drishti.core.CardCalibration
import app.drishti.core.ConfigException
import app.drishti.core.DisplaySize
import app.drishti.core.FixedDistance
import app.drishti.core.FlowScreen
import app.drishti.core.InputId
import app.drishti.core.InputResult
import app.drishti.core.KeyDebouncer
import app.drishti.core.Readiness
import app.drishti.core.RemoteAction
import app.drishti.core.RemoteFilter
import app.drishti.core.RemoteKey
import app.drishti.core.RemoteMapping
import app.drishti.core.RemoteProfiles
import app.drishti.core.RemoteStatus
import app.drishti.core.Resolved
import app.drishti.core.ScreenCalibration
import app.drishti.core.ScreeningFlow
import app.drishti.core.SeenDevice
import app.drishti.core.StationRemote
import app.drishti.core.TestGate
import app.drishti.data.CalibrationStore
import app.drishti.data.ConfigRepository
import app.drishti.data.RemoteStore
import app.drishti.input.AmbientLightSensor
import app.drishti.input.AxisValue
import app.drishti.input.DeviceRef
import app.drishti.input.InputDevices
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

enum class Screen { HOME, CALIBRATION, DEBUG, FLOW, REMOTE_SETUP }

/**
 * One logged press on Remote setup (D47). [input] is what can be assigned:
 * null for a diagonal, which is no single press.
 */
data class Captured(
    val device: DeviceRef,
    val input: InputId?,
    /** The raw press: key name and codes, or axis values. */
    val what: String,
    /** What it does now: "recognised as Left (your mapping)", "not mapped", ... */
    val gives: String,
)

/** The latest joystick values, shown live on Remote setup. */
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
        val devices: List<SeenDevice> = emptyList(),
        val station: StationRemote? = null,
        val profiles: RemoteProfiles = RemoteProfiles(),
        val remote: RemoteStatus = RemoteStatus(connected = false, battery = null),
        /** False once the operator chose on-screen answers: the remote chip is hidden (D49). */
        val remoteAnswers: Boolean = true,
    ) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CalibrationStore(app)
    private val light = AmbientLightSensor(app)
    private val remoteStore = RemoteStore(app)
    private val inputDevices = InputDevices(app)
    private val mapping = RemoteMapping(KeyMap.DEFAULTS)
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
            inputDevices.start()
            viewModelScope.launch {
                combine(inputDevices.devices, remoteStore.station, remoteStore.profiles, ::Triple)
                    .collect { (devices, station, profiles) ->
                        updateReady {
                            it.copy(devices = devices, station = station, profiles = profiles, remote = RemoteStatus.of(station, devices))
                        }
                        syncRemote()
                    }
            }
            viewModelScope.launch {
                // Battery changes are not always announced by the listener, so re-read them (D51).
                while (true) {
                    delay(KeyMap.BATTERY_REFRESH_MS)
                    val screen = (_state.value as? UiState.Ready)?.screen
                    if (screen == Screen.FLOW || screen == Screen.REMOTE_SETUP) inputDevices.refresh()
                }
            }
        }
    }

    fun setDisplay(size: DisplaySize) {
        display.value = size
    }

    fun open(screen: Screen) = updateReady { s ->
        if (screen == Screen.DEBUG && s.readiness !is Readiness.Ready) {
            s.copy(notice = "Calibrate the screen first.")
        } else {
            val capture = screen == Screen.REMOTE_SETUP
            if (capture) inputDevices.refresh()
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
        inputDevices.refresh()
        updateReady { it.copy(screen = Screen.FLOW, notice = null, registerErrors = emptyList()) }
        syncRemote()
        publishFlow()
    }

    /** Tells the flow whether the remote is connected (D50); it reacts only to a change. */
    private fun syncRemote() {
        val s = _state.value as? UiState.Ready ?: return
        val f = flow ?: return
        f.onRemoteConnection(s.remote.connected)
        publishFlow()
    }

    /** From the remote-lost pause (D50). */
    fun continueOnScreen() {
        flow?.continueOnScreen()
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
        val f = flow
        val screen = f?.screen
        if (screen is FlowScreen.Result) light.stop()
        updateReady { it.copy(flow = screen, remoteAnswers = f?.remoteAnswers ?: true) }
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
        device: DeviceRef,
        fromPhoneUi: Boolean,
        isDown: Boolean,
        repeatCount: Int,
        eventTimeMs: Long,
    ): Boolean {
        val s = _state.value as? UiState.Ready ?: return false
        val input = InputId.Key(keyCode)
        val resolved = resolve(s, device, input)
        if (s.screen == Screen.REMOTE_SETUP) {
            if (isDown && repeatCount == 0) {
                capture(Captured(device, input, "$keyName ($keyCode) · scanCode $scanCode", gives(resolved)))
            }
            return true // capture everything, including back and volume
        }
        if (fromPhoneUi) return false
        val action = (resolved as? Resolved.Mapped)?.action
        val f = flowTakesPresses(s)
        // During the flow every physical key is consumed so it cannot reach other apps (media keys etc.).
        val consume = f != null || action != null
        if (!isDown || repeatCount != 0) return consume
        if (!accepted(s, device)) return consume

        if (action == null) {
            val label = "Unmapped: $keyName"
            if (f != null && debouncer.accept(eventTimeMs)) report(f.onOtherPress(), label) else notify(label)
            return consume
        }
        if (debouncer.accept(eventTimeMs)) press(action)
        return true
    }

    /** The device's stored mapping first, then the built-in defaults (D47). */
    private fun resolve(s: UiState.Ready, device: DeviceRef, input: InputId): Resolved {
        val profile = device.descriptor?.let { s.profiles.forRemote(RemoteKey(it, device.name)) }
        return mapping.resolve(profile, input)
    }

    /** D48: with a station remote set, other external devices are ignored during a test, and the operator is told. */
    private fun accepted(s: UiState.Ready, device: DeviceRef): Boolean {
        val duringTest = flowTakesPresses(s) != null
        if (RemoteFilter.accepts(s.station, device.descriptor, device.external, duringTest)) return true
        notify("Ignored: ${device.name} is not the station remote")
        return false
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
    fun onMotion(deviceId: Int, device: DeviceRef, frames: List<MotionFrame>): Boolean {
        val s = _state.value as? UiState.Ready ?: return true
        val gates = axes.getOrPut(deviceId) { DeviceAxes() }
        val capture = s.screen == Screen.REMOTE_SETUP
        // Gates still see the motion so a held stick from an ignored device cannot fire later.
        val ignored = !capture && flowTakesPresses(s) != null &&
            !RemoteFilter.accepts(s.station, device.descriptor, device.external, duringTest = true)
        for (fr in frames) {
            val events = listOf(
                Triple(HAT, fr.hatX to fr.hatY, gates.hat.update(fr.hatX, fr.hatY, fr.eventTimeMs)),
                Triple(STICK, fr.stickX to fr.stickY, gates.stick.update(fr.stickX, fr.stickY, fr.eventTimeMs)),
            )
            for ((control, xy, event) in events) {
                if (event == null) continue
                if (ignored) {
                    accepted(s, device)
                    continue
                }
                val input = (event as? AxisEvent.Press)?.let {
                    if (control == HAT) AxisInputs.of(HAT_X, HAT_Y, it.action) else AxisInputs.of(STICK_X, STICK_Y, it.action)
                }
                val resolved = input?.let { resolve(s, device, it) }
                if (capture) {
                    capture(Captured(device, input, "$control: ${xyText(control, xy)}", resolved?.let(::gives) ?: describe(event)))
                    continue
                }
                when (resolved) {
                    is Resolved.Mapped -> press(resolved.action)
                    Resolved.Unmapped -> otherPress("Unmapped: ${inputLabel(checkNotNull(input))}", fr.eventTimeMs)
                    null -> otherPress(DIAGONAL, fr.eventTimeMs)
                }
            }
            for (v in fr.others) {
                if (!gates.latch(v.axis).update(v.value)) continue
                if (ignored) {
                    accepted(s, device)
                    continue
                }
                val input = InputId.Axis(v.axis, positive = v.value > 0)
                val resolved = resolve(s, device, input)
                when {
                    capture -> capture(Captured(device, input, "%s %.2f".format(v.name, v.value), gives(resolved)))
                    // An axis the operator mapped: the latch has no debouncer, so it shares the keys' one.
                    resolved is Resolved.Mapped -> if (debouncer.accept(fr.eventTimeMs)) press(resolved.action)
                    else -> otherPress("Unmapped: ${inputLabel(input)}", fr.eventTimeMs)
                }
            }
        }
        if (capture) {
            val last = frames.last()
            updateReady {
                it.copy(liveMotion = LiveMotion(device.name, last.hatX to last.hatY, last.stickX to last.stickY, last.others))
            }
        }
        return true
    }

    /** A remote press with no mapping: it may continue a prompt (D41); always shown (D44). */
    private fun otherPress(label: String, eventTimeMs: Long) {
        val s = _state.value as? UiState.Ready ?: return
        val f = flowTakesPresses(s)
        if (f != null && debouncer.accept(eventTimeMs)) report(f.onOtherPress(), label) else notify(label)
    }

    // ---- remote setup ------------------------------------------------------------------

    /** Assigns the last captured press to [action] for the remote it came from (D47). */
    fun assignLast(action: RemoteAction) {
        val s = _state.value as? UiState.Ready ?: return
        val last = s.captured.firstOrNull() ?: return
        val input = last.input ?: return
        val descriptor = last.device.descriptor ?: return
        viewModelScope.launch {
            remoteStore.assign(RemoteKey(descriptor, last.device.name), input, action)
            notify("Saved: ${inputLabel(input)} on ${last.device.name} now means ${actionLabel(action)}")
        }
    }

    fun resetMapping(remote: RemoteKey) {
        viewModelScope.launch {
            remoteStore.reset(remote)
            notify("Mapping reset for ${remote.name}: built-in defaults apply")
        }
    }

    fun setStation(station: StationRemote?) {
        viewModelScope.launch { remoteStore.setStation(station) }
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
        inputDevices.stop()
    }

    private fun updateReady(f: (UiState.Ready) -> UiState.Ready) {
        _state.update { s -> if (s is UiState.Ready) f(s) else s }
    }

    companion object {
        private const val MAX_CAPTURED = 50
        const val DIAGONAL = "Diagonal: press one direction"
        private const val HAT = "Hat"
        private const val STICK = "Left stick"
        private const val HAT_X = MotionEvent.AXIS_HAT_X
        private const val HAT_Y = MotionEvent.AXIS_HAT_Y
        private const val STICK_X = MotionEvent.AXIS_X
        private const val STICK_Y = MotionEvent.AXIS_Y

        fun inputLabel(i: InputId): String = when (i) {
            is InputId.Key -> i.label { KeyEvent.keyCodeToString(it) }
            is InputId.Axis -> i.label { MotionEvent.axisToString(it) }
        }

        fun actionLabel(a: RemoteAction) = when (a) {
            RemoteAction.UP -> "Up"
            RemoteAction.DOWN -> "Down"
            RemoteAction.LEFT -> "Left"
            RemoteAction.RIGHT -> "Right"
            RemoteAction.OK -> "Not sure (OK)"
        }

        fun gives(r: Resolved): String = when (r) {
            is Resolved.Mapped -> "recognised as ${actionLabel(r.action)}" + if (r.stored) " (your mapping)" else " (built-in)"
            Resolved.Unmapped -> "not mapped"
        }

        fun xyText(control: String, xy: Pair<Double, Double>): String {
            val (x, y) = if (control == HAT) "AXIS_HAT_X" to "AXIS_HAT_Y" else "AXIS_X" to "AXIS_Y"
            return "%s %.2f, %s %.2f".format(x, xy.first, y, xy.second)
        }

        fun describe(event: AxisEvent?): String = when (event) {
            is AxisEvent.Press -> event.action.name
            AxisEvent.Diagonal -> "nothing (diagonal)"
            null -> "nothing"
        }
    }
}
