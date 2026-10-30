package com.liuliang.board

import com.liuliang.board.ui.FrostProfile
import org.junit.Assert.*
import org.junit.Test

class FrostProfileTest {
    @Test fun everyPositionHasOpaqueNormalizedAdjacentWeights() {
        for (i in 0..10000) {
            val w = FrostProfile.weights(i / 10000f)
            assertEquals(1f, w.sum(), .000001f)
            assertTrue(w.all { it in 0f..1f })
            val active = w.indices.filter { w[it] > 0f }
            assertTrue(active.size <= 2)
            if (active.size == 2) assertEquals(1, active[1] - active[0])
        }
    }
    @Test fun plateauIsFullyBlurredAndEdgeIsExactlyOriginal() {
        assertEquals(1f, FrostProfile.strength(30f, 160f, 64f, true), 0f)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f), FrostProfile.weights(1f))
        assertEquals(listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f), FrostProfile.weights(0f))
        assertEquals(0f, FrostProfile.strength(160f, 160f, 64f, true), 0f)
    }
    @Test fun topAndBottomAreMirrorsWithContinuousWeights() {
        var previous = FrostProfile.weights(1f)
        for (i in 0..16000) {
            val y = i / 100f
            val top = FrostProfile.strength(y, 160f, 64f, true)
            assertEquals(top, FrostProfile.strength(160f - y, 160f, 64f, false), .000001f)
            val next = FrostProfile.weights(top)
            assertTrue(next.zip(previous).all { (a, b) -> kotlin.math.abs(a - b) < .01f })
            previous = next
        }
    }
}
