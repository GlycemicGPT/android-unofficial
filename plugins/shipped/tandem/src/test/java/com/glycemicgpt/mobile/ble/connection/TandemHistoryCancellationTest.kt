// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.ble.connection

import android.util.Base64
import com.glycemicgpt.mobile.domain.pump.DebugLogger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

/**
 * A cancelled poll loop must stay cancelled, and must never come back to the caller as a pump
 * that cannot make progress (GLY-250).
 *
 * A history fetch that cannot verify what it received reports a failure, so that a backfill which
 * has silently halted reaches the poll loop's failure ladder instead of reading as "caught up".
 * That is only worth anything if the failures arriving there are the pump's. `CancellationException`
 * extends `Exception`, so a blanket catch around a BLE read takes the connection watcher's
 * teardown -- which fires on every non-CONNECTED state, i.e. on the ordinary BLE flap this work
 * exists for -- and re-reports it as a stall. A window can sit in that read for the whole stream
 * timeout, so the exposure is most of a backfill.
 *
 * The other half: the pump going quiet is signalled with a `CancellationException` too
 * (`withTimeout`), and that one IS a stall. So neither the exception type alone nor a blanket
 * rethrow is the answer -- the driver asks its own job whether it is still alive. These tests
 * fail if either direction is lost.
 */
class TandemHistoryCancellationTest {

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

    @Test
    fun `a fetch cancelled mid-window propagates instead of being reported as a stall`() = runTest {
        val parked = CompletableDeferred<Unit>()
        coEvery { connectionManager.requestHistoryLogStream(any(), any()) } coAnswers {
            parked.complete(Unit)
            awaitCancellation()
        }

        var outcome: Any? = null
        val fetch = launch {
            outcome = try {
                driver.getHistoryLogs(sinceSequence = 0)
            } catch (e: Throwable) {
                e
            }
        }
        parked.await()
        // The user walks out of range: the connection watcher cancels the poll loops.
        fetch.cancelAndJoin()

        assertTrue(
            "the loop's own cancellation must reach the caller as cancellation, not as a " +
                "history-log failure on the ladder (was $outcome)",
            outcome is CancellationException,
        )
    }

    @Test
    fun `a stream that times out is still a stall, and not a cancellation`() = runTest {
        // The pump stops answering FFF8 mid-window with the loop perfectly healthy. That is a
        // stall and belongs on the ladder -- even though `withTimeout` signals it with a
        // `CancellationException` subclass, which is why the type alone cannot decide this.
        coEvery { connectionManager.requestHistoryLogStream(any(), any()) } coAnswers {
            withTimeout(1) { awaitCancellation() }
        }

        val result = driver.getHistoryLogs(sinceSequence = 0)

        assertTrue("a pump that stops answering must not read as caught up", result.isFailure)
        assertFalse(
            "a Result carrying a cancellation type tears the poll loop down silently instead " +
                "of reporting the read",
            result.exceptionOrNull() is CancellationException,
        )
    }

    @Test
    fun `a range read cancelled mid-flight propagates instead of failing the fetch`() = runTest {
        // Same shape one message earlier: the opcode-59 index-range read that opens every fetch.
        val parked = CompletableDeferred<Unit>()
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } coAnswers {
            parked.complete(Unit)
            awaitCancellation()
        }

        var outcome: Any? = null
        val fetch = launch {
            outcome = try {
                driver.getHistoryLogs(sinceSequence = 0)
            } catch (e: Throwable) {
                e
            }
        }
        parked.await()
        fetch.cancelAndJoin()

        assertTrue(
            "a cancelled range read must propagate, not come back as a failed read (was $outcome)",
            outcome is CancellationException,
        )
    }

    @Test
    fun `a range read that times out fails without handing back a cancellation`() = runTest {
        coEvery { connectionManager.sendStatusRequest(any(), any(), any()) } coAnswers {
            withTimeout(1) { awaitCancellation() }
        }

        val result = driver.getHistoryLogs(sinceSequence = 0)

        assertTrue(result.isFailure)
        assertFalse(
            "the caller rethrows this to report the step; a cancellation type there kills the " +
                "slow loop instead, silently and until the next reconnect",
            result.exceptionOrNull() is CancellationException,
        )
    }
}
