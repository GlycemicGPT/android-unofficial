// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.weardevice.data

import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The watch has no Sentry and plants no Timber tree in release, so the persisted record is the
 * only trace a `dataSync` timeout leaves behind (GLY-244). It is worth pinning.
 *
 * `:wear-device` has no Robolectric, so SharedPreferences is faked with a map-backed mock rather
 * than adding a test dependency (which would touch the lockfile).
 */
class FgsTimeoutReporterTest {

    private val stored = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        stored.clear()
        FgsTimeoutReporter.setStoreForTest(mapBackedPrefs())
    }

    @After
    fun tearDown() {
        FgsTimeoutReporter.setStoreForTest(null)
    }

    @Test
    fun `a watch that never timed out has no record`() {
        assertEquals(
            0L,
            FgsTimeoutReporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
        assertEquals(
            0,
            FgsTimeoutReporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
    }

    @Test
    fun `recordTimeout persists when and which type`() {
        FgsTimeoutReporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE,
            startId = 4,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(
            1_700_000_000_000L,
            FgsTimeoutReporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            FgsTimeoutReporter.lastFgsType(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
        assertEquals(
            1,
            FgsTimeoutReporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
    }

    @Test
    fun `repeated timeouts accumulate a count`() {
        repeat(3) {
            FgsTimeoutReporter.recordTimeout(
                component = FgsTimeoutReporter.COMPONENT_WATCH_APK_RECEIVE,
                startId = 1,
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }

        assertEquals(
            3,
            FgsTimeoutReporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WATCH_APK_RECEIVE),
        )
    }

    @Test
    fun `the two receive services record independently`() {
        FgsTimeoutReporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_WATCH_APK_RECEIVE,
            startId = 1,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        assertEquals(
            0,
            FgsTimeoutReporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
        assertEquals(
            0L,
            FgsTimeoutReporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
    }

    @Test
    fun `an uninitialized store degrades to a log instead of throwing`() {
        FgsTimeoutReporter.setStoreForTest(null)

        // A watch service that somehow times out before onCreate opened the store still has to
        // reach stopSelf; losing the record beats crashing the process it was saving.
        FgsTimeoutReporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE,
            startId = 1,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        assertEquals(
            0,
            FgsTimeoutReporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WATCH_FACE_RECEIVE),
        )
    }

    /** Minimal SharedPreferences double: writes land in [stored], reads come back out of it. */
    private fun mapBackedPrefs(): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(any(), any()) } answers {
            stored[firstArg()] = secondArg<Long>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            stored[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.apply() } just Runs

        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        every { prefs.getLong(any(), any()) } answers {
            stored[firstArg()] as? Long ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            stored[firstArg()] as? Int ?: secondArg()
        }
        return prefs
    }
}
