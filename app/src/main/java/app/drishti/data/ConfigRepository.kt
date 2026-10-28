package app.drishti.data

import android.content.Context
import app.drishti.core.AppConfig
import app.drishti.core.ConfigHash

/** The config as used, with the SHA-256 of the exact bytes loaded (D54). */
class LoadedConfig(val config: AppConfig, val sha256: String)

/** Loads assets/config.json. Throws ConfigException (listing every problem) if invalid. */
class ConfigRepository(private val context: Context) {
    fun load(): LoadedConfig {
        val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
        return LoadedConfig(AppConfig.parse(bytes.toString(Charsets.UTF_8)), ConfigHash.sha256(bytes))
    }

    companion object {
        const val ASSET_NAME = "config.json"
    }
}
