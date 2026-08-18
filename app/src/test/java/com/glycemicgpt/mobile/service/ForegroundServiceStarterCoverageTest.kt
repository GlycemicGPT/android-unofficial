// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import com.glycemicgpt.mobile.contract.ContractFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard for GLY-246: `ForegroundServiceStarter` is the only place allowed to call
 * `Context.startForegroundService` or `Service.startForeground` -- and, since review round F1,
 * that is true repo-wide, not just in `:app`. `:wear-device` has its own twin
 * (`com.glycemicgpt.weardevice.data.ForegroundServiceStarter`) for the same reason
 * [DataSyncTimeoutCoverageTest] scans both modules: `wear-device/build.gradle.kts` ships under
 * the same `applicationId` as `:app`, and GLY-244 already treats the two APKs as one app for
 * exactly this class of guard. Source-text based, the same idiom [DataSyncTimeoutCoverageTest]
 * and [com.glycemicgpt.mobile.contract.SafetyConstantDriftGuardTest] use: there is no custom Lint
 * check in this repo, and a compiled-code check can't see a call site that was simply never
 * routed through a helper.
 */
class ForegroundServiceStarterCoverageTest {

    private data class Module(val name: String, val sourceRoot: String, val helperPath: String)

    private val modules = listOf(
        Module(
            name = ":app",
            sourceRoot = "app/src/main/java",
            helperPath = "app/src/main/java/com/glycemicgpt/mobile/service/ForegroundServiceStarter.kt",
        ),
        Module(
            name = ":wear-device",
            sourceRoot = "wear-device/src/main/java",
            helperPath = "wear-device/src/main/java/com/glycemicgpt/weardevice/data/ForegroundServiceStarter.kt",
        ),
    )

    @Test
    fun `no source file outside a helper calls startForegroundService or startForeground directly`() {
        val violations = modules.flatMap { module ->
            val sourceRoot = File(ContractFixtures.repoRoot(), module.sourceRoot)
            check(sourceRoot.isDirectory) { "Expected source root not found: $sourceRoot" }
            // Path-based, not name-based: a future file that merely happens to share the helper's
            // *name* elsewhere in the tree must still be scanned (GLY-246 review F10).
            val helperFile = File(ContractFixtures.repoRoot(), module.helperPath).canonicalFile

            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.canonicalFile != helperFile }
                .flatMap { file ->
                    CALL_PATTERN.findAll(file.readText()).map { match ->
                        "${module.name}/${file.relativeTo(sourceRoot)}: ${match.value.trim()}"
                    }
                }
        }

        assertTrue(
            "Raw startForegroundService/startForeground call(s) found outside a " +
                "ForegroundServiceStarter helper. Route through it instead so a platform " +
                "rejection is caught and reported: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `each helper contains exactly the guarded call sites it wraps`() {
        modules.forEach { module ->
            val helper = File(ContractFixtures.repoRoot(), module.helperPath)
            val matches = CALL_PATTERN.findAll(helper.readText()).count()
            val expected = EXPECTED_CALL_SITE_COUNT.getValue(module.name)

            // Exact count, not a floor (GLY-246 review F10): a helper that lost one of its
            // guarded call sites -- e.g. a promote() overload collapsed back to an unguarded
            // direct call -- must fail this, not slide under a ">=" bound.
            assertEquals(
                "${module.name}'s ForegroundServiceStarter no longer contains the expected " +
                    "number of guarded startForegroundService/startForeground call sites " +
                    "(found $matches, expected $expected). If the implementation changed shape, " +
                    "update this guard's expectation.",
                expected,
                matches,
            )
        }
    }

    private companion object {
        // Excludes declarations (`fun startForeground(...)`/`override fun startForegroundService`)
        // via the negative lookbehind, so RestrictedPluginContext's sandboxed override -- which
        // denies the call rather than making it -- does not trip this guard.
        val CALL_PATTERN = Regex("""(?<!fun )\bstartForegroundService\s*\(|(?<!fun )\bstartForeground\s*\(""")

        // :app's start()/promote() cover both the 2-arg and 3-arg Service.startForeground
        // overloads plus Context.startForegroundService; :wear-device's promote() covers only
        // the two Service.startForeground overloads (no companion start() call shape there).
        val EXPECTED_CALL_SITE_COUNT = mapOf(":app" to 3, ":wear-device" to 2)
    }
}
