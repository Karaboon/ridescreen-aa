package io.github.karaboon.ridescreendash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H264ParseTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun splitsMixedStartCodes() {
        // 4-byte SC + SPS(0x67), 3-byte SC + PPS(0x68), 4-byte SC + IDR(0x65)
        val au = b(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 9, 9)
        val nals = H264Player.nalUnits(au)
        assertEquals(listOf(4 to 7, 10 to 12, 16 to 19), nals)
    }

    @Test
    fun extractsSpsAndPpsWithStartCodes() {
        val au = b(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 9, 9)
        val (sps, pps) = H264Player.parameterSets(au)!!
        assertArrayEquals(b(0, 0, 0, 1, 0x67, 1, 2), sps)
        assertArrayEquals(b(0, 0, 0, 1, 0x68, 3), pps)
    }

    @Test
    fun pFrameHasNoParameterSets() {
        assertNull(H264Player.parameterSets(b(0, 0, 0, 1, 0x41, 5, 6, 7)))
    }
}
