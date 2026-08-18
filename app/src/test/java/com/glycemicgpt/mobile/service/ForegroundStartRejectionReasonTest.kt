// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ForegroundStartRejectionReason.classify] is pure message-text matching, so it needs no
 * Robolectric context. GLY-246 review F3 added the enum; NEW-5 (round 2) closes the gap where
 * only [ForegroundStartRejectionReason.BUDGET_EXHAUSTED] and
 * [ForegroundStartRejectionReason.PERMISSION_DENIED] were ever exercised, and tightens the
 * background-restriction match to the specific `mAllowStartForeground` field name the real
 * platform message carries (DropBox evidence in GLY-247) instead of the generic "not allowed due
 * to"/"background" substrings that could misclassify a message this classifier does not actually
 * recognize.
 */
class ForegroundStartRejectionReasonTest {

    @Test
    fun `a security exception is always permission denied regardless of message`() {
        assertEquals(
            ForegroundStartRejectionReason.PERMISSION_DENIED,
            ForegroundStartRejectionReason.classify(SecurityException("anything")),
        )
    }

    @Test
    fun `a budget message classifies as budget exhausted`() {
        assertEquals(
            ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
            ForegroundStartRejectionReason.classify(
                IllegalStateException("Time limit already exhausted for foreground service type dataSync"),
            ),
        )
    }

    @Test
    fun `a BOOT_COMPLETED message classifies as boot type restriction`() {
        assertEquals(
            ForegroundStartRejectionReason.BOOT_TYPE_RESTRICTION,
            ForegroundStartRejectionReason.classify(
                IllegalStateException(
                    "startForegroundService() not allowed due to BOOT_COMPLETED broadcast restriction",
                ),
            ),
        )
    }

    @Test
    fun `the real background-start restriction message classifies as background start restriction`() {
        // Real platform text (DropBox evidence, GLY-247): the field name the check fails on is
        // `mAllowStartForeground`, not a generic phrase this classifier should be matching on.
        assertEquals(
            ForegroundStartRejectionReason.BACKGROUND_START_RESTRICTION,
            ForegroundStartRejectionReason.classify(
                IllegalStateException("startForegroundService() not allowed due to mAllowStartForeground false"),
            ),
        )
    }

    @Test
    fun `an unrecognized message falls back to OTHER instead of a confident wrong label`() {
        // Generic phrasing that an earlier, broader classifier would have misfiled as
        // BACKGROUND_START_RESTRICTION purely because it contains "not allowed" / "background".
        assertEquals(
            ForegroundStartRejectionReason.OTHER,
            ForegroundStartRejectionReason.classify(
                IllegalStateException("Service not allowed to run in the background right now"),
            ),
        )
    }

    @Test
    fun `a null message falls back to OTHER`() {
        assertEquals(
            ForegroundStartRejectionReason.OTHER,
            ForegroundStartRejectionReason.classify(IllegalStateException()),
        )
    }
}
