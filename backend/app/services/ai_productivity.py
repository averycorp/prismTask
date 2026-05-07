import json
import logging
import os
from datetime import date, timedelta

from app.config import settings

try:
    import anthropic
except ImportError:
    anthropic = None  # type: ignore

logger = logging.getLogger(__name__)

MODEL_HAIKU = "claude-haiku-4-5-20251001"
MODEL_SONNET = "claude-sonnet-4-20250514"

# AI features that use Sonnet for higher-quality output. Everything else runs
# on Haiku. These are the premium AI features baked into the single Pro tier.
SONNET_FEATURES = {"weekly_planner", "monthly_review"}


def get_model(feature: str | None = None) -> str:
    """Return the appropriate Claude model ID for the given AI feature.

    Weekly planner and monthly review use Sonnet; everything else uses
    Haiku. ``feature`` is a short lowercase identifier (e.g. ``"eisenhower"``,
    ``"weekly_planner"``). Passing ``None`` defaults to Haiku.
    """
    if feature in SONNET_FEATURES:
        return MODEL_SONNET
    return MODEL_HAIKU

# Sonnet pricing (~$3/M input, ~$15/M output) vs Haiku (~$0.25/M, ~$1.25/M).
# Weekly planner / monthly review are Sonnet-backed; all other AI features
# use Haiku to keep the margin healthy at the single $7.99/mo (or $5/mo
# annual at $59.99/yr) Pro tier.


def _get_client():
    api_key = os.environ.get("ANTHROPIC_API_KEY") or settings.ANTHROPIC_API_KEY
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY environment variable is not set")
    if anthropic is None:
        raise RuntimeError("anthropic package is not installed")
    return anthropic.Anthropic(api_key=api_key)


def _parse_ai_json(content: str) -> dict | list:
    """Strip markdown fences and parse JSON from AI response."""
    content = content.strip()
    if content.startswith("```"):
        content = content.split("\n", 1)[1] if "\n" in content else content[3:]
    if content.endswith("```"):
        content = content[:-3]
    content = content.strip()
    return json.loads(content)


def categorize_eisenhower(tasks: list[dict], today: date, tier: str = "FREE") -> list[dict]:
    """Call Claude to categorize tasks into Eisenhower quadrants."""
    client = _get_client()
    model = get_model("eisenhower")
    tasks_json = json.dumps(tasks, default=str, indent=2)
    prompt = f"""You are a productivity assistant. Categorize each task into an Eisenhower Matrix quadrant.

Quadrants:
- Q1 (Urgent + Important): Deadlines within 48 hours, high-priority blockers, critical issues
- Q2 (Not Urgent + Important): Long-term goals, planning, skill building, health, relationships
- Q3 (Urgent + Not Important): Most emails, some meetings, minor deadlines, others' priorities
- Q4 (Not Urgent + Not Important): Time-wasters, excessive social media, busywork

Consider: due date proximity, priority level, project context, task description.
A task with no due date but high priority is likely Q2.
A task due today with low priority is likely Q3.

Tasks:
{tasks_json}

Today's date: {today.isoformat()}

Respond ONLY with valid JSON:
[{{"task_id": 1, "quadrant": "Q1", "reason": "Due tomorrow, high priority"}}]"""

    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(model=model, max_tokens=2048, messages=[{"role": "user", "content": prompt}])
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, list):
                raise ValueError("Expected a JSON array")
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse Eisenhower response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Eisenhower AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def classify_eisenhower_text(
    title: str,
    description: str | None,
    due_date: str | None,
    priority: int,
    today: date,
    tier: str = "FREE",
) -> dict:
    """Classify a single task into an Eisenhower quadrant from raw text.

    Returns ``{"quadrant": "Q1|Q2|Q3|Q4", "reason": "..."}``. Raises
    ``ValueError`` on malformed AI response, ``RuntimeError`` when the
    Anthropic client is unavailable — same pattern as ``categorize_eisenhower``.
    """
    client = _get_client()
    model = get_model("eisenhower")
    task_summary = {
        "title": title,
        "description": description or "",
        "due_date": due_date,
        "priority": priority,  # 0=None, 1=Low, 2=Medium, 3=High, 4=Urgent
    }
    prompt = f"""You are a productivity assistant. Categorize ONE task into an Eisenhower Matrix quadrant.

Quadrants:
- Q1 (Urgent + Important): Deadlines within 48 hours, high-priority blockers, critical issues
- Q2 (Not Urgent + Important): Long-term goals, planning, skill building, health, relationships
- Q3 (Urgent + Not Important): Most emails, some meetings, minor deadlines, others' priorities
- Q4 (Not Urgent + Not Important): Time-wasters, excessive social media, busywork

Consider: due date proximity, priority level (0-4), task description.
A task with no due date but high priority (3-4) is likely Q2.
A task due today with low priority (0-1) is likely Q3.

Task:
{json.dumps(task_summary, default=str, indent=2)}

Today's date: {today.isoformat()}

Respond ONLY with valid JSON (no markdown, no prose):
{{"quadrant": "Q1", "reason": "brief reason"}}"""

    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(
                model=model,
                max_tokens=256,
                messages=[{"role": "user", "content": prompt}],
            )
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            quadrant = result.get("quadrant")
            if quadrant not in {"Q1", "Q2", "Q3", "Q4"}:
                raise ValueError(f"Invalid quadrant: {quadrant!r}")
            return {
                "quadrant": quadrant,
                "reason": str(result.get("reason", ""))[:500],
            }
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse text-classify response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Eisenhower text-classify AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def classify_cognitive_load_text(
    title: str,
    description: str | None,
    tier: str = "FREE",
) -> dict:
    """Classify a single task into a cognitive-load tier from raw text.

    Returns ``{"load": "EASY|MEDIUM|HARD", "reason": "..."}``. Raises
    ``ValueError`` on malformed AI response, ``RuntimeError`` when the
    Anthropic client is unavailable — mirrors ``classify_eisenhower_text``.

    The classifier intentionally never inflates HARD when ambiguous —
    over-classifying triggers procrastination preemptively. Tie-break
    bias mirrors the on-device tie-break (EASY > MEDIUM > HARD), which
    matches ``docs/COGNITIVE_LOAD.md`` § Inference rules.
    """
    client = _get_client()
    model = get_model("eisenhower")  # same Haiku tier as Eisenhower text-classify
    task_summary = {
        "title": title,
        "description": description or "",
    }
    prompt = f"""You are a productivity assistant. Categorize ONE task into a cognitive-load tier — *how much friction stands between the user and starting?* — independent of duration, importance, or reward type.

Tiers:
- EASY: Low start-friction. The user can begin without a deliberate decision (quick reply, archive, water plant).
- MEDIUM: Moderate start-friction. Needs a beat of attention before starting (review PR, compose notes).
- HARD: High start-friction. Beginning IS the work (draft difficult email, debug, start the novel).

Rules:
- Cognitive load is NOT duration: a 30-second task can be HARD; a 90-min task can be EASY.
- Cognitive load is NOT importance: that's the Eisenhower axis.
- When ambiguous, prefer EASY > MEDIUM > HARD. Never over-classify as HARD — that triggers procrastination preemptively.

Task:
{json.dumps(task_summary, default=str, indent=2)}

Respond ONLY with valid JSON (no markdown, no prose):
{{"load": "EASY", "reason": "brief reason"}}"""

    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(
                model=model,
                max_tokens=256,
                messages=[{"role": "user", "content": prompt}],
            )
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            load = result.get("load")
            if load not in {"EASY", "MEDIUM", "HARD"}:
                raise ValueError(f"Invalid load: {load!r}")
            return {
                "load": load,
                "reason": str(result.get("reason", ""))[:500],
            }
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse cognitive-load text-classify response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Cognitive-load text-classify AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def plan_pomodoro(tasks: list[dict], available_minutes: int, session_length: int, break_length: int, long_break_length: int, focus_preference: str, today: date, tier: str = "FREE") -> dict:
    """Call Claude to generate a Pomodoro focus session plan."""
    client = _get_client()
    tasks_json = json.dumps(tasks, default=str, indent=2)
    prompt = f"""You are a productivity coach planning focus sessions (Pomodoro technique).

Available time: {available_minutes} minutes
Session length: {session_length} min work + {break_length} min break
Long break: {long_break_length} min after every 4 sessions
Focus preference: {focus_preference}

Focus preference meanings:
- "deep_work": prioritize complex, high-concentration tasks; batch similar work
- "quick_wins": start with short, easy tasks to build momentum
- "balanced": mix of quick wins and deep work
- "deadline_driven": prioritize by due date urgency

Today's date: {today.isoformat()}

User's tasks:
{tasks_json}

Create an ordered plan of which tasks to work on in each session.

Respond ONLY with valid JSON:
{{"sessions": [{{"session_number": 1, "tasks": [{{"task_id": 1, "title": "Write report draft", "allocated_minutes": 25}}], "rationale": "Starting with the most urgent deadline"}}], "total_sessions": 4, "total_work_minutes": 100, "total_break_minutes": 20, "skipped_tasks": [{{"task_id": 7, "reason": "No estimated duration and low priority"}}]}}"""

    model = get_model("pomodoro")
    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(model=model, max_tokens=4096, messages=[{"role": "user", "content": prompt}])
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse Pomodoro response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Pomodoro AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def generate_daily_briefing(today: date, overdue_tasks: list[dict], today_tasks: list[dict], planned_tasks: list[dict], habits: list[dict], completed_tasks: list[dict], tier: str = "FREE") -> dict:
    """Call Claude to generate a daily morning briefing."""
    client = _get_client()
    day_of_week = today.strftime("%A")
    overdue_json = json.dumps(overdue_tasks, default=str, indent=2)
    today_json = json.dumps(today_tasks, default=str, indent=2)
    planned_json = json.dumps(planned_tasks, default=str, indent=2)
    habits_json = json.dumps(habits, default=str, indent=2)
    completed_json = json.dumps(completed_tasks, default=str, indent=2)
    prompt = f"""You are a productivity coach delivering a concise morning briefing.

Today: {today.isoformat()} ({day_of_week})

User's data:
- Overdue tasks: {overdue_json}
- Today's tasks (due today): {today_json}
- Planned for today: {planned_json}
- Today's habits: {habits_json}
- Completed yesterday: {completed_json}

Generate a morning briefing with:
1. A one-line motivational greeting based on their workload (light/moderate/heavy day)
2. "Top 3 Priorities" with brief reasoning
3. "Heads Up" any overdue items or potential scheduling conflicts
4. "Suggested Task Order" all today's tasks ranked in recommended execution order
5. "Habit Reminders" which habits to complete today

Respond ONLY with valid JSON:
{{"greeting": "Good morning!", "top_priorities": [{{"task_id": 1, "title": "...", "reason": "Due today"}}], "heads_up": ["You have 2 overdue tasks"], "suggested_order": [{{"task_id": 1, "title": "...", "suggested_time": "9:00 AM", "reason": "Hardest task first"}}], "habit_reminders": ["Exercise"], "day_type": "moderate"}}"""

    model = get_model("daily_briefing")
    max_tokens = 4096
    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(model=model, max_tokens=max_tokens, messages=[{"role": "user", "content": prompt}])
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse daily briefing response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Daily briefing AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def generate_weekly_plan(week_start: date, week_end: date, work_days: list[str], focus_hours_per_day: int, prefer_front_loading: bool, tasks: list[dict], recurring_tasks: list[dict], tier: str = "FREE") -> dict:
    """Call Claude to generate a weekly task plan."""
    client = _get_client()
    tasks_json = json.dumps(tasks, default=str, indent=2)
    recurring_json = json.dumps(recurring_tasks, default=str, indent=2)
    prompt = f"""You are a productivity coach creating a weekly plan.

Week: {week_start.isoformat()} to {week_end.isoformat()}
Work days: {', '.join(work_days)}
Max focus hours per day: {focus_hours_per_day}
Front-loading preference: {prefer_front_loading}

User's tasks:
{tasks_json}

Recurring tasks generating this week:
{recurring_json}

Create a day-by-day plan distributing tasks across the week. Consider:
- Hard deadlines, Eisenhower quadrants, estimated durations
- Energy management, project batching, buffer time

Respond ONLY with valid JSON:
{{"plan": {{"Monday": {{"date": "{week_start.isoformat()}", "tasks": [{{"task_id": 1, "title": "...", "suggested_time": "9:00 AM", "duration_minutes": 60, "reason": "Due Tuesday"}}], "total_hours": 5.5, "calendar_events": [], "habits": ["Exercise"]}}}}, "unscheduled": [{{"task_id": 12, "title": "...", "reason": "No deadline"}}], "week_summary": "Moderate week.", "tips": ["Consider delegating Q3 tasks"]}}"""

    model = get_model("weekly_planner")
    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(model=model, max_tokens=8192, messages=[{"role": "user", "content": prompt}])
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse weekly plan response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Weekly plan AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def generate_time_blocks(
    target_date: date,
    day_start: str,
    day_end: str,
    block_size_minutes: int,
    include_breaks: bool,
    break_frequency_minutes: int,
    break_duration_minutes: int,
    tasks: list[dict],
    calendar_events: list[dict],
    tier: str = "FREE",
    horizon_days: int = 1,
    task_signals: list[dict] | None = None,
    existing_blocks: list[dict] | None = None,
    horizon_end: date | None = None,
) -> dict:
    """Call Claude to generate a time-blocked schedule across a horizon.

    v1.4.40: horizon-aware. When ``horizon_days > 1``, the prompt is extended
    with the date range, per-task Eisenhower / Pomodoro signals, and the list
    of pre-existing PrismTask blocks (tasks already scheduled, Pomodoro
    sessions) that Haiku must treat as hard constraints. Each returned
    schedule entry carries the ISO ``date`` it belongs to.

    Backward-compatible: calling without the new kwargs reproduces the
    pre-v1.4.40 single-day prompt.
    """
    client = _get_client()
    day_of_week = target_date.strftime("%A")
    tasks_json = json.dumps(tasks, default=str, indent=2)
    events_json = json.dumps(calendar_events, default=str, indent=2)
    break_instructions = (
        f"- Insert breaks: every {break_frequency_minutes} min of work, take a {break_duration_minutes} min break"
        if include_breaks
        else "- No automatic breaks requested"
    )

    signals_block = json.dumps(task_signals or [], default=str, indent=2)
    existing_block = json.dumps(existing_blocks or [], default=str, indent=2)

    # v1.4.40: emit a horizon header so Haiku knows whether it's writing one
    # day's schedule or a week. Single-day horizon preserves the legacy
    # prompt shape (date, day-of-week) to avoid perturbing model output for
    # existing callers.
    if horizon_days <= 1:
        horizon_header = (
            f"Date: {target_date.isoformat()} ({day_of_week})\n"
            f"Horizon: 1 day"
        )
        date_instruction = (
            f"- Every schedule entry must carry \"date\": \"{target_date.isoformat()}\"."
        )
    else:
        end_date = horizon_end or (target_date + timedelta(days=horizon_days - 1))
        horizon_header = (
            f"Horizon window: {target_date.isoformat()} "
            f"({target_date.strftime('%A')}) "
            f"through {end_date.isoformat()} "
            f"({end_date.strftime('%A')}) — {horizon_days} days total"
        )
        date_instruction = (
            "- Every schedule entry MUST carry a \"date\" field with the ISO "
            "date of the day that block belongs to (YYYY-MM-DD). Distribute "
            "work across the horizon — do not pile everything on day 1."
        )

    prompt = f"""You are a time management coach creating a time-blocked schedule.

{horizon_header}
Available each day: {day_start} to {day_end}
Block size: {block_size_minutes} min minimum

Tasks to schedule:
{tasks_json}

Per-task signals (Eisenhower quadrant, estimated Pomodoro sessions, etc.):
{signals_block}

Pre-existing PrismTask blocks and Pomodoro sessions (HARD CONSTRAINTS — do not schedule over these):
{existing_block}

Fixed calendar events (also cannot be moved):
{events_json}

Ranking and placement rules:
- Eisenhower Q1 (Urgent + Important) and Q2 (Not Urgent + Important) belong in the highest-energy slots early in the day / early in the week.
- Q3 (Urgent + Not Important) is scheduled but later in the day. Q4 (Not Urgent + Not Important) is the first to be deferred to "unscheduled_tasks" when capacity is tight.
- When a task carries ``estimated_pomodoro_sessions``, size its block(s) so the duration covers roughly session_count * 25 min. If no session data is provided, fall back to ``estimated_duration_minutes`` or the default {block_size_minutes} min.
- The pre-existing blocks above are immovable — route work around them, never over them.
- Calendar events are also fixed.
- High-energy tasks in morning, low-energy in afternoon.
- Respect estimated durations; default {block_size_minutes} min when nothing else is known.
{break_instructions}
- Leave 15 min buffer between context-switching blocks.
- Leave at least 30 min unscheduled per day for unexpected work.
{date_instruction}

Respond ONLY with valid JSON (no markdown, no code fences, no prose):
{{"schedule": [{{"start": "09:00", "end": "09:30", "type": "task", "task_id": 1, "title": "Write report", "reason": "Deep work while fresh", "date": "{target_date.isoformat()}"}}], "unscheduled_tasks": [{{"task_id": 7, "title": "...", "reason": "Not enough time today"}}], "stats": {{"total_work_minutes": 300, "total_break_minutes": 60, "total_free_minutes": 60, "tasks_scheduled": 8, "tasks_deferred": 2}}}}"""

    model = get_model("time_blocking")
    # Wider budget for 7-day horizons; single day keeps the legacy 8192 cap.
    max_tokens = 12288 if horizon_days > 2 else 8192
    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(
                model=model,
                max_tokens=max_tokens,
                messages=[{"role": "user", "content": prompt}],
            )
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            # For horizon=1, backfill date on each block so the client can
            # rely on it unconditionally.
            if horizon_days <= 1:
                iso = target_date.isoformat()
                for block in result.get("schedule", []) or []:
                    if isinstance(block, dict) and not block.get("date"):
                        block["date"] = iso
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse time block response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Time block AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def analyze_habit_correlations(daily_data: list[dict], tier: str = "FREE") -> dict:
    """Call Claude to analyze correlations between habit completion and task productivity."""
    client = _get_client()
    daily_json = json.dumps(daily_data, default=str, indent=2)
    prompt = f"""Analyze the correlation between habit completion and task productivity.

Data (last 90 days):
{daily_json}

For each habit, calculate: task completion rate on days done vs not done, correlation direction, and interpretation.

Respond ONLY with valid JSON:
{{"correlations": [{{"habit": "Exercise", "done_productivity": 82, "not_done_productivity": 65, "correlation": "positive", "interpretation": "You complete 26% more tasks on days you exercise"}}], "top_insight": "Exercise has the strongest positive impact", "recommendation": "Try to exercise before starting work"}}"""

    model = get_model("habit_correlation")
    last_error = None
    for attempt in range(2):
        try:
            message = client.messages.create(model=model, max_tokens=4096, messages=[{"role": "user", "content": prompt}])
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to parse habit correlation response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Habit correlation AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


# ---------------------------------------------------------------------------
# v1.4.0 V6 -- AI weekly review
# ---------------------------------------------------------------------------

def _format_task_bullets(tasks: list[dict]) -> str:
    """Render a task list as terse bullets for inclusion in the weekly-review prompt."""
    if not tasks:
        return "(none)"
    lines = []
    for t in tasks:
        parts = [f"- {t.get('title', '').strip() or '(untitled)'}"]
        meta: list[str] = []
        if t.get("priority") is not None:
            meta.append(f"p{t['priority']}")
        if t.get("eisenhower_quadrant"):
            meta.append(str(t["eisenhower_quadrant"]))
        if t.get("life_category"):
            meta.append(str(t["life_category"]))
        if t.get("due_date"):
            meta.append(f"due {t['due_date']}")
        if t.get("completed_at"):
            meta.append(f"done {t['completed_at']}")
        if meta:
            parts.append(f"  [{', '.join(meta)}]")
        lines.append(" ".join(parts))
    return "\n".join(lines)


def generate_weekly_review(
    week_start: str,
    week_end: str,
    completed_tasks: list[dict],
    slipped_tasks: list[dict],
    open_tasks: list[dict],
    habit_summary: dict | None = None,
    pomodoro_summary: dict | None = None,
    notes: str | None = None,
    tier: str = "FREE",
) -> dict:
    """Generate a forgiveness-first weekly review narrative via Claude.

    Inputs are four structured lists plus optional opaque summaries:
      * completed_tasks — what shipped this week (from client body)
      * slipped_tasks   — due/planned but not completed (from client body)
      * open_tasks      — what's currently on the user's plate (Firestore,
                          ranked by priority DESC, due_date ASC (nulls last),
                          sort_order ASC, capped to 20 upstream)
      * habit_summary / pomodoro_summary — opaque dicts forwarded verbatim
      * notes — free-form text the user wrote about their week

    Uses Sonnet via the ``monthly_review`` feature flag for higher-quality
    narrative output — do not change without coordinating pricing.
    """
    client = _get_client()

    completed_block = _format_task_bullets(completed_tasks)
    slipped_block = _format_task_bullets(slipped_tasks)
    open_block = _format_task_bullets(open_tasks)
    habit_block = json.dumps(habit_summary, default=str) if habit_summary else "(not provided)"
    pomodoro_block = json.dumps(pomodoro_summary, default=str) if pomodoro_summary else "(not provided)"
    notes_block = notes.strip() if notes and notes.strip() else "(none)"

    prompt = f"""You are a compassionate productivity coach writing a weekly review for someone with ADHD. Use a forgiveness-first, supportive tone. Ground every observation in the specifics below — don't invent tasks or metrics.

Week under review: {week_start} to {week_end}

This week the user completed {len(completed_tasks)} task(s):
{completed_block}

They had {len(slipped_tasks)} task(s) that slipped (due or planned this week but not completed):
{slipped_block}

Currently open on their plate going forward: {len(open_tasks)} task(s) (top 20 by priority and due date):
{open_block}

Habit summary (opaque client-provided aggregate):
{habit_block}

Pomodoro summary (opaque client-provided aggregate):
{pomodoro_block}

User's notes about the week:
{notes_block}

Write a review with:
- wins: 2-3 specific accomplishments, citing completed tasks by name where possible.
- slips: 2-3 honest, non-judgmental observations on what didn't happen, citing slipped task titles where they make the point land.
- patterns: 2-3 trends you notice across completed/slipped/open (e.g. "Q2 tasks consistently get deferred", "self-care category dominated completions").
- next_week_focus: 2-3 concrete suggestions for next week, anchored in what's currently open.
- narrative: 2-3 sentence prose summary capturing the overall arc and emotional tone of the week.

Respond ONLY with valid JSON in this exact shape:
{{"wins": ["..."], "slips": ["..."], "patterns": ["..."], "next_week_focus": ["..."], "narrative": "..."}}"""

    model = get_model("monthly_review")
    max_tokens = 2048
    last_error_wr = None
    for attempt in range(2):
        try:
            message = client.messages.create(model=model, max_tokens=max_tokens, messages=[{"role": "user", "content": prompt}])
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object")
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error_wr = e
            logger.error(f"Failed to parse weekly review response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Weekly review AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error_wr}")


# ---------------------------------------------------------------------------
# v1.4.0 V9 -- conversation -> tasks extraction
# ---------------------------------------------------------------------------

def extract_tasks_from_text(text: str, source: str | None = None, tier: str = "FREE") -> list[dict]:
    """Extract structured task candidates from pasted conversation text."""
    if not text or not text.strip():
        return []
    if len(text) > 10_000:
        text = text[:10_000]

    client = _get_client()
    source_label = f" (source: {source})" if source else ""
    prompt = f"""You are an assistant that extracts action items from conversation text{source_label}.

Text:
---
{text}
---

For each action item, return: title (imperative, Title case, under 12 words), suggested_due_date (ISO or null), suggested_priority (0-4), suggested_project (one-word or null), confidence (0-1).

Only extract clear action items. Ignore general discussion.

Respond ONLY with valid JSON:
[{{"title": "Send the design mocks to Alice", "suggested_due_date": null, "suggested_priority": 2, "suggested_project": null, "confidence": 0.9}}]

Return an empty array if no action items are found."""

    model = get_model("task_extraction")
    last_error_ex = None
    for attempt in range(2):
        try:
            message = client.messages.create(model=model, max_tokens=2048, messages=[{"role": "user", "content": prompt}])
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, list):
                raise ValueError("Expected a JSON array")
            return result
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error_ex = e
            logger.error(f"Failed to parse task extraction response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Task extraction AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse AI response: {last_error_ex}")


# --- Pomodoro AI Coaching (pre-session / break / recap) ---


def _pomodoro_coaching_prompt(
    trigger: str,
    upcoming_tasks: list[dict] | None,
    session_length_minutes: int | None,
    elapsed_minutes: int | None,
    break_type: str | None,
    recent_suggestions: list[str] | None,
    completed_tasks: list[dict] | None,
    started_tasks: list[dict] | None,
    session_duration_minutes: int | None,
) -> str:
    """Build the Haiku prompt for one of the three Pomodoro coaching surfaces.

    Each surface is a single-sentence, friendly coaching response rendered
    straight to the user. Prompts deliberately bias toward concrete,
    low-friction guidance over generic motivation.
    """
    if trigger == "pre_session":
        tasks_json = json.dumps(upcoming_tasks or [], default=str, indent=2)
        return f"""You are a focused productivity coach about to kick off a {session_length_minutes or 25}-minute Pomodoro session.

The user is about to work on these tasks:
{tasks_json}

Write 1-2 sentences (no more than ~40 words total) suggesting a concrete starting approach — which task to start on, or how to sequence them, or what small first step to take. Be specific, warm, and action-oriented. No bullet points, no greetings, no sign-offs — just the coaching sentence(s) directly."""

    if trigger == "break_activity":
        recent_json = json.dumps(recent_suggestions or [])
        kind = break_type or "short"
        return f"""You are a productivity coach suggesting a break activity.

The user just finished a focus block and is on a {kind} break ({elapsed_minutes or 0} minutes of work elapsed this session).

Suggest one concrete, quick break activity that fits a {kind} break. Vary between categories: stretches, hydration, eye-rest (20-20-20 rule), brief walk, breathing, posture reset. AVOID repeating any of these recent suggestions: {recent_json}

Respond with 1 sentence (no more than ~25 words). No preamble, no sign-off — just the suggestion directly."""

    if trigger == "session_recap":
        completed_json = json.dumps(completed_tasks or [], default=str, indent=2)
        started_json = json.dumps(started_tasks or [], default=str, indent=2)
        return f"""You are a supportive productivity coach wrapping up a {session_duration_minutes or 0}-minute Pomodoro session.

Completed tasks:
{completed_json}

Started-but-not-finished tasks:
{started_json}

Write 2 short sentences (no more than ~50 words total):
1. A warm, specific acknowledgment of what got done (name a task if possible).
2. One concrete "carry forward" suggestion for the started-but-unfinished work, or a suggested next micro-step if everything's done.

No bullet points, no greeting, no sign-off."""

    raise ValueError(f"Unknown Pomodoro coaching trigger: {trigger}")


def generate_pomodoro_coaching(
    trigger: str,
    upcoming_tasks: list[dict] | None = None,
    session_length_minutes: int | None = None,
    elapsed_minutes: int | None = None,
    break_type: str | None = None,
    recent_suggestions: list[str] | None = None,
    completed_tasks: list[dict] | None = None,
    started_tasks: list[dict] | None = None,
    session_duration_minutes: int | None = None,
    tier: str = "FREE",
) -> str:
    """Call Claude Haiku to produce one coaching sentence for a Pomodoro surface.

    Returns the plain-text message. Unlike the JSON-responding AI endpoints
    (eisenhower, pomodoro-plan, etc.), coaching surfaces speak directly to the
    user and don't need a JSON envelope — keeps the prompt tight and the
    response fast.
    """
    client = _get_client()
    model = get_model("pomodoro_coaching")
    prompt = _pomodoro_coaching_prompt(
        trigger=trigger,
        upcoming_tasks=upcoming_tasks,
        session_length_minutes=session_length_minutes,
        elapsed_minutes=elapsed_minutes,
        break_type=break_type,
        recent_suggestions=recent_suggestions,
        completed_tasks=completed_tasks,
        started_tasks=started_tasks,
        session_duration_minutes=session_duration_minutes,
    )
    try:
        message = client.messages.create(
            model=model,
            max_tokens=256,
            messages=[{"role": "user", "content": prompt}],
        )
        text = message.content[0].text.strip()
        # Defensive strip: some Haiku replies add stray quotes or a trailing newline.
        return text.strip('"').strip()
    except Exception as e:
        logger.error(f"Pomodoro coaching AI error ({trigger}): {type(e).__name__}: {e}")
        raise


# ---------------------------------------------------------------------------
# A2 — NLP batch schedule operations (pulled from Phase H)
# ---------------------------------------------------------------------------

_BATCH_PARSE_SYSTEM_PROMPT = """You are a productivity assistant that turns a single natural-language command into a structured batch of mutations on a user's tasks, habits, projects, and medications.

You are given:
- The user's command (one short sentence, e.g. "Cancel everything Friday")
- Today's date in the user's local timezone
- The user's timezone
- A list of TASKS the user has, with id, title, due_date, scheduled_start_time, priority, project, tags, life_category, is_completed
- A list of HABITS the user tracks, with id and name
- A list of PROJECTS the user has, with id and name
- A list of MEDICATIONS the user takes, with id, name, and optional display_label (a user-facing alias, e.g. brand name when name is the generic). When matching a user's spoken phrase against medications, consider both 'name' and 'display_label' as valid match targets.
- A `committed_medication_matches` map (phrase -> medication_id) the client has already deterministically resolved. When a medication mention in the command matches one of these phrases, you MUST emit the committed entity_id and MUST NOT flag the phrase in `ambiguous_entities`. These are authoritative — they override any guess you would otherwise make.
- A `forced_ambiguous_phrases` list the client classified as ambiguous (multiple candidate medications). Do not propose mutations for these phrases; the service will unconditionally append them to your `ambiguous_entities` response so the user can disambiguate.

You must return strict JSON with this shape (no prose, no markdown fences):
{
  "mutations": [
    {
      "entity_type": "TASK" | "HABIT" | "PROJECT" | "MEDICATION",
      "entity_id": "<id from the input>",
      "mutation_type": "RESCHEDULE" | "DELETE" | "COMPLETE" | "SKIP" | "PRIORITY_CHANGE" | "TAG_CHANGE" | "PROJECT_MOVE" | "ARCHIVE" | "STATE_CHANGE",
      "proposed_new_values": { ... },
      "human_readable_description": "<one short sentence describing the change>"
    }
  ],
  "confidence": <float 0.0..1.0, your confidence the user actually wanted this batch>,
  "ambiguous_entities": [
    {
      "phrase": "<the user's words that were ambiguous>",
      "candidate_entity_type": "TASK" | "HABIT" | "PROJECT" | "MEDICATION",
      "candidate_entity_ids": ["<id>", "<id>"],
      "note": "<short hint for the resolution dialog>"
    }
  ]
}

Per-mutation `proposed_new_values` schemas:
- RESCHEDULE on TASK: {"due_date": "YYYY-MM-DD"} or {"scheduled_start_time": "YYYY-MM-DDTHH:MM:SS"}
- RESCHEDULE on HABIT: {"date": "YYYY-MM-DD"} (one-off shift of one occurrence)
- DELETE: {} (no values needed)
- COMPLETE on TASK: {} ; on HABIT: {"date": "YYYY-MM-DD"} ; on MEDICATION: {"date": "YYYY-MM-DD", "slot_key": "morning"}
- SKIP on HABIT: {"date": "YYYY-MM-DD"} ; on MEDICATION: {"date": "YYYY-MM-DD", "slot_key": "morning"}
- PRIORITY_CHANGE on TASK: {"priority": 0..4}
- TAG_CHANGE on TASK: {"tags_added": ["..."], "tags_removed": ["..."]}
- PROJECT_MOVE on TASK: {"project_id": "<id from input or null>"}
- ARCHIVE on PROJECT or HABIT: {}
- STATE_CHANGE on MEDICATION: {"tier": "skipped"|"essential"|"prescription"|"complete", "date": "YYYY-MM-DD", "slot_key": "morning"} (manual tier override; prefer COMPLETE/SKIP unless the user explicitly names a tier)

Hard rules — break any of these and the request fails:
1. Never invent entity IDs. Only use IDs that appear in the input lists.
2. Return only the mutations the user plausibly intended. Do not add "bonus" mutations.
3. If the user's command is ambiguous (e.g. "work tasks" maps to two projects, or a date phrase is unclear), do NOT guess — add an entry to `ambiguous_entities` and either skip the affected mutations or include them with a low confidence.
4. Date parsing rules (today is the date you're given):
   - "today" / "tonight" → today
   - "tomorrow" → today + 1 day
   - "Monday".."Sunday" without a qualifier → the next occurrence on or after today
   - "next Monday".."next Sunday" → the occurrence in the following Mon-Sun week
   - "this week" → Mon..Sun of the current week (Mon = week start)
   - "next week" → Mon..Sun of the following week
   - "the weekend" → Sat + Sun of the current or upcoming weekend
5. "All tasks tagged X" matches tasks where `tags` contains the lowercased phrase.
6. "Everything Friday" / "everything on Friday" matches incomplete tasks whose due_date OR scheduled_start_time falls on Friday.
7. Cancel = DELETE on TASK, SKIP on HABIT, ARCHIVE on PROJECT (use the most reasonable mapping; if unclear, lower the confidence).
8. Confidence: 1.0 = certain. 0.7-0.9 = mostly certain but a date or filter was inferred. <0.7 = significant ambiguity, surface in `ambiguous_entities`.
9. Output JSON only. No code fences. No prose. No trailing commentary.

If the command makes no sense or matches no entities, return `{"mutations": [], "confidence": 0.0, "ambiguous_entities": []}`."""


def _enforce_medication_match_guards(
    mutations: list[dict],
    ambiguous_entities: list[dict],
    *,
    known_medication_ids: set[str],
    forced_ambiguous_phrases: list[dict],
) -> tuple[list[dict], list[dict]]:
    """Defensive backend guards that close failure modes #1 and #2 even
    if Haiku ignores the system-prompt instructions:

    1. Drop any MEDICATION mutation whose entity_id isn't in the known
       medications list (Haiku can't legally invent ids; this is the
       last-line firewall against the silent wrong-pick failure mode).
    2. Append every client-supplied forced_ambiguous_phrase to the
       response's ambiguous_entities, deduplicated by (phrase, ids).
    """

    safe_mutations: list[dict] = []
    for mutation in mutations:
        if not isinstance(mutation, dict):
            continue
        if mutation.get("entity_type") == "MEDICATION":
            entity_id = mutation.get("entity_id")
            if entity_id is None or str(entity_id) not in known_medication_ids:
                logger.info(
                    "batch_parse: dropping MEDICATION mutation with unknown id %r",
                    entity_id,
                )
                continue
        safe_mutations.append(mutation)

    seen: set[tuple[str, tuple[str, ...]]] = set()
    for hint in ambiguous_entities:
        if not isinstance(hint, dict):
            continue
        seen.add(
            (
                str(hint.get("phrase") or ""),
                tuple(sorted(str(i) for i in (hint.get("candidate_entity_ids") or []))),
            )
        )
    for forced in forced_ambiguous_phrases:
        if not isinstance(forced, dict):
            continue
        phrase = str(forced.get("phrase") or "")
        ids = tuple(
            sorted(str(i) for i in (forced.get("candidate_entity_ids") or []))
        )
        key = (phrase, ids)
        if key in seen:
            continue
        seen.add(key)
        ambiguous_entities.append(
            {
                "phrase": phrase,
                "candidate_entity_type": forced.get(
                    "candidate_entity_type", "MEDICATION"
                ),
                "candidate_entity_ids": list(ids),
                "note": forced.get("note"),
            }
        )
    return safe_mutations, ambiguous_entities


def parse_batch_command(
    command_text: str,
    user_context: dict,
    tier: str = "PRO",
) -> dict:
    """Call Claude Haiku to parse a natural-language batch command into
    a structured mutation plan.

    Returns a dict with shape:
        {"mutations": [...], "confidence": float,
         "ambiguous_entities": [...]}

    Raises:
        RuntimeError: Anthropic client unavailable / API key missing.
        ValueError:   AI returned a malformed response after retry.
    """

    client = _get_client()
    model = get_model("batch_parse")

    # Trim the context the model sees: completed tasks rarely matter for a
    # batch command and they bloat the prompt. Cap each list at a sane
    # ceiling to keep token usage bounded — the client should already be
    # filtering, but defense in depth.
    ctx = dict(user_context)
    tasks = ctx.get("tasks") or []
    if isinstance(tasks, list):
        # Drop completed tasks and cap at 200 to bound token usage.
        ctx["tasks"] = [t for t in tasks if not t.get("is_completed")][:200]
    if isinstance(ctx.get("habits"), list):
        ctx["habits"] = [h for h in ctx["habits"] if not h.get("is_archived")][:100]
    if isinstance(ctx.get("projects"), list):
        ctx["projects"] = ctx["projects"][:100]
    if isinstance(ctx.get("medications"), list):
        ctx["medications"] = ctx["medications"][:50]

    known_medication_ids: set[str] = {
        str(m["id"])
        for m in (ctx.get("medications") or [])
        if isinstance(m, dict) and m.get("id") is not None
    }
    forced_ambiguous_phrases = ctx.get("forced_ambiguous_phrases") or []
    if not isinstance(forced_ambiguous_phrases, list):
        forced_ambiguous_phrases = []

    user_payload = json.dumps(
        {"command": command_text, "context": ctx},
        default=str,
        indent=2,
    )

    last_error: Exception | None = None
    for attempt in range(2):
        try:
            message = client.messages.create(
                model=model,
                max_tokens=4096,
                system=_BATCH_PARSE_SYSTEM_PROMPT,
                messages=[{"role": "user", "content": user_payload}],
            )
            content = message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object at top level")
            # Shape-validate the required keys; the router does the
            # pydantic-level validation but we want a clean error here.
            if "mutations" not in result:
                raise ValueError("Response missing required key 'mutations'")
            mutations = result.get("mutations") or []
            ambiguous_entities = result.get("ambiguous_entities") or []
            mutations, ambiguous_entities = _enforce_medication_match_guards(
                mutations,
                ambiguous_entities,
                known_medication_ids=known_medication_ids,
                forced_ambiguous_phrases=forced_ambiguous_phrases,
            )
            return {
                "mutations": mutations,
                "confidence": float(result.get("confidence") or 0.0),
                "ambiguous_entities": ambiguous_entities,
            }
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(
                f"Failed to parse batch-parse response (attempt {attempt + 1}): {e}"
            )
            if attempt == 0:
                continue
            raise ValueError(
                f"Failed to parse batch-parse response after retry: {e}"
            ) from e
        except Exception as e:
            logger.error(f"Batch-parse AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse batch-parse response: {last_error}")


# ---------------------------------------------------------------------------
# Conversational AI Coach (chat)
# ---------------------------------------------------------------------------

_CHAT_SYSTEM_PROMPT = """You are PrismTask's conversational productivity coach. The user is in a one-on-one chat with you inside an Android task-management app. Be warm, concise, and concrete — never preachy. Default to 1-3 short sentences; only go longer when the user explicitly asks.

You can suggest inline action buttons that the app will render under your reply. Only emit actions that map to a real user need expressed in the most recent message. NEVER invent task IDs or fabricate references the user did not give you.

When the user opens chat from a specific task, the latest user turn carries a `task_context` block with the actual title, description, due date, and priority. Ground your reply in those concrete fields — refer to the task by its title, not the opaque id. The `task_context_id` integer is the handle the action buttons must echo back; only the user-facing reply text should mention the task by title.

Allowed action shapes (use exactly these `type` values; omit any unused fields):
- {"type": "create_task", "title": "...", "due": "today|tomorrow|next_week|YYYY-MM-DD", "priority": "low|medium|high|urgent"}
- {"type": "start_timer", "minutes": 25}
- When the user is talking about a specific task and you have been given a `task_context_id`, you MAY suggest:
  - {"type": "complete", "task_id": "<task_context_id>"}
  - {"type": "reschedule", "task_id": "<task_context_id>", "to": "today|tomorrow|next_week|YYYY-MM-DD"}
  - {"type": "breakdown", "task_id": "<task_context_id>", "subtasks": ["...", "..."]}
  - {"type": "archive", "task_id": "<task_context_id>"}

Hard rules:
1. Output STRICT JSON only. No markdown fences, no prose outside JSON.
2. Top-level shape: {"message": "<your reply>", "actions": [<0..N action objects>]}.
3. `actions` may be an empty list — prefer that over emitting weak suggestions.
4. Only reference a task_id when the user has either (a) explicitly given you one in their message or (b) you have a `task_context_id` in the prompt.
5. For `breakdown`, propose 2-5 concrete subtasks expressed as imperative phrases (e.g. "Draft outline", "Review with team").
6. Never invent due dates beyond today/tomorrow/next_week unless the user named one.
7. Stay supportive and practical. Avoid moralizing about productivity.

If the user message is small talk or unclear, just reply with `{"message": "<friendly short reply>", "actions": []}`."""


def generate_chat_response(
    message: str,
    conversation_id: str,
    task_context_id: int | None = None,
    task_context: dict | None = None,
    history: list[dict] | None = None,
) -> dict:
    """Call Claude Haiku to produce a conversational chat reply + optional actions.

    The backend is stateless from the conversation's POV: the client owns
    the rolling history and forwards it on every turn via ``history``. The
    list arrives as ``[{"role": "user"|"assistant", "content": "..."}]``
    in chronological order, capped to 12 entries (last 6 user/assistant
    pairs) by ``ChatRequest`` validation.

    ``task_context`` is the snapshot of the task the user is talking about,
    forwarded by the client when chat is opened from a task. Without it the
    AI has only the opaque ``task_context_id`` integer to echo into actions.

    Returns a dict with shape::

        {
          "message": "<assistant reply text>",
          "actions": [<0..N action dicts>],
          "tokens_used": {"input": int, "output": int},
        }

    Raises:
        RuntimeError: Anthropic client unavailable / API key missing.
        ValueError:   AI returned a malformed response after retry.
    """
    client = _get_client()
    model = get_model("chat")

    user_block: dict = {
        "conversation_id": conversation_id,
        "task_context_id": task_context_id,
        "user_message": message,
    }
    if task_context:
        user_block["task_context"] = task_context
    user_payload = json.dumps(user_block, default=str, indent=2)

    # Build the Anthropic messages array: rolling history pairs first
    # (chronological), then the latest user turn carrying the structured
    # context block. The server-side validation already capped history to
    # 12 entries; we still defensively trim here in case a future caller
    # bypasses the schema (e.g. a unit test).
    anthropic_messages: list[dict] = []
    for entry in (history or [])[-12:]:
        role = entry.get("role")
        content = entry.get("content")
        if role in ("user", "assistant") and isinstance(content, str) and content:
            anthropic_messages.append({"role": role, "content": content})
    anthropic_messages.append({"role": "user", "content": user_payload})

    last_error: Exception | None = None
    for attempt in range(2):
        try:
            ai_message = client.messages.create(
                model=model,
                max_tokens=1024,
                system=_CHAT_SYSTEM_PROMPT,
                messages=anthropic_messages,
            )
            content = ai_message.content[0].text
            result = _parse_ai_json(content)
            if not isinstance(result, dict):
                raise ValueError("Expected a JSON object at top level")
            reply = result.get("message")
            if not isinstance(reply, str) or not reply.strip():
                raise ValueError("Response missing required string 'message'")
            actions_raw = result.get("actions") or []
            if not isinstance(actions_raw, list):
                raise ValueError("'actions' must be a list when present")

            usage = getattr(ai_message, "usage", None)
            tokens_used = {
                "input": int(getattr(usage, "input_tokens", 0) or 0),
                "output": int(getattr(usage, "output_tokens", 0) or 0),
            }
            return {
                "message": reply.strip(),
                "actions": actions_raw,
                "tokens_used": tokens_used,
            }
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(
                f"Failed to parse chat response (attempt {attempt + 1}): {e}"
            )
            if attempt == 0:
                continue
            raise ValueError(
                f"Failed to parse chat response after retry: {e}"
            ) from e
        except Exception as e:
            logger.error(f"Chat AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to parse chat response: {last_error}")


# ---------------------------------------------------------------------------
# A7 — Automation action AI (ai.complete / ai.summarize)
# ---------------------------------------------------------------------------
#
# Plain-text completions for the on-device automation engine. Both
# functions deliberately skip the JSON envelope used elsewhere: the
# results are stored verbatim on the firing log and rendered straight to
# the user, so a tighter prompt + raw text response is the right fit.


_AUTOMATION_COMPLETE_SYSTEM_PROMPT = (
    "You are an assistant invoked from a user's PrismTask automation rule. "
    "Respond to the user's prompt directly and concisely (1-3 sentences "
    "unless the prompt explicitly asks for more). The response is rendered "
    "as plain text in the rule's firing log — no markdown headings, no "
    "bullet lists unless the user asks. If the rule supplies a context "
    "object describing the trigger entity, ground your answer in that "
    "context."
)


def generate_automation_completion(
    prompt: str,
    context: dict | None = None,
    tier: str = "PRO",
) -> str:
    """Run a free-form Anthropic completion for an ``ai.complete`` action.

    Returns the assistant's reply as plain text. Raises ``RuntimeError``
    if the Anthropic client can't be constructed (missing key or import),
    and re-raises other transport errors.
    """
    client = _get_client()
    model = get_model("automation_complete")

    user_block: dict = {"prompt": prompt}
    if context:
        user_block["context"] = context
    user_payload = json.dumps(user_block, default=str, indent=2)

    try:
        ai_message = client.messages.create(
            model=model,
            max_tokens=1024,
            system=_AUTOMATION_COMPLETE_SYSTEM_PROMPT,
            messages=[{"role": "user", "content": user_payload}],
        )
        text = ai_message.content[0].text.strip()
        # Defensive strip: some Haiku replies add stray quotes or trailing
        # whitespace.
        return text.strip('"').strip()
    except Exception as e:
        logger.error(
            f"Automation complete AI error: {type(e).__name__}: {e}"
        )
        raise


_AUTOMATION_SUMMARIZE_SYSTEM_PROMPT = (
    "You are an assistant invoked from a user's PrismTask automation rule. "
    "Summarize the user's PrismTask activity for the named scope ('today', "
    "'week', 'month', etc.) in 1-3 sentences. Lead with what got done, "
    "follow with what's outstanding, and close with one concrete next "
    "step if the data warrants it. Plain text — no markdown, no bullet "
    "lists. If a context object is supplied, ground the summary in it; "
    "otherwise produce a generic encouragement that respects the scope."
)


def generate_automation_summary(
    scope: str,
    max_items: int = 50,
    context: dict | None = None,
    tier: str = "PRO",
) -> str:
    """Run a scoped Anthropic summary for an ``ai.summarize`` action.

    The handler passes a ``scope`` identifier ("today" / "week" / etc.)
    and a hard cap on how many entities the prompt can mention. Returns
    the assistant's summary as plain text.
    """
    client = _get_client()
    model = get_model("automation_summarize")

    user_block: dict = {"scope": scope, "max_items": int(max_items)}
    if context:
        user_block["context"] = context
    user_payload = json.dumps(user_block, default=str, indent=2)

    try:
        ai_message = client.messages.create(
            model=model,
            max_tokens=1024,
            system=_AUTOMATION_SUMMARIZE_SYSTEM_PROMPT,
            messages=[{"role": "user", "content": user_payload}],
        )
        text = ai_message.content[0].text.strip()
        return text.strip('"').strip()
    except Exception as e:
        logger.error(
            f"Automation summarize AI error: {type(e).__name__}: {e}"
        )
        raise
