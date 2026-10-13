package app.drishti.core

data class Cell(val row: Int, val col: Int)

/** The tumbling E on a 5x5 grid: each limb, gap and the spine are one cell thick. */
object TumblingE {
    private val right: Set<Cell> = buildSet {
        for (r in 0 until OptotypeMath.GRID) add(Cell(r, 0)) // spine
        for (r in listOf(0, 2, 4)) for (c in 0 until OptotypeMath.GRID) add(Cell(r, c)) // limbs
    }

    private val byOrientation: Map<Orientation, Set<Cell>> = run {
        val last = OptotypeMath.GRID - 1
        fun cw(cells: Set<Cell>) = cells.map { Cell(it.col, last - it.row) }.toSet()
        val down = cw(right)
        val left = cw(down)
        val up = cw(left)
        mapOf(Orientation.RIGHT to right, Orientation.DOWN to down, Orientation.LEFT to left, Orientation.UP to up)
    }

    fun cells(orientation: Orientation): Set<Cell> = byOrientation.getValue(orientation)
}
