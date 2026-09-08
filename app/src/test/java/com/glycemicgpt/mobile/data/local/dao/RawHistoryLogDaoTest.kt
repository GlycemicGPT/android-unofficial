// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.local.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.glycemicgpt.mobile.data.local.AppDatabase
import com.glycemicgpt.mobile.data.local.entity.RawHistoryLogEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-SQL proof of the two raw-history statements the `processed` flag changed the meaning of
 * (GLY-250): what a committed batch is allowed to mark, and what the stand-down purge is allowed
 * to delete. Both are one-line SQL predicates whose failure mode is silent data loss, so they are
 * pinned against an actual database rather than a mock's verb.
 */
// Plain Application: the manifest's @HiltAndroidApp class would pull keystore-backed
// injection into a JVM test that only needs a Context for an in-memory database.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RawHistoryLogDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RawHistoryLogDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.rawHistoryLogDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(sequenceNumber: Int, sentToBackend: Boolean = false) = RawHistoryLogEntity(
        sequenceNumber = sequenceNumber,
        rawBytesB64 = "cmF3",
        eventTypeId = 399,
        pumpTimeSeconds = 572_000_000L + sequenceNumber,
        sentToBackend = sentToBackend,
        createdAtMs = 1_000L + sequenceNumber,
        processed = false,
    )

    private fun processedBySequence(): List<Pair<Int, Boolean>> =
        db.query(
            "SELECT sequenceNumber, processed FROM raw_history_logs ORDER BY sequenceNumber",
            emptyArray(),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0) to (cursor.getInt(1) == 1)) }
        }

    @Test
    fun `markProcessed touches exactly the sequences it is given`() = runTest {
        dao.insertAll(listOf(row(100), row(200), row(300), row(400)))

        val marked = dao.markProcessed(listOf(200, 400))

        assertEquals(2, marked)
        // Not a range: a batch is whatever the driver handed over, and the rows in between may
        // belong to a batch that has not been derived at all.
        assertEquals(
            listOf(100 to false, 200 to true, 300 to false, 400 to true),
            processedBySequence(),
        )
    }

    @Test
    fun `markProcessed is idempotent and reports nothing left to do`() = runTest {
        dao.insertAll(listOf(row(100)))
        dao.markProcessed(listOf(100))

        assertEquals(1, dao.markProcessed(listOf(100)))
        assertEquals(0, dao.countUnprocessed())
    }

    @Test
    fun `getUnprocessed returns the re-derivation input, oldest first`() = runTest {
        dao.insertAll(listOf(row(300), row(100), row(200)))
        dao.markProcessed(listOf(200))

        assertEquals(listOf(100, 300), dao.getUnprocessed().map { it.sequenceNumber })
        assertEquals(2, dao.countUnprocessed())
    }

    @Test
    fun `the stand-down purge spares rows that still owe derived records`() = runTest {
        dao.insertAll(listOf(row(100), row(200), row(300), row(400)))
        dao.markProcessed(listOf(100, 300))

        val purged = dao.deleteAllButMaxSequence()

        // 200 survives because nothing has derived it yet -- it is the input a re-derivation
        // pass rebuilds from, not dead weight. Deleting it is how the recovery copy of data lost
        // to the pre-GLY-250 cursor bug would disappear on a BLE-only device, on every sweep.
        // 400 survives as the highest sequence, as it always did.
        assertEquals(2, purged)
        assertEquals(listOf(200 to false, 400 to false), processedBySequence())
    }

    @Test
    fun `the stand-down purge still clears delivered, derived rows`() = runTest {
        dao.insertAll(listOf(row(100, sentToBackend = true), row(200), row(300)))
        dao.markProcessed(listOf(100, 200, 300))

        assertEquals(2, dao.deleteAllButMaxSequence())
        assertEquals(listOf(300 to true), processedBySequence())
    }
}
