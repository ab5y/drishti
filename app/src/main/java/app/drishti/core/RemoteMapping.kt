package app.drishti.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One physical press: a key, or one axis pushed one way (D47). */
@Serializable
sealed interface InputId {
    @Serializable
    @SerialName("key")
    data class Key(val keyCode: Int) : InputId

    @Serializable
    @SerialName("axis")
    data class Axis(val axis: Int, val positive: Boolean) : InputId

    /** For the setup screen; [name] gives the Android name of a key or axis code, if known. */
    fun label(name: (Int) -> String?): String = when (this) {
        is Key -> "${name(keyCode) ?: "key"} ($keyCode)"
        is Axis -> "${name(axis) ?: "axis $axis"} ${if (positive) "+" else "-"}"
    }
}

@Serializable
data class Assignment(val input: InputId, val action: RemoteAction)

/** Which remote a stored mapping belongs to: InputDevice descriptor plus name (D47). */
@Serializable
data class RemoteKey(val descriptor: String, val name: String)

/** The operator's mapping for one remote. Later assignments of the same press replace earlier ones. */
@Serializable
data class RemoteProfile(val remote: RemoteKey, val assignments: List<Assignment> = emptyList()) {
    fun actionFor(input: InputId): RemoteAction? = assignments.lastOrNull { it.input == input }?.action

    fun assign(input: InputId, action: RemoteAction): RemoteProfile =
        copy(assignments = assignments.filter { it.input != input } + Assignment(input, action))
}

/** Every stored mapping, as kept in DataStore. */
@Serializable
data class RemoteProfiles(val profiles: List<RemoteProfile> = emptyList()) {
    fun forRemote(remote: RemoteKey): RemoteProfile? = profiles.firstOrNull { it.remote == remote }

    fun assign(remote: RemoteKey, input: InputId, action: RemoteAction): RemoteProfiles {
        val updated = (forRemote(remote) ?: RemoteProfile(remote)).assign(input, action)
        return RemoteProfiles(profiles.filter { it.remote != remote } + updated)
    }

    /** Deletes one remote's mapping, so it falls back to the defaults. */
    fun reset(remote: RemoteKey): RemoteProfiles = RemoteProfiles(profiles.filter { it.remote != remote })

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Unreadable saved text loads as no mappings, so the defaults apply. */
        fun decode(text: String): RemoteProfiles =
            runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(RemoteProfiles())
    }
}

sealed interface Resolved {
    /** [stored] is true when the operator's mapping gave the action, false for a default. */
    data class Mapped(val action: RemoteAction, val stored: Boolean) : Resolved
    data object Unmapped : Resolved
}

/** Resolves a press: that remote's stored mapping first, then the built-in [defaults] (D47). */
class RemoteMapping(private val defaults: Map<InputId, RemoteAction>) {
    fun resolve(profile: RemoteProfile?, input: InputId): Resolved {
        profile?.actionFor(input)?.let { return Resolved.Mapped(it, stored = true) }
        defaults[input]?.let { return Resolved.Mapped(it, stored = false) }
        return Resolved.Unmapped
    }
}

/**
 * Hat and stick presses from [AxisGate] as axis plus direction. The gate
 * reports the direction it saw in screen terms (+X right, +Y down), so this
 * recovers which axis moved and which way.
 */
object AxisInputs {
    fun of(xAxis: Int, yAxis: Int, gateAction: RemoteAction): InputId.Axis? = when (gateAction) {
        RemoteAction.RIGHT -> InputId.Axis(xAxis, positive = true)
        RemoteAction.LEFT -> InputId.Axis(xAxis, positive = false)
        RemoteAction.DOWN -> InputId.Axis(yAxis, positive = true)
        RemoteAction.UP -> InputId.Axis(yAxis, positive = false)
        RemoteAction.OK -> null
    }

    /** The built-in meaning of a two-axis control: each direction means itself. */
    fun defaults(xAxis: Int, yAxis: Int): Map<InputId, RemoteAction> =
        listOf(RemoteAction.UP, RemoteAction.DOWN, RemoteAction.LEFT, RemoteAction.RIGHT)
            .associateBy { checkNotNull(of(xAxis, yAxis, it)) }
}
