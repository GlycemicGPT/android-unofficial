// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.ble.connection

import android.util.Base64
import com.glycemicgpt.mobile.domain.pump.DebugLogger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The driver's progressive scan position must be subordinate to the caller's durable cursor
 * (GLY-250).
 *
 * The pump is paginated by record INDEX, and this driver used to move its scan position the
 * moment it handed a window over. Between that hand-over and the caller committing the records
 * sits a BLE flap -- the connection watcher cancels the poll loops on every non-CONNECTED state
 * -- so the next scan resumed ABOVE a batch nobody had persisted, and those records were gone
 * for good while the cursor kept moving. The position now moves only when the caller says the
 * batch is durable.
 */
class TandemHistoryScanPositionTest {

    private val connectionManager = mockk<BleConnectionManager>(relaxed = true)
    private val debugLogger = mockk<DebugLogger>(relaxed = true)
    private val driver = TandemBleDriver(connectionManager, debugLogger)

    @Before
    fun setUp() {
        // android.util.Base64 is a stub in JVM unit tests; the record parser encodes with it.
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } returns
            historyLogStatusCargo(firstIndex = 1, lastIndex = 1_000)
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    /** Opcode 59 reply: numEntries, firstSeq, lastSeq -- all uint32 LE. */
    private fun historyLogStatusCargo(firstIndex: Int, lastIndex: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(lastIndex - firstIndex + 1)
            .putInt(firstIndex)
            .putInt(lastIndex)
            .array()

    /** One 26-byte FFF8 stream record: eventTypeId, pumpTime, record index, then payload. */
    private fun streamRecord(index: Int, eventTypeId: Int = 399): ByteArray =
        ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(eventTypeId.toShort())
            .putInt(572_000_000)
            .putInt(index)
            .put(ByteArray(16))
            .array()

    /** The start index each opcode-60 request asked for, in order. */
    private fun captureFetchStarts(): MutableList<Int> {
        val starts = mutableListOf<Int>()
        val cargo = slot<ByteArray>()
        coEvery { connectionManager.requestHistoryLogStream(capture(cargo), any()) } answers {
            val start = ByteBuffer.wrap(cargo.captured).order(ByteOrder.LITTLE_ENDIAN).int
            starts += start
            // One packet per request, then nothing, so each call fetches exactly one batch.
            if (starts.size % 2 == 1) listOf(streamRecord(start)) else emptyList()
        }
        return starts
    }

    @Test
    fun `an unacknowledged fetch is re-served rather than skipped`() = runTest {
        val starts = captureFetchStarts()

        driver.getHistoryLogs(sinceSequence = 0)
        val firstStart = starts.first()

        // No acknowledgement: this is the BLE flap, cancelling the poll loop between the fetch
        // and the commit. The next scan has to offer the same window again.
        starts.clear()
        driver.getHistoryLogs(sinceSequence = 0)

        assertEquals(firstStart, starts.first())
    }

    @Test
    fun `an acknowledged fetch moves the scan on`() = runTest {
        val starts = captureFetchStarts()

        driver.getHistoryLogs(sinceSequence = 0)
        val firstStart = starts.first()
        driver.acknowledgeHistoryLogs()

        starts.clear()
        driver.getHistoryLogs(sinceSequence = 0)

        // Otherwise a progressive scanner would re-read the same window forever and never reach
        // the records that ARE new.
        assertEquals(firstStart + TandemBleDriver.HISTORY_BATCH_SIZE, starts.first())
    }

    @Test
    fun `acknowledging twice does not move the scan twice`() = runTest {
        val starts = captureFetchStarts()

        driver.getHistoryLogs(sinceSequence = 0)
        val firstStart = starts.first()
        driver.acknowledgeHistoryLogs()
        driver.acknowledgeHistoryLogs()

        starts.clear()
        driver.getHistoryLogs(sinceSequence = 0)

        assertEquals(firstStart + TandemBleDriver.HISTORY_BATCH_SIZE, starts.first())
    }

    @Test
    fun `an unknown event type is kept, so the batch it sits in has no hole`() = runTest {
        val cargo = slot<ByteArray>()
        coEvery { connectionManager.requestHistoryLogStream(capture(cargo), any()) } answers {
            val start = ByteBuffer.wrap(cargo.captured).order(ByteOrder.LITTLE_ENDIAN).int
            if (start == 1) {
                listOf(
                    streamRecord(index = 1) +
                        // An id this build does not know: it used to be dropped here, which put
                        // a hole in the middle of the batch. The caller's cursor advances to
                        // max(sequenceNumber), so the record was skipped permanently AND its raw
                        // bytes never stored -- nothing left to re-derive from.
                        streamRecord(index = 2, eventTypeId = 900) +
                        streamRecord(index = 3),
                )
            } else {
                emptyList()
            }
        }

        val records = driver.getHistoryLogs(sinceSequence = 0).getOrThrow()

        assertEquals(listOf(1, 2, 3), records.map { it.sequenceNumber })
        assertEquals(900, records[1].eventTypeId)
    }

    @Test
    fun `a batch with an undecodable packet in the middle is dropped whole and retried`() = runTest {
        val starts = mutableListOf<Int>()
        val cargo = slot<ByteArray>()
        coEvery { connectionManager.requestHistoryLogStream(capture(cargo), any()) } answers {
            val start = ByteBuffer.wrap(cargo.captured).order(ByteOrder.LITTLE_ENDIAN).int
            starts += start
            listOf(
                listOf(streamRecord(index = start)),
                // Neither the 26-byte nor the 18-byte layout: this packet yields nothing.
                listOf(ByteArray(7)),
                listOf(streamRecord(index = start + 2)),
            ).flatten()
        }

        val records = driver.getHistoryLogs(sinceSequence = 0).getOrThrow()

        // Handing over the records either side of the hole would step the caller's cursor
        // straight over the missing one, with its raw bytes never stored.
        assertEquals(emptyList<Int>(), records.map { it.sequenceNumber })

        // ...and the window is offered again next time rather than being written off.
        val firstStart = starts.first()
        starts.clear()
        driver.acknowledgeHistoryLogs()
        driver.getHistoryLogs(sinceSequence = 0)
        assertEquals(firstStart, starts.first())
    }
}
