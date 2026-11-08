package com.alefinot.dashboardpp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProtocolTest {

    private fun payload(size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in out.indices) out[i] = (i % 251).toByte()
        return out
    }

    @Test
    fun oneFrameCarriesFirstAndLast() {
        val frames = BleProto.frames(BleProto.T_REQ, 7, "hi".toByteArray(), mtu = 517)

        assertEquals(1, frames.size)
        val frame = frames[0]
        assertEquals(BleProto.VERSION.toByte(), frame[0])
        assertEquals(BleProto.T_REQ.toByte(), frame[1])
        assertEquals((BleProto.FLAG_FIRST or BleProto.FLAG_LAST).toByte(), frame[2])
        assertEquals(7, (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8))
        assertEquals(2, (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8))
        assertEquals("hi", String(frame, BleProto.HEADER, 2))
    }

    @Test
    fun fragmentSizeFollowsTheMtuAndNeverExceedsThePayloadBuffer() {
        // 517 byte ATT MTU: 3 bytes of ATT header and 8 of frame header leave 506.
        assertEquals(506, BleProto.fragmentPayload(517))
        assertEquals(236, BleProto.fragmentPayload(247))
        assertEquals(1, BleProto.fragmentPayload(11))
        assertEquals(BleProto.MAX_FRAGMENT, BleProto.fragmentPayload(1024))
    }

    @Test
    fun aLongMessageIsSplitAndReassemblesByteForByte() {
        val body = payload(1200)
        val frames = BleProto.frames(BleProto.T_RESP, 42, body, mtu = 517)

        assertEquals(3, frames.size)
        assertTrue((frames.first()[2].toInt() and BleProto.FLAG_FIRST) != 0)
        assertTrue((frames.last()[2].toInt() and BleProto.FLAG_LAST) != 0)
        assertTrue((frames[1][2].toInt() and BleProto.FLAG_FIRST) == 0)
        assertTrue((frames[1][2].toInt() and BleProto.FLAG_LAST) == 0)

        val assembler = FrameAssembler()
        var message: FrameAssembler.Message? = null
        for (frame in frames) message = assembler.accept(frame) ?: message

        val done = message!!
        assertEquals(BleProto.T_RESP, done.type)
        assertEquals(42, done.seq)
        assertTrue(body.contentEquals(done.payload))
    }

    @Test
    fun twoCharacteristicsNeedTwoAssemblers() {
        val telemetry = FrameAssembler()
        val rpc = FrameAssembler()
        val telemFrames = BleProto.frames(BleProto.T_TELEM, 1, payload(600), mtu = 247)
        val rpcFrames = BleProto.frames(BleProto.T_RESP, 2, payload(600), mtu = 247)

        assertNull(telemetry.accept(telemFrames[0]))
        assertNull(rpc.accept(rpcFrames[0]))
        assertNull(telemetry.accept(telemFrames[1]))
        assertNull(rpc.accept(rpcFrames[1]))

        val t = telemetry.accept(telemFrames[2])!!
        val r = rpc.accept(rpcFrames[2])!!
        assertEquals(BleProto.T_TELEM, t.type)
        assertEquals(BleProto.T_RESP, r.type)
        assertTrue(payload(600).contentEquals(t.payload))
        assertTrue(payload(600).contentEquals(r.payload))
    }

    @Test
    fun aNewFirstFragmentReplacesAHalfOpenMessage() {
        val assembler = FrameAssembler()
        val old = BleProto.frames(BleProto.T_RESP, 1, payload(900), mtu = 247)
        val new = BleProto.frames(BleProto.T_RESP, 2, "fresh".toByteArray(), mtu = 247)

        assertNull(assembler.accept(old[0]))
        val message = assembler.accept(new[0])!!
        assertEquals("fresh", String(message.payload))
    }

    @Test
    fun fragmentsThatDoNotMatchTheOpenSequenceAreIgnored() {
        val assembler = FrameAssembler()
        val frames = BleProto.frames(BleProto.T_RESP, 10, payload(900), mtu = 247)
        assertNull(assembler.accept(frames[0]))

        val stranger = BleProto.frames(BleProto.T_RESP, 11, payload(500), mtu = 247)
        // Not a FIRST, and not the sequence the assembler is holding: dropped.
        assertNull(assembler.accept(stranger[1]))
        // The open message still completes from its own remaining fragments.
        assertNull(assembler.accept(frames[1]))
        assertNull(assembler.accept(frames[2]))
        val done = assembler.accept(frames[3])!!
        assertEquals(10, done.seq)
    }

    @Test
    fun malformedFramesAreDropped() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(ByteArray(3)))
        assertNull(assembler.accept(ByteArray(BleProto.HEADER)))

        val badVersion = ByteArray(BleProto.HEADER + 1)
        badVersion[0] = 2
        badVersion[2] = BleProto.FLAG_FIRST.toByte()
        assertNull(assembler.accept(badVersion))

        // len claims more payload than the frame carries
        val lying = ByteArray(BleProto.HEADER)
        lying[2] = (BleProto.FLAG_FIRST or BleProto.FLAG_LAST).toByte()
        lying[6] = 9
        assertNull(assembler.accept(lying))
    }

    @Test
    fun anOversizedMessageIsRefusedInsteadOfGrowingForever() {
        val assembler = FrameAssembler()
        val big = BleProto.frames(BleProto.T_RESP, 1, payload(9000), mtu = 517)
        assertEquals(18, big.size)
        for (i in 0 until 16) assertNull(assembler.accept(big[i]))
        // This one would take the total to 8602 bytes: refused, and the slot closes.
        assertNull(assembler.accept(big[16]))
        assertNull(assembler.accept(big[17]))
    }

    @Test
    fun resetForgetsAHalfReceivedMessage() {
        val assembler = FrameAssembler()
        val frames = BleProto.frames(BleProto.T_RESP, 3, payload(900), mtu = 247)
        assertNull(assembler.accept(frames[0]))
        assembler.reset()
        assertNull(assembler.accept(frames[1]))
        assertNull(assembler.accept(frames[2]))
    }

    @Test
    fun emptyPayloadStillProducesOneFrame() {
        val frames = BleProto.frames(BleProto.T_PING, 1, ByteArray(0), mtu = 517)
        assertEquals(1, frames.size)
        assertEquals(BleProto.HEADER, frames[0].size)
        val message = FrameAssembler().accept(frames[0])!!
        assertEquals(0, message.payload.size)
        assertEquals(BleProto.T_PING, message.type)
    }
}
