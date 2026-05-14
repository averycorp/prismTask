package com.averycorp.prismtask.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `backend/tests/test_crisis_keywords.py`. Both lists must stay
 * in lockstep; if either is changed without the other, R5's Free-tier
 * fallback will diverge from the backend defense-in-depth pre-filter.
 */
class CrisisKeywordsTest {

    @Test
    fun positiveMatch_kill_myself() {
        assertTrue(CrisisKeywords.containsCrisisSignal("i want to kill myself"))
    }

    @Test
    fun positiveMatch_killing_myself() {
        assertTrue(CrisisKeywords.containsCrisisSignal("I'm killing myself tonight"))
    }

    @Test
    fun positiveMatch_want_to_die() {
        assertTrue(CrisisKeywords.containsCrisisSignal("I want to die"))
    }

    @Test
    fun positiveMatch_wanna_die() {
        assertTrue(CrisisKeywords.containsCrisisSignal("I just wanna die"))
    }

    @Test
    fun positiveMatch_end_my_life() {
        assertTrue(CrisisKeywords.containsCrisisSignal("thinking about ending my life"))
    }

    @Test
    fun positiveMatch_hurt_myself() {
        assertTrue(CrisisKeywords.containsCrisisSignal("I might hurt myself"))
    }

    @Test
    fun positiveMatch_harm_myself() {
        assertTrue(CrisisKeywords.containsCrisisSignal("harm myself again"))
    }

    @Test
    fun positiveMatch_take_my_own_life() {
        assertTrue(CrisisKeywords.containsCrisisSignal("about to take my own life"))
    }

    @Test
    fun positiveMatch_suicide() {
        assertTrue(CrisisKeywords.containsCrisisSignal("thinking about suicide"))
    }

    @Test
    fun positiveMatch_suicidal() {
        assertTrue(CrisisKeywords.containsCrisisSignal("I feel suicidal"))
    }

    @Test
    fun positiveMatch_self_harm_spaced() {
        assertTrue(CrisisKeywords.containsCrisisSignal("self harm urges"))
    }

    @Test
    fun positiveMatch_self_harm_hyphenated() {
        assertTrue(CrisisKeywords.containsCrisisSignal("history of self-harm"))
    }

    @Test
    fun positiveMatch_caseInsensitive() {
        assertTrue(CrisisKeywords.containsCrisisSignal("KILL MYSELF"))
        assertTrue(CrisisKeywords.containsCrisisSignal("Suicide"))
    }

    @Test
    fun negativeMatch_blank() {
        assertFalse(CrisisKeywords.containsCrisisSignal(""))
        assertFalse(CrisisKeywords.containsCrisisSignal("   "))
    }

    @Test
    fun negativeMatch_unrelated() {
        assertFalse(CrisisKeywords.containsCrisisSignal("hello"))
        assertFalse(CrisisKeywords.containsCrisisSignal("I need help with my schedule"))
    }

    @Test
    fun negativeMatch_diehard_idiom() {
        assertFalse(CrisisKeywords.containsCrisisSignal("die hard fan of this app"))
    }

    @Test
    fun negativeMatch_dying_to_see_idiom() {
        // "dying to" without "myself" / etc. is idiomatic enthusiasm,
        // not a crisis signal. The phrase-anchored list excludes it.
        assertFalse(CrisisKeywords.containsCrisisSignal("I'm dying to see that movie"))
    }

    @Test
    fun negativeMatch_killing_me_idiom() {
        // "killing me" (without "myself") is idiomatic frustration.
        assertFalse(CrisisKeywords.containsCrisisSignal("this is killing me"))
    }

    @Test
    fun negativeMatch_project_going_to_die() {
        // "going to die" was deliberately removed from the phrase list
        // because of this false-positive class — project / sales /
        // company / plant "going to die" is everyday English.
        assertFalse(
            CrisisKeywords.containsCrisisSignal(
                "my project is going to die unless we ship"
            )
        )
    }

    @Test
    fun staticReplyMentionsResourcesSurface() {
        // Voice-anchor guard — the static reply must surface the
        // "If you need help now" name so the cross-app navigation
        // reference is consistent with the G1 Crisis Resources screen
        // title and the backend's CRISIS_STATIC_REPLY.
        assertTrue(CrisisKeywords.STATIC_REPLY.contains("If you need help now"))
    }

    @Test
    fun staticReplyMatchesBackendVoice() {
        // The exact string is duplicated server-side. A future edit on
        // one side without the other will dilute the cross-device chat
        // experience; this canary makes the drift obvious.
        assertEquals(
            "I'm really glad you told me. I'm not the right kind of help for this, " +
                "but the resources in 'If you need help now' (Settings or the link at " +
                "the bottom of Mood & Energy) are open 24/7. Please reach out — you " +
                "matter more than any task.",
            CrisisKeywords.STATIC_REPLY
        )
    }
}
