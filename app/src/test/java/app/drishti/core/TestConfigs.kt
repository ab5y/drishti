package app.drishti.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The shipped config.json, optionally modified, for tests. */
object TestConfigs {
    val shippedText: String = File("src/main/assets/config.json").readText()
    private val shippedJson: JsonObject = Json.parseToJsonElement(shippedText).jsonObject

    fun shipped(): AppConfig = AppConfig.parse(shippedText)

    /** Override top-level keys ("a") or nested keys ("section.key"). */
    fun with(vararg overrides: Pair<String, Any>): AppConfig {
        var obj = shippedJson
        for ((path, value) in overrides) {
            val v: JsonElement = when (value) {
                is JsonElement -> value
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                else -> error("unsupported $value")
            }
            val parts = path.split(".")
            obj = if (parts.size == 1) {
                JsonObject(obj + (path to v))
            } else {
                val section = obj.getValue(parts[0]).jsonObject
                JsonObject(obj + (parts[0] to JsonObject(section + (parts[1] to v))))
            }
        }
        return AppConfig.parse(obj.toString())
    }
}
