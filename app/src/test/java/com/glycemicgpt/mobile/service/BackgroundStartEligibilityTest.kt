// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The battery-optimization route into [BackgroundStartEligibility] -- the one an eligibility
 * question can actually be pinned on, since process importance is decided by the platform and not
 * by anything a test can set.
 *
 * What matters is the direction of the default: not exempt means not eligible. An eligibility
 * probe that guessed "yes" would put back exactly the illegal start GLY-247 removes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackgroundStartEligibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val eligibility = BackgroundStartEligibility(context)

    private fun setExempt(exempt: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, exempt)
    }

    @Test
    fun `a battery-optimization exemption qualifies`() {
        setExempt(true)

        assertTrue(eligibility.isEligible())
    }

    @Test
    fun `no exemption and no foreground importance does not qualify`() {
        // Also pins the importance probe's pessimistic seed. `RunningAppProcessInfo`'s constructor
        // fills in IMPORTANCE_FOREGROUND, and nothing here shadows `getMyMemoryState`, so a probe
        // that trusted the struct default would call this background process eligible and hand
        // back the illegal start.
        setExempt(false)

        assertFalse(eligibility.isEligible())
    }
}
