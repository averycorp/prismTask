"""Crisis-keyword pre-filter for the AI chat surface.

Defense in depth around the AI chat: even with a § "Safety" block in
``_CHAT_SYSTEM_PROMPT_BASE``, we want a server-side short-circuit so that
high-confidence crisis terms never round-trip to Claude. The static reply
this module returns:

- never carries productivity tool calls,
- points to the in-app crisis-resources surface (G1),
- mirrors the voice of the rest of the forgiveness-first chat copy.

The keyword list is intentionally tight — phrase-anchored, not single
common words like "die" — to keep the false-positive rate low. It is NOT
a diagnostic. The matching ignores word case and tolerates apostrophes
in contractions ("I'm", "don't").

Mirrored on the Android client (``CrisisKeywords.kt``) so the Free-tier
client-side fallback in ``ChatViewModel`` short-circuits to the same
copy without calling the backend. Keep the two lists in lockstep.
"""

from __future__ import annotations

import re

# Phrase-anchored matches. Each entry below renders into a regex that
# matches word-boundary-delimited keyword sequences case-insensitively,
# tolerating apostrophes in contractions ("I'm", "don't").
_CRISIS_PHRASES: tuple[str, ...] = (
    "kill myself",
    "killing myself",
    "want to die",
    "wanna die",
    "hurt myself",
    "hurting myself",
    "harm myself",
    "harming myself",
    "end my life",
    "ending my life",
    "take my own life",
    "taking my own life",
    "suicide",
    "suicidal",
    "self harm",
    "self-harm",
)


def _phrase_to_pattern(phrase: str) -> str:
    # Anchor on word boundaries; allow flexible spacing between tokens.
    parts = [re.escape(p) for p in phrase.split()]
    return r"\b" + r"\s+".join(parts) + r"\b"


_CRISIS_PATTERN = re.compile(
    "|".join(_phrase_to_pattern(p) for p in _CRISIS_PHRASES),
    flags=re.IGNORECASE,
)


# Voice anchor: same forgiveness-first register as
# ``ProductiveStreakPreferences.BROKEN_STREAK_NOTIFICATION_BODY``
# and the chat system prompt's posture paragraph. Updated alongside
# ``CrisisKeywords.kt`` whenever the copy changes.
CRISIS_STATIC_REPLY: str = (
    "I'm really glad you told me. I'm not the right kind of help for this, "
    "but the resources in 'If you need help now' (Settings or the link at "
    "the bottom of Mood & Energy) are open 24/7. Please reach out — you "
    "matter more than any task."
)


def contains_crisis_signal(message: str) -> bool:
    """Return True when ``message`` carries a high-confidence crisis term.

    Empty / whitespace-only input always returns False so the regular
    chat path remains in control of error responses.
    """
    if not message or not message.strip():
        return False
    return _CRISIS_PATTERN.search(message) is not None


def crisis_safety_response() -> dict:
    """Static response shape mirroring ``generate_chat_response`` output.

    Importantly, ``actions`` is an empty list — the safety reply must
    never carry productivity tool calls.
    """
    return {
        "message": CRISIS_STATIC_REPLY,
        "actions": [],
        "tokens_used": {"input": 0, "output": 0},
    }
