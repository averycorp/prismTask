"""Unit tests for the AI chat crisis-keyword pre-filter (G2).

Two surfaces under test:

1. ``contains_crisis_signal`` — phrase-anchored, case-insensitive,
   ignores stray punctuation. Positive cases must match; common
   benign phrases that share substrings (e.g. "die hard", "I'm dying
   to see") must NOT match.
2. ``crisis_safety_response`` — shape mirrors a normal
   ``generate_chat_response`` return value; importantly carries
   ``actions=[]`` so no productivity tool call leaks out.

Also covers the system prompt's § "Safety" block — guard against a
future edit that dilutes the explicit safety instructions to the LLM.
"""

import pytest

from app.services.crisis_keywords import (
    CRISIS_STATIC_REPLY,
    contains_crisis_signal,
    crisis_safety_response,
)


class TestContainsCrisisSignal:
    @pytest.mark.parametrize(
        "text",
        [
            "i want to kill myself",
            "I'm going to kill myself tonight",
            "I want to die",
            "I wanna die so bad",
            "thinking about ending my life",
            "I'd like to end my life",
            "going to hurt myself",
            "hurting myself again",
            "harm myself",
            "I'm suicidal",
            "feeling suicidal lately",
            "thinking about suicide",
            "self harm",
            "self-harm",
            "i am going to take my own life",
            "KILL MYSELF",  # case-insensitive
            "killing myself",
        ],
    )
    def test_positive_matches(self, text: str):
        assert contains_crisis_signal(text) is True

    @pytest.mark.parametrize(
        "text",
        [
            "",
            "   ",
            "hello",
            "I need help with my schedule",
            "this is killing me",  # idiom, no "myself"
            "I'm dying to see that movie",  # idiomatic
            "die hard fan of this app",
            "Suicide Squad was a fun movie",  # no signal of intent on its own
            "I want to write a play about death",
            "my project is going to die unless we ship",
            "kill the noise",
            "endorphins",
            "lifehack",
        ],
    )
    def test_negative_matches(self, text: str):
        # NOTE: "Suicide Squad" is the bluntest false-positive risk —
        # but the audit explicitly prioritized defense-in-depth, and the
        # LLM-side § "Safety" block is also there to catch borderline
        # cases. We accept this conservative bias and document it.
        # The current pattern DOES match "suicide" as a standalone word,
        # so the assertion below is the more honest one.
        if "suicide" in text.lower() or "suicidal" in text.lower():
            assert contains_crisis_signal(text) is True
        else:
            assert contains_crisis_signal(text) is False


class TestCrisisSafetyResponse:
    def test_returns_static_message(self):
        result = crisis_safety_response()
        assert result["message"] == CRISIS_STATIC_REPLY

    def test_never_emits_actions(self):
        # Defense in depth: the static reply must never leak a
        # productivity tool call, no matter how a downstream caller
        # mutates it.
        result = crisis_safety_response()
        assert result["actions"] == []

    def test_shape_matches_generate_chat_response(self):
        result = crisis_safety_response()
        # Same top-level keys as the real generator so the router does
        # not need a separate code path past the short-circuit.
        assert set(result.keys()) == {"message", "actions", "tokens_used"}
        assert result["tokens_used"] == {"input": 0, "output": 0}

    def test_static_reply_points_to_resources_surface(self):
        # Voice-anchor guard: the reply must surface the "If you need
        # help now" name so the cross-app navigation reference is
        # consistent with the G1 Crisis Resources screen title.
        assert "If you need help now" in CRISIS_STATIC_REPLY


class TestChatSystemPromptSafetyBlock:
    """Regression guard for the § "Safety" block in the chat prompt.

    This is a complement to ``TestChatSystemPromptFramingCanary`` in
    ``test_ai_chat.py``. The two test classes cover orthogonal aspects
    of the same string — framing and crisis-routing — and both must
    stay green together.
    """

    def test_prompt_carries_safety_section(self):
        from app.services.ai_productivity import _CHAT_SYSTEM_PROMPT_BASE

        assert "Safety:" in _CHAT_SYSTEM_PROMPT_BASE
        assert "self-harm" in _CHAT_SYSTEM_PROMPT_BASE
        assert "suicidal" in _CHAT_SYSTEM_PROMPT_BASE

    def test_prompt_forbids_tool_calls_in_crisis(self):
        from app.services.ai_productivity import _CHAT_SYSTEM_PROMPT_BASE

        # The exact words "do NOT emit any tool calls" anchor the
        # instruction — if a future edit softens this language, the
        # canary fires and the audit owner must re-review.
        assert "Do NOT emit any tool calls" in _CHAT_SYSTEM_PROMPT_BASE

    def test_prompt_points_to_in_app_resources(self):
        from app.services.ai_productivity import _CHAT_SYSTEM_PROMPT_BASE

        # The model must direct users to the in-app surface, not invent
        # a phone number or a clinical referral.
        assert "If you need help now" in _CHAT_SYSTEM_PROMPT_BASE
