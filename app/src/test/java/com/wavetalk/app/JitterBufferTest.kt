package com.wavetalk.app

import com.wavetalk.app.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    @Test
    fun `push and poll preserve order`() {
        val buffer = JitterBuffer(startThreshold = 1, maxDepth = 8)
        val f1 = byteArrayOf(1)
        val f2 = byteArrayOf(2)
        val f3 = byteArrayOf(3)
        buffer.push(f1); buffer.push(f2); buffer.push(f3)
        assertEquals(f1, buffer.poll(1))
        assertEquals(f2, buffer.poll(1))
        assertEquals(f3, buffer.poll(1))
    }

    @Test
    fun `poll on empty returns null after timeout`() {
        val buffer = JitterBuffer()
        val start = System.nanoTime()
        assertNull(buffer.poll(30))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("poll should wait, took ${elapsedMs}ms", elapsedMs >= 20)
    }

    @Test
    fun `overflow drops the oldest frames`() {
        val buffer = JitterBuffer(startThreshold = 1, maxDepth = 4)
        repeat(8) { buffer.push(byteArrayOf(it.toByte())) }
        assertEquals(4, buffer.depth)
        // Oldest were dropped: first pollable frame is index 4.
        assertEquals(4.toByte(), buffer.poll(1)!![0])
    }

    @Test
    fun `readyToPlay respects threshold and adaptive extra`() {
        val buffer = JitterBuffer(startThreshold = 3, maxDepth = 8)
        buffer.push(byteArrayOf(1))
        buffer.push(byteArrayOf(2))
        assertTrue(!buffer.readyToPlay())
        buffer.push(byteArrayOf(3))
        assertTrue(buffer.readyToPlay())

        buffer.onUnderrun()
        buffer.clear()
        buffer.push(byteArrayOf(1))
        buffer.push(byteArrayOf(2))
        assertTrue(!buffer.readyToPlay()) // needs 4 now
        buffer.push(byteArrayOf(3))
        buffer.push(byteArrayOf(4))
        assertTrue(buffer.readyToPlay())

        buffer.onHealthy()
        buffer.push(byteArrayOf(5))
        buffer.clear()
        buffer.push(byteArrayOf(1))
        buffer.push(byteArrayOf(2))
        buffer.push(byteArrayOf(3))
        assertTrue(buffer.readyToPlay())
    }

    @Test
    fun `poll with timeout returns a frame eventually`() {
        val buffer = JitterBuffer(startThreshold = 1)
        Thread {
            Thread.sleep(50)
            buffer.push(byteArrayOf(9))
        }.start()
        val frame = buffer.poll(500)
        assertNotNull(frame)
        assertEquals(9.toByte(), frame!![0])
    }
}
