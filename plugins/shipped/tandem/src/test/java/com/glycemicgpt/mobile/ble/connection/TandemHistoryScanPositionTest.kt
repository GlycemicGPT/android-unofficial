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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The driver's progressive scan position must be subordinate both to what the pump actually
 * delivered and to the caller's durable cursor (GLY-250).
 *
 * The pump is paginated by record INDEX, and this driver used to move its scan position the
 * moment it handed a window over. Between that hand-over and the caller committing the records
 * sits a BLE flap -- the connection watcher cancels the poll loops on every non-CONNECTED state
 * -- so the next scan resumed ABOVE a batch nobody had persisted, and those records were gone
 * for good while the cursor kept moving. The position now moves only when the caller says the
 * batch is durable.
 *
 * The second half is what an acknowledgement is allowed to move it OVER. Asking for twenty
 * indices is not the same as receiving twenty records, and the driver used to advance by the
 * request either way: a notification lost in the middle of a window, an undecodable last one, or
 * a short answer all left records behind while the scan -- and then the caller's cursor -- moved
 * past them. It now advances only over the run of indices the pump proved it delivered, and
 * refuses an answer carrying an index it never asked for.
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
            // One record per request, then nothing, so each call fetches exactly one record and
            // stops. A one-record answer to a twenty-index request moves the scan by ONE: the
            // other nineteen were asked for, not delivered.
            if (starts.size % 2 == 1) listOf(streamRecord(start)) else emptyList()
        }
        return starts
    }

    /** Answers `packets` to the first request and nothing to any later one, recording starts. */
    private fun respondOnceWith(packets: (Int) -> List<ByteArray>): MutableList<Int> {
        val starts = mutableListOf<Int>()
        val cargo = slot<ByteArray>()
        coEvery { connectionManager.requestHistoryLogStream(capture(cargo), any()) } answers {
            val start = ByteBuffer.wrap(cargo.captured).order(ByteOrder.LITTLE_ENDIAN).int
            starts += start
            if (starts.size == 1) packets(start) else emptyList()
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
        // the records that ARE new. One record arrived, so the scan moves by one -- not by the
        // twenty indices the request asked for, which is what it used to do.
        assertEquals(firstStart + 1, starts.first())
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

        assertEquals(firstStart + 1, starts.first())
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
    fun `a hole in a window stops the hand-over at the hole`() = runTest {
        val starts = respondOnceWith { start ->
            listOf(
                streamRecord(index = start),
                // Neither the 26-byte nor the 18-byte layout: this packet yields nothing, which
                // is what a notification lost in transit looks like from here.
                ByteArray(7),
                streamRecord(index = start + 2),
            )
        }

        val records = driver.getHistoryLogs(sinceSequence = 0).getOrThrow()

        // Handing over the records either side of the hole would step the caller's cursor
        // straight over the missing one, with its raw bytes never stored.
        val firstStart = starts.first()
        assertEquals(listOf(firstStart), records.map { it.sequenceNumber })

        // ...and everything from the hole on is offered again rather than written off.
        starts.clear()
        driver.acknowledgeHistoryLogs()
        driver.getHistoryLogs(sinceSequence = 0)
        assertEquals(firstStart + 1, starts.first())
    }

    @Test
    fun `a window that comes back short resumes where it ran out`() = runTest {
        // Twenty indices requested, ten delivered: the last notification never arrived, so there
        // is no hole to see -- the answer just ends early. Advancing by the request would write
        // off the other ten, which is the trailing-loss half of the same defect.
        val delivered = 10
        val starts = respondOnceWith { start ->
            (0 until delivered).map { streamRecord(index = start + it) }
        }

        val records = driver.getHistoryLogs(sinceSequence = 0).getOrThrow()

        val firstStart = starts.first()
        assertEquals((0 until delivered).map { firstStart + it }, records.map { it.sequenceNumber })

        starts.clear()
        driver.acknowledgeHistoryLogs()
        driver.getHistoryLogs(sinceSequence = 0)
        assertEquals(firstStart + delivered, starts.first())
    }

    @Test
    fun `an index outside the requested window is refused, answer and all`() = runTest {
        // What a misframed FFF8 packet decodes to: the 26-byte layout "structurally matches" any
        // cargo whose length divides by 26, so an 18-byte-layout packet of the right size comes
        // out as records with garbage indices. The caller's cursor advances to the highest one it
        // is given and can never come back, so one of these landing would stop the backfill
        // committing anything, forever.
        val starts = respondOnceWith { start ->
            listOf(
                streamRecord(index = start),
                streamRecord(index = Int.MAX_VALUE - 5),
            )
        }

        val result = driver.getHistoryLogs(sinceSequence = 0)

        assertTrue("an answer this driver cannot vouch for must not be handed over", result.isFailure)
        val firstStart = starts.first()

        // Nothing was proven, so an acknowledgement has nothing to promote and the window is
        // requested again.
        starts.clear()
        driver.acknowledgeHistoryLogs()
        driver.getHistoryLogs(sinceSequence = 0)
        assertEquals(firstStart, starts.first())
    }

    @Test
    fun `a pump whose range starts at zero still makes progress`() = runTest {
        // Index 0 is the stream parser's padding sentinel, so a window starting there could never
        // prove it received its own first index -- a fresh pump would stall on its first record
        // instead of syncing. The window starts at 1 and index 0 is simply never requested.
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } returns
            historyLogStatusCargo(firstIndex = 0, lastIndex = 100)
        val starts = respondOnceWith { start ->
            (0 until TandemBleDriver.HISTORY_BATCH_SIZE).map { streamRecord(index = start + it) }
        }

        val records = driver.getHistoryLogs(sinceSequence = 0).getOrThrow()

        assertEquals(TandemBleDriver.FIRST_USABLE_HISTORY_INDEX, starts.first())
        assertEquals(TandemBleDriver.HISTORY_BATCH_SIZE, records.size)
    }

    @Test
    fun `a scan that proves nothing fails instead of reporting it is caught up`() = runTest {
        // Every packet undecodable, every cycle. Returning an empty success here reads exactly
        // like "no new records", so the backfill halts indefinitely with nothing to report it.
        coEvery { connectionManager.requestHistoryLogStream(any(), any()) } returns listOf(ByteArray(7))

        val result = driver.getHistoryLogs(sinceSequence = 0)

        assertTrue("a stalled scan must reach the caller's failure ladder", result.isFailure)
    }

    @Test
    fun `an empty log is an empty success, and promotes nothing`() = runTest {
        val starts = captureFetchStarts()
        driver.getHistoryLogs(sinceSequence = 0)
        val firstStart = starts.first()

        // The poll loop is cancelled before the batch commits, so that fetch is never
        // acknowledged. Then the pump reports it holds no records at all.
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } returns
            historyLogStatusCargo(firstIndex = 5, lastIndex = 4)
        val empty = driver.getHistoryLogs(sinceSequence = 0)
        assertEquals(emptyList<Int>(), empty.getOrThrow().map { it.sequenceNumber })

        // The caller acknowledges an empty answer -- there was nothing to persist. That must not
        // promote the earlier fetch's position, which no one ever committed.
        driver.acknowledgeHistoryLogs()
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } returns
            historyLogStatusCargo(firstIndex = 1, lastIndex = 1_000)
        starts.clear()
        driver.getHistoryLogs(sinceSequence = 0)

        assertEquals(firstStart, starts.first())
    }

    @Test
    fun `a range that ends below the first usable index promotes nothing either`() = runTest {
        // Get the scan off its starting index first, so a rewind is visible.
        val starts = captureFetchStarts()
        driver.getHistoryLogs(sinceSequence = 0)
        driver.acknowledgeHistoryLogs()
        val acknowledged = starts.first() + 1

        // The other shape of "the pump holds nothing": a well-formed range that is entirely below
        // the first index this driver may request. `firstSeq = lastSeq = 0` satisfies
        // `lastSeq >= firstSeq`, so it used to fall through to the scan -- which then requested no
        // window at all, because the window starts at index 1 -- and still proposed a position.
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } returns
            historyLogStatusCargo(firstIndex = 0, lastIndex = 0)
        starts.clear()
        val empty = driver.getHistoryLogs(sinceSequence = 0)
        assertEquals(emptyList<Int>(), empty.getOrThrow().map { it.sequenceNumber })
        assertEquals("no window may be requested for a range this driver cannot read", 0, starts.size)

        // The caller acknowledges an empty answer -- there was nothing to persist. That must not
        // promote a position, and in particular must not drag the scan back to the start of a
        // window this call never requested.
        driver.acknowledgeHistoryLogs()
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } returns
            historyLogStatusCargo(firstIndex = 1, lastIndex = 1_000)
        starts.clear()
        driver.getHistoryLogs(sinceSequence = 0)

        assertEquals(acknowledged, starts.first())
    }
}
