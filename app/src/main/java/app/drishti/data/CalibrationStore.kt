package app.drishti.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(name = "calibration")

/**
 * Per-device calibration. pxPerMm is only ever written from the card match;
 * it is null until the device has been calibrated.
 */
class CalibrationStore(context: Context) {
    private val store = context.applicationContext.calibrationDataStore

    val pxPerMm: Flow<Double?> = store.data.map { it[PX_PER_MM] }

    suspend fun savePxPerMm(pxPerMm: Double) {
        require(pxPerMm > 0 && pxPerMm.isFinite()) { "pxPerMm must be > 0, was $pxPerMm" }
        store.edit { it[PX_PER_MM] = pxPerMm }
    }

    /** A random, non-identifying id for this install, created on first use. */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        var id = ""
        store.edit { prefs -> id = prefs[DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID] = it } }
        return id
    }

    private companion object {
        val PX_PER_MM = doublePreferencesKey("px_per_mm")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
