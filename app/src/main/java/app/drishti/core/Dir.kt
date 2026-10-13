package app.drishti.core

/** A response from the remote. UNSURE is scored as incorrect but counted for the quality flag. */
enum class Dir { UP, DOWN, LEFT, RIGHT, UNSURE }

/** The direction the limbs of the tumbling E point. */
enum class Orientation {
    UP, DOWN, LEFT, RIGHT;

    fun isCorrect(response: Dir): Boolean = when (this) {
        UP -> response == Dir.UP
        DOWN -> response == Dir.DOWN
        LEFT -> response == Dir.LEFT
        RIGHT -> response == Dir.RIGHT
    }
}

/** A remote button. OK means "unsure" while a letter is shown and "ready" on a prompt. */
enum class RemoteAction {
    UP, DOWN, LEFT, RIGHT, OK;

    fun toDir(): Dir = when (this) {
        UP -> Dir.UP
        DOWN -> Dir.DOWN
        LEFT -> Dir.LEFT
        RIGHT -> Dir.RIGHT
        OK -> Dir.UNSURE
    }
}

/** Drops a key press that arrives less than [minIntervalMs] after the last accepted one. */
class KeyDebouncer(private val minIntervalMs: Long) {
    private var lastAcceptedMs: Long? = null

    fun accept(eventTimeMs: Long): Boolean {
        val last = lastAcceptedMs
        if (last != null && eventTimeMs - last < minIntervalMs) return false
        lastAcceptedMs = eventTimeMs
        return true
    }
}
