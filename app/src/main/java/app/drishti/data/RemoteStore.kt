package app.drishti.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.drishti.core.InputId
import app.drishti.core.RemoteAction
import app.drishti.core.RemoteKey
import app.drishti.core.RemoteProfiles
import app.drishti.core.StationRemote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.remoteDataStore: DataStore<Preferences> by preferencesDataStore(name = "remote")

/** Operator remote mappings (D47) and the station remote (D48). Not clinical, so not in config.json. */
class RemoteStore(context: Context) {
    private val store = context.applicationContext.remoteDataStore

    val profiles: Flow<RemoteProfiles> = store.data.map { p -> p[PROFILES]?.let(RemoteProfiles::decode) ?: RemoteProfiles() }

    val station: Flow<StationRemote?> = store.data.map { p ->
        val descriptor = p[STATION_DESCRIPTOR] ?: return@map null
        StationRemote(descriptor, p[STATION_NAME] ?: "")
    }

    suspend fun assign(remote: RemoteKey, input: InputId, action: RemoteAction) = editProfiles { it.assign(remote, input, action) }

    suspend fun reset(remote: RemoteKey) = editProfiles { it.reset(remote) }

    suspend fun setStation(station: StationRemote?) {
        store.edit {
            if (station == null) {
                it.remove(STATION_DESCRIPTOR)
                it.remove(STATION_NAME)
            } else {
                it[STATION_DESCRIPTOR] = station.descriptor
                it[STATION_NAME] = station.name
            }
        }
    }

    private suspend fun editProfiles(change: (RemoteProfiles) -> RemoteProfiles) {
        store.edit { p ->
            val now = p[PROFILES]?.let(RemoteProfiles::decode) ?: RemoteProfiles()
            p[PROFILES] = change(now).encode()
        }
    }

    private companion object {
        val PROFILES = stringPreferencesKey("profiles_json")
        val STATION_DESCRIPTOR = stringPreferencesKey("station_descriptor")
        val STATION_NAME = stringPreferencesKey("station_name")
    }
}
