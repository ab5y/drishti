package app.drishti.ui

import app.drishti.core.FlowScreen

/**
 * D68: screens where Back must do nothing at all, at the activity level as well
 * as in Compose. Remote setup records Back as a key; a letter must not be lost.
 */
object BackGuard {
    fun blocks(screen: Screen, flow: FlowScreen?): Boolean =
        screen == Screen.REMOTE_SETUP || (screen == Screen.FLOW && flow is FlowScreen.Letter)
}
