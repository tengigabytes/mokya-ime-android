// SPDX-License-Identifier: Apache-2.0
package io.github.tengigabytes.mokyaime.input

import io.github.tengigabytes.mokyaime.input.CandidateGrid.Cell
import kotlin.test.Test
import kotlin.test.assertEquals

class CandidateGridTest {

    @Test
    fun candidatesWrapWhenTheRowIsFull() {
        // 100 px candidates in a 250 px row: two per row, five rows of two.
        val cells = CandidateGrid.layout(List(10) { 100 }, rowWidth = 250)
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4), cells.map { it.row })
        assertEquals(5, CandidateGrid.rowCount(cells))
    }

    @Test
    fun fullRowsAreStretchedToTheRowWidth() {
        val cells = CandidateGrid.layout(listOf(100, 100, 100), rowWidth = 250)
        assertEquals(Cell(0, 0, 125), cells[0])
        assertEquals(Cell(0, 125, 125), cells[1])
    }

    @Test
    fun spareOddPixelsGoToTheFirstCells() {
        val cells = CandidateGrid.layout(listOf(80, 80, 80, 200), rowWidth = 250)
        assertEquals(listOf(84, 83, 83), cells.take(3).map { it.width })
        assertEquals(250, cells.take(3).sumOf { it.width })
        assertEquals(listOf(0, 84, 167), cells.take(3).map { it.left })
    }

    @Test
    fun theLastRowKeepsItsOwnWidths() {
        val cells = CandidateGrid.layout(listOf(100, 100, 100), rowWidth = 250)
        assertEquals(Cell(1, 0, 100), cells[2])
        // Everything fits in one row: nothing is stretched.
        assertEquals(listOf(Cell(0, 0, 60), Cell(0, 60, 70)), CandidateGrid.layout(listOf(60, 70), rowWidth = 250))
    }

    @Test
    fun candidateWiderThanTheRowGetsItsOwnRow() {
        val cells = CandidateGrid.layout(listOf(100, 400, 100), rowWidth = 250)
        assertEquals(listOf(Cell(0, 0, 250), Cell(1, 0, 250), Cell(2, 0, 100)), cells)
    }

    @Test
    fun nothingToLayOut() {
        assertEquals(emptyList(), CandidateGrid.layout(emptyList(), rowWidth = 250))
        assertEquals(0, CandidateGrid.rowCount(emptyList()))
        // Not measured yet: no negative widths.
        assertEquals(listOf(Cell(0, 0, 0), Cell(0, 0, 0)), CandidateGrid.layout(listOf(100, 100), rowWidth = -1))
    }
}
