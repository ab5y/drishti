package app.drishti.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ConfigTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val shippedText = File("src/main/assets/config.json").readText()
    private val shipped: JsonObject = Json.parseToJsonElement(shippedText).jsonObject

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun JsonObject.without(key: String) = JsonObject(this - key)
    private fun JsonObject.withNested(section: String, key: String, value: JsonElement) =
        with(section, this.getValue(section).jsonObject.with(key, value))

    private fun expectErrors(json: JsonObject, vararg fragments: String): ConfigException {
        try {
            AppConfig.parse(json.toString())
        } catch (e: ConfigException) {
            fragments.forEach { f ->
                assertTrue("expected an error containing '$f', got: ${e.errors}", e.errors.any { it.contains(f) })
            }
            return e
        }
        fail("expected ConfigException")
        throw AssertionError()
    }

    @Test
    fun `shipped config parses and matches build sheet section 09 defaults`() {
        val c = AppConfig.parse(shippedText)
        assertEquals(3000.0, c.farDistanceMm, 0.0)
        assertEquals(400.0, c.nearDistanceMm, 0.0)
        assertEquals(DistanceMethod.ROPE, c.distanceMethod)
        assertEquals(Optotype.TUMBLING_E, c.optotype)
        assertEquals(true, c.crowding.enabled)
        assertEquals(CrowdingSpec(gapStrokes = 2.5, barThicknessStrokes = 1.0), c.crowdingSpec())
        assertEquals((10 downTo 0).map { it / 10.0 }, c.far.linesLogMAR)
        assertEquals(0.1, c.lineStepLogMAR, 0.0)
        assertEquals(5, c.optotypesPerLine)
        assertEquals(4, c.passMinCorrect)
        assertEquals(0.3, c.far.referralCutoffLogMAR, 0.0) // worse than 6/12 refers
        assertEquals(0.4, c.near.referralCutoffLogMAR, 0.0) // N8 @ 40 cm ~ 1.0 M ~ logMAR 0.4
        assertEquals(true, c.pinhole.onFail)
        assertEquals(3.0, c.minStrokePx, 0.0)
        assertEquals(50.0, c.minLux, 0.0)
        assertEquals(1.0, c.testBrightness, 0.0)
        assertEquals(EngineKind.DESCENDING_LINES, c.engine)
    }

    @Test
    fun `shipped config builds engine configs for far, near and pinhole`() {
        val c = AppConfig.parse(shippedText)
        val far = c.engineConfig(TestKind.FAR)
        val near = c.engineConfig(TestKind.NEAR)
        val pin = c.engineConfig(TestKind.PINHOLE)
        assertEquals(DistanceMode.FAR, far.distanceMode)
        assertEquals(DistanceMode.NEAR, near.distanceMode)
        assertEquals(DistanceMode.FAR, pin.distanceMode)
        assertEquals(0, pin.practiceTrials)
        assertEquals(0.4, near.referralCutoffLogMAR, 0.0)
        val d = FixedDistance.from(c)
        assertEquals(3000.0, d.currentDistanceMm(DistanceMode.FAR), 0.0)
        assertEquals(400.0, d.currentDistanceMm(DistanceMode.NEAR), 0.0)
    }

    @Test
    fun `missing field fails loudly`() {
        expectErrors(shipped.without("pass_min_correct"), "pass_min_correct")
    }

    @Test
    fun `unknown key fails loudly`() {
        expectErrors(shipped.with("pass_min_corect", JsonPrimitive(4)), "pass_min_corect")
    }

    @Test
    fun `unknown enum value fails loudly`() {
        expectErrors(shipped.with("optotype", JsonPrimitive("landolt_C")), "landolt_C")
    }

    @Test
    fun `pass rule larger than the line is rejected`() {
        expectErrors(shipped.with("pass_min_correct", JsonPrimitive(6)), "pass_min_correct")
    }

    @Test
    fun `cutoff not on the line set is rejected`() {
        expectErrors(shipped.withNested("far", "referral_cutoff_logmar", JsonPrimitive(0.35)), "far.referral_cutoff_logmar")
    }

    @Test
    fun `non-uniform line step is rejected`() {
        val lines = JsonArray(listOf(1.0, 0.9, 0.7, 0.6).map { JsonPrimitive(it) })
        expectErrors(
            shipped.withNested("near", "lines_logmar", lines).withNested("near", "referral_cutoff_logmar", JsonPrimitive(0.6)),
            "near.lines_logmar must descend",
        )
    }

    @Test
    fun `ascending line set is rejected`() {
        val lines = JsonArray((0..10).map { JsonPrimitive(it / 10.0) })
        expectErrors(shipped.withNested("far", "lines_logmar", lines), "far.lines_logmar must descend")
    }

    @Test
    fun `unimplemented engine and camera distance methods are rejected`() {
        expectErrors(shipped.with("engine", JsonPrimitive("adaptive_staircase")), "not implemented")
        expectErrors(shipped.with("distance_method", JsonPrimitive("stand_card")), "camera module")
    }

    @Test
    fun `all problems are reported together`() {
        val bad = shipped
            .with("far_distance_mm", JsonPrimitive(-1))
            .with("min_stroke_px", JsonPrimitive(0))
            .with("schema_version", JsonPrimitive(2))
            .with("test_brightness", JsonPrimitive(1.5))
        val e = expectErrors(bad, "far_distance_mm", "min_stroke_px", "schema_version", "test_brightness")
        assertTrue(e.errors.size >= 4)
    }

    @Test
    fun `crowding spacing must be positive, and disabled crowding gives no bars`() {
        expectErrors(shipped.withNested("crowding", "bar_gap_strokes", JsonPrimitive(0)), "crowding.bar_gap_strokes")
        expectErrors(shipped.withNested("crowding", "bar_thickness_strokes", JsonPrimitive(-1)), "crowding.bar_thickness_strokes")
        val off = AppConfig.parse(shipped.withNested("crowding", "enabled", JsonPrimitive(false)).toString())
        assertEquals(null, off.crowdingSpec())
    }

    @Test
    fun `malformed json fails loudly`() {
        try {
            AppConfig.parse("{ not json")
            fail("expected ConfigException")
        } catch (e: ConfigException) {
            assertTrue(e.message!!.contains("could not be parsed"))
        }
    }
}
