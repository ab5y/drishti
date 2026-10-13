package app.drishti.data

import android.content.Context
import app.drishti.core.AppConfig

/** Loads assets/config.json. Throws ConfigException (listing every problem) if invalid. */
class ConfigRepository(private val context: Context) {
    fun load(): AppConfig =
        context.assets.open(ASSET_NAME).bufferedReader().use { AppConfig.parse(it.readText()) }

    companion object {
        const val ASSET_NAME = "config.json"
    }
}
