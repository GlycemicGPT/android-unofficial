// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import com.glycemicgpt.mobile.contract.ContractFixtures
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard: an `Application` subclass registers and observes; it never starts monitoring.
 *
 * `Application.onCreate` runs on every process creation, and most of them are not the user
 * opening the app -- a periodic worker, an inbound wear message, a notification action, a
 * ContentProvider touch. Those run in the background, where Android 12+ rejects a
 * foreground-service start; the platform turns the rejection thrown out of application init into
 * an "Unable to create application" crash, which field DropBox evidence on an Android 16 device
 * confirms was this app's top background crasher. Routing the call through
 * [ForegroundServiceStarter] stopped the crash but kept the illegal attempt; the fix is that
 * application init makes no such attempt at all, and that is what this pins.
 *
 * Reconciling from init is forbidden for the same reason it is not a fix: whether that process
 * creation is allowed to start anything is exactly what `Application.onCreate` cannot know. The
 * decision belongs to a trigger that carries its own context -- [MonitoringForegroundObserver] for
 * a visible Activity, [BootCompletedReceiver] for boot -- which is what [MonitoringReconciler]
 * takes.
 *
 * Source-text based, the same idiom [ForegroundServiceStarterCoverageTest] and
 * [com.glycemicgpt.mobile.contract.SafetyConstantDriftGuardTest] use: a compiled-code check
 * cannot see a call site that was simply written back in.
 */
class ApplicationInitNoServiceStartTest {

    @Test
    fun `no Application subclass starts a service or reconciles monitoring`() {
        val applications = SOURCE_ROOTS
            .map { File(ContractFixtures.repoRoot(), it) }
            .onEach { check(it.isDirectory) { "Expected source root not found: $it" } }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map { it to it.readText() }
                    .filter { (_, text) -> APPLICATION_SUBCLASS.containsMatchIn(text) }
                    .toList()
            }

        assertTrue(
            "No Application subclass found to guard -- if the app class moved, update SOURCE_ROOTS.",
            applications.isNotEmpty(),
        )

        val violations = applications.flatMap { (file, text) ->
            FORBIDDEN.findAll(text).map { "${file.name}: ${it.value.trim()}" }
        }

        assertTrue(
            "An Application subclass starts a service or reconciles monitoring. Application init " +
                "must only register and observe -- move the decision behind a trigger that knows " +
                "it is in a legal context (MonitoringReconciler): $violations",
            violations.isEmpty(),
        )
    }

    private companion object {
        val SOURCE_ROOTS = listOf("app/src/main/java", "wear-device/src/main/java")

        val APPLICATION_SUBCLASS = Regex(""":\s*Application\s*\(""")

        /**
         * `startService` is in here too: it is not the crashing call, but a background
         * `startService` for a component that promotes itself to the foreground reaches the same
         * rejection one hop later.
         */
        val FORBIDDEN = Regex(
            """\bstartForegroundService\s*\(""" +
                """|\bstartService\s*\(""" +
                """|\b\w*Service\s*\.\s*start\s*\(""" +
                """|\.reconcile\s*\(""",
        )
    }
}
