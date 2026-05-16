package com.averycorp.prismtask.domain.automation

import com.averycorp.prismtask.data.local.dao.AutomationLogDao
import com.averycorp.prismtask.data.local.entity.AutomationRuleEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [AutomationRateLimiter]. The existing five execution
 * caps (per-rule daily, global hourly, AI daily) plus the new mental-health
 * class cap (per-task notify) all flow through `canFire` —
 * this test asserts they (a) compose cleanly,
 * (b) bypass when their precondition is absent, and (c) block with a
 * specific reason string when they fire.
 */
class AutomationRateLimiterTest {

    private val logDao: AutomationLogDao = mockk(relaxed = true)
    private val limiter = AutomationRateLimiter(logDao)

    private val now = 1_700_000_000_000L
    private val taskId = 42L

    private fun rule(
        id: Long = 1,
        dailyFireCount: Int = 0,
        triggerJson: String = """{"type":"ENTITY_EVENT","eventKind":"TaskUpdated"}""",
        actionJson: String = """[{"type":"notify","title":"x","body":"y"}]"""
    ) = AutomationRuleEntity(
        id = id,
        name = "test",
        triggerJson = triggerJson,
        actionJson = actionJson,
        dailyFireCount = dailyFireCount,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun taskUpdatedEvent(forTaskId: Long = taskId) =
        AutomationEvent.TaskUpdated(taskId = forTaskId, occurredAt = now)

    @Test fun allows_when_all_caps_below_threshold() = runTest {
        coEvery { logDao.countSince(any()) } returns 0
        coEvery { logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any()) } returns 0

        val decision = limiter.canFire(rule(), taskUpdatedEvent(), now)

        assertEquals(AutomationRateLimiter.Decision.Allowed, decision)
    }

    @Test fun blocks_when_per_rule_daily_cap_reached() = runTest {
        val decision = limiter.canFire(
            rule(dailyFireCount = AutomationRateLimiter.MAX_FIRES_PER_RULE_PER_DAY),
            taskUpdatedEvent(),
            now
        )

        assertTrue(decision is AutomationRateLimiter.Decision.Blocked)
        assertTrue(
            (decision as AutomationRateLimiter.Decision.Blocked)
                .reason.contains("per-rule daily cap")
        )
    }

    @Test fun per_rule_cap_respects_maxFiresPerDay_override_in_trigger_json() = runTest {
        coEvery { logDao.countSince(any()) } returns 0
        coEvery { logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any()) } returns 0

        val overrideTrigger =
            """{"type":"ENTITY_EVENT","eventKind":"TaskUpdated","maxFiresPerDay":5}"""
        val ruleAtOverride = rule(dailyFireCount = 5, triggerJson = overrideTrigger)

        val decision = limiter.canFire(ruleAtOverride, taskUpdatedEvent(), now)

        assertTrue(decision is AutomationRateLimiter.Decision.Blocked)
        assertTrue(
            (decision as AutomationRateLimiter.Decision.Blocked)
                .reason.contains("(5)")
        )
    }

    @Test fun blocks_when_global_hourly_cap_reached() = runTest {
        coEvery { logDao.countSince(any()) } returns AutomationRateLimiter.MAX_GLOBAL_FIRES_PER_HOUR

        val decision = limiter.canFire(rule(), taskUpdatedEvent(), now)

        assertTrue(decision is AutomationRateLimiter.Decision.Blocked)
        assertTrue(
            (decision as AutomationRateLimiter.Decision.Blocked)
                .reason.contains("global hourly cap")
        )
    }

    @Test fun blocks_when_ai_action_daily_cap_reached() = runTest {
        coEvery { logDao.countSince(any()) } returns 0
        coEvery { logDao.countAiSince(any()) } returns AutomationRateLimiter.MAX_AI_ACTIONS_PER_DAY

        val aiRule = rule(
            actionJson = """[{"type":"ai.summarize","scope":"recent_completions"}]"""
        )

        val decision = limiter.canFire(aiRule, taskUpdatedEvent(), now)

        assertTrue(decision is AutomationRateLimiter.Decision.Blocked)
        assertTrue(
            (decision as AutomationRateLimiter.Decision.Blocked)
                .reason.contains("AI-action daily cap")
        )
    }

    @Test fun ai_cap_not_checked_when_no_ai_action_present() = runTest {
        coEvery { logDao.countSince(any()) } returns 0
        coEvery { logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any()) } returns 0

        limiter.canFire(rule(), taskUpdatedEvent(), now)

        coVerify(exactly = 0) { logDao.countAiSince(any()) }
    }

    // --- Per-task notify soft cap (7th mechanism) ----------------------

    @Test fun blocks_when_per_task_notify_cap_reached() = runTest {
        coEvery { logDao.countSince(any()) } returns 0
        coEvery {
            logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any())
        } returns AutomationRateLimiter.MAX_NOTIFIES_PER_TASK_PER_DAY

        val decision = limiter.canFire(rule(), taskUpdatedEvent(), now)

        assertTrue(decision is AutomationRateLimiter.Decision.Blocked)
        val reason = (decision as AutomationRateLimiter.Decision.Blocked).reason
        assertTrue(reason.contains("per-task notify cap"))
        assertTrue(reason.contains("task $taskId"))
    }

    @Test fun per_task_cap_bypassed_when_rule_has_no_notify_action() = runTest {
        coEvery { logDao.countSince(any()) } returns 0

        val nonNotifyRule = rule(actionJson = """[{"type":"log","message":"hi"}]""")
        val decision = limiter.canFire(nonNotifyRule, taskUpdatedEvent(), now)

        assertEquals(AutomationRateLimiter.Decision.Allowed, decision)
        coVerify(exactly = 0) {
            logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any())
        }
    }

    @Test fun per_task_cap_bypassed_when_event_has_no_task_id() = runTest {
        coEvery { logDao.countSince(any()) } returns 0

        val timeTick = AutomationEvent.TimeTick(hour = 8, minute = 0, occurredAt = now)
        val decision = limiter.canFire(rule(), timeTick, now)

        assertEquals(AutomationRateLimiter.Decision.Allowed, decision)
        coVerify(exactly = 0) {
            logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any())
        }
    }

    @Test fun per_task_cap_passes_taskId_markers_with_both_boundary_shapes() = runTest {
        coEvery { logDao.countSince(any()) } returns 0
        coEvery {
            logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any())
        } returns 0

        limiter.canFire(rule(), taskUpdatedEvent(forTaskId = 7L), now)

        coVerify {
            logDao.countNotifiesForRuleAndTaskSince(
                ruleId = 1L,
                taskMarkerComma = "%\"taskId\":7,%",
                taskMarkerBrace = "%\"taskId\":7}%",
                since = now - AutomationRateLimiter.DAY_MS
            )
        }
    }

    @Test fun per_task_cap_allows_below_threshold() = runTest {
        coEvery { logDao.countSince(any()) } returns 0
        coEvery {
            logDao.countNotifiesForRuleAndTaskSince(any(), any(), any(), any())
        } returns AutomationRateLimiter.MAX_NOTIFIES_PER_TASK_PER_DAY - 1

        val decision = limiter.canFire(rule(), taskUpdatedEvent(), now)

        assertEquals(AutomationRateLimiter.Decision.Allowed, decision)
    }
}
