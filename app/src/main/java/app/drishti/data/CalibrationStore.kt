package app.drishti.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.drishti.core.DisplaySize
import app.drishti.core.ScreenCalibration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(name = "calibration")

/**
 * Per-device card calibration. pxPerMm is only ever written from the card
 * match; [calibration] is null until the device has been calibrated.
 */
class CalibrationStore(context: Context) {
    private val store = context.applicationContext.calibrationDataStore

    val calibration: Flow<ScreenCalibration?> = store.data.map { p ->
        val px = p[PX_PER_MM] ?: return@map null
        ScreenCalibration(
            deviceId = p[CAL_DEVICE_ID] ?: return@map null,
            pxPerMm = px,
            display = DisplaySize(p[CAL_DISPLAY_LONG] ?: return@map null, p[CAL_DISPLAY_SHORT] ?: return@map null),
            xdpi = p[CAL_XDPI] ?: Double.NaN,
            calibratedAtEpochMs = p[CAL_AT] ?: 0L,
        )
    }

    suspend fun save(calibration: ScreenCalibration) {
        require(calibration.pxPerMm > 0 && calibration.pxPerMm.isFinite()) { "pxPerMm must be > 0" }
        store.edit {
            it[PX_PER_MM] = calibration.pxPerMm
            it[CAL_DEVICE_ID] = calibration.deviceId
            it[CAL_DISPLAY_LONG] = calibration.display.longPx
            it[CAL_DISPLAY_SHORT] = calibration.display.shortPx
            it[CAL_XDPI] = calibration.xdpi
            it[CAL_AT] = calibration.calibratedAtEpochMs
        }
    }

    /** A random, non-identifying id for this install, created on first use. */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        store.edit { prefs -> id = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it } }
        return id
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val PX_PER_MM = doublePreferencesKey("px_per_mm")
        val CAL_DEVICE_ID = stringPreferencesKey("cal_device_id")
        val CAL_DISPLAY_LONG = intPreferencesKey("cal_display_long_px")
        val CAL_DISPLAY_SHORT = intPreferencesKey("cal_display_short_px")
        val CAL_XDPI = doublePreferencesKey("cal_xdpi")
        val CAL_AT = longPreferencesKey("cal_at_epoch_ms")
    }
}
