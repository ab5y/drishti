package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TumblingETest {

    /** Parses a 5-line picture: '#' filled, '.' empty. */
    private fun picture(vararg rows: String): Set<Cell> = buildSet {
        rows.forEachIndexed { r, line -> line.forEachIndexed { c, ch -> if (ch == '#') add(Cell(r, c)) } }
    }

    private fun render(cells: Set<Cell>) =
        (0 until 5).joinToString("\n") { r -> (0 until 5).joinToString("") { c -> if (Cell(r, c) in cells) "#" else "." } }

    private fun assertCells(expected: Set<Cell>, o: Orientation) =
        assertEquals("orientation $o", render(expected), render(TumblingE.cells(o)))

    @Test
    fun `RIGHT is a normal E, limbs pointing right`() = assertCells(
        picture(
            "#####",
            "#....",
            "#####",
            "#....",
            "#####",
        ), Orientation.RIGHT,
    )

    @Test
    fun `LEFT is mirrored, limbs pointing left`() = assertCells(
        picture(
            "#####",
            "....#",
            "#####",
            "....#",
            "#####",
        ), Orientation.LEFT,
    )

    @Test
    fun `UP has limbs pointing up`() = assertCells(
        picture(
            "#.#.#",
            "#.#.#",
            "#.#.#",
            "#.#.#",
            "#####",
        ), Orientation.UP,
    )

    @Test
    fun `DOWN has limbs pointing down`() = assertCells(
        picture(
            "#####",
            "#.#.#",
            "#.#.#",
            "#.#.#",
            "#.#.#",
        ), Orientation.DOWN,
    )

    @Test
    fun `each orientation is a 90 degree rotation of the previous`() {
        // Rotating clockwise: RIGHT -> DOWN -> LEFT -> UP -> RIGHT
        fun cw(cells: Set<Cell>) = cells.map { Cell(it.col, 4 - it.row) }.toSet()
        assertEquals(TumblingE.cells(Orientation.DOWN), cw(TumblingE.cells(Orientation.RIGHT)))
        assertEquals(TumblingE.cells(Orientation.LEFT), cw(TumblingE.cells(Orientation.DOWN)))
        assertEquals(TumblingE.cells(Orientation.UP), cw(TumblingE.cells(Orientation.LEFT)))
        assertEquals(TumblingE.cells(Orientation.RIGHT), cw(TumblingE.cells(Orientation.UP)))
    }

    @Test
    fun `every orientation has 17 cells inside the 5x5 grid`() {
        Orientation.entries.forEach { o ->
            val cells = TumblingE.cells(o)
            assertEquals(17, cells.size)
            cells.forEach { assertEquals(true, it.row in 0..4 && it.col in 0..4) }
        }
    }
}
