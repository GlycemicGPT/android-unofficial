// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import com.glycemicgpt.mobile.contract.ContractFixtures
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard for GLY-246: [ForegroundServiceStarter] is the only place in `:app` allowed to call
 * `Context.startForegroundService` or `Service.startForeground`. Every other call site -- present
 * or future -- must route through it so a platform rejection is handled once, not N times (and
 * not missed on the Nth+1 time). Source-text based, the same idiom
 * [DataSyncTimeoutCoverageTest] and [com.glycemicgpt.mobile.contract.SafetyConstantDriftGuardTest]
 * use: there is no custom Lint check in this repo, and a compiled-code check can't see a call
 * site that was simply never routed through the helper.
 */
class ForegroundServiceStarterCoverageTest {

    @Test
    fun `no source file outside the helper calls startForegroundService or startForeground directly`() {
        val sourceRoot = File(ContractFixtures.repoRoot(), "app/src/main/java")
        check(sourceRoot.isDirectory) { "Expected source root not found: $sourceRoot" }

        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != HELPER_FILE_NAME }
            .flatMap { file ->
                CALL_PATTERN.findAll(file.readText()).map { match ->
                    "${file.relativeTo(sourceRoot)}: ${match.value.trim()}"
                }
            }
            .toList()

        assertTrue(
            "Raw startForegroundService/startForeground call(s) found outside " +
                "$HELPER_FILE_NAME. Route through ForegroundServiceStarter instead so a platform " +
                "rejection is caught and reported: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `the helper itself still contains the only two guarded call sites`() {
        val helper = File(
            ContractFixtures.repoRoot(),
            "app/src/main/java/com/glycemicgpt/mobile/service/$HELPER_FILE_NAME",
        )
        val matches = CALL_PATTERN.findAll(helper.readText()).count()

        assertTrue(
            "Expected ForegroundServiceStarter.kt to contain the startForegroundService/" +
                "startForeground call sites it wraps; found $matches. If the implementation " +
                "changed shape, update this guard's expectations.",
            matches >= 2,
        )
    }

    private companion object {
        const val HELPER_FILE_NAME = "ForegroundServiceStarter.kt"

        // Excludes declarations (`fun startForeground(...)`/`override fun startForegroundService`)
        // via the negative lookbehind, so RestrictedPluginContext's sandboxed override -- which
        // denies the call rather than making it -- does not trip this guard.
        val CALL_PATTERN = Regex("""(?<!fun )\bstartForegroundService\s*\(|(?<!fun )\bstartForeground\s*\(""")
    }
}
