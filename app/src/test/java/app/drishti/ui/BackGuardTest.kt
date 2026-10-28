package app.drishti.ui

import app.drishti.core.FixedDistance
import app.drishti.core.FlowScreen
import app.drishti.core.RemoteAction
import app.drishti.core.RemoteCheck
import app.drishti.core.ScreeningFlow
import app.drishti.core.TestConfigs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackGuardTest {
    /** A real letter screen, reached the way an operator would. */
    private fun letter(): FlowScreen.Letter {
        val config = TestConfigs.shipped()
        val f = ScreeningFlow(config, 400 / 25.4, FixedDistance.from(config), Random(1), { 0L }, { 300.0 }, 0)
        f.setViewport(2400, 1080)
        f.register("", "", 300.0, consent = true)
        f.onRemoteConnection(true)
        repeat(100) {
            when (val s = f.screen) {
                is FlowScreen.Letter -> return s
                is FlowScreen.RemoteCheck -> RemoteCheck.ORDER.forEach { f.onAction(it) }
                else -> f.onAction(RemoteAction.OK)
            }
        }
        error("no letter screen")
    }

    @Test
    fun `back does nothing on remote setup and on a letter (D68)`() {
        assertTrue(BackGuard.blocks(Screen.REMOTE_SETUP, null))
        assertTrue(BackGuard.blocks(Screen.FLOW, letter()))
    }

    @Test
    fun `back works as usual everywhere else`() {
        listOf(Screen.HOME, Screen.CALIBRATION, Screen.DEBUG, Screen.RECORDS).forEach {
            assertFalse("$it", BackGuard.blocks(it, null))
        }
        assertFalse(BackGuard.blocks(Screen.FLOW, FlowScreen.Register))
        assertFalse(BackGuard.blocks(Screen.FLOW, null))
        // A letter left over in state does not block Back once the flow is closed.
        assertFalse(BackGuard.blocks(Screen.HOME, letter()))
    }
}
