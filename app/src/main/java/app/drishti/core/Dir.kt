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
