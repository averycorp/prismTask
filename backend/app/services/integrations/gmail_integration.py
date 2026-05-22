"""Gmail integration — scans inbox for actionable tasks via Claude Haiku."""

import json
import logging
import os
from datetime import date, datetime, timezone

try:
    import anthropic
except ImportError:
    anthropic = None  # type: ignore

try:
    from googleapiclient.discovery import build as google_build
    from google.oauth2.credentials import Credentials as GoogleCredentials
except ImportError:
    google_build = None  # type: ignore
    GoogleCredentials = None  # type: ignore

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models import IntegrationSource
from app.services.integrations.base import (
    get_user_projects,
    get_user_tags,
    store_suggestions,
)

logger = logging.getLogger(__name__)

GMAIL_SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]


def _strip_code_fences(text: str) -> str:
    """Remove markdown code fences from a response."""
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else text[3:]
    if text.endswith("```"):
        text = text[:-3]
    return text.strip()


def _build_extraction_prompt(
    emails: list[dict],
    projects: list[str],
    tags: list[str],
    today: date,
) -> str:
    emails_json = json.dumps(emails, indent=2, default=str)
    projects_str = ", ".join(projects) if projects else "none"
    tags_str = ", ".join(tags) if tags else "none"

    return f"""You are an email-to-task extraction assistant.

Analyze these emails and extract actionable tasks. Not every email is a task —
only extract clear action items, requests, deadlines, or commitments.

Emails:
{emails_json}

User's existing projects: {projects_str}
User's existing tags: {tags_str}
Today's date: {today.isoformat()}

For each email that contains an actionable task, extract:
- suggested_title: clear, concise task title
- suggested_description: relevant context from the email
- suggested_due_date: if a deadline is mentioned (ISO format YYYY-MM-DD)
- suggested_priority: 0-4 based on urgency cues (0=none, 1=low, 2=medium, 3=high, 4=urgent)
- suggested_project: best matching project from user's list (or null)
- suggested_tags: relevant tags from user's list
- confidence: 0.0-1.0 how confident you are this is a real task

Skip emails that are:
- Newsletters, marketing, automated notifications
- FYI/informational with no action needed
- Already-completed items (receipts, confirmations)

Respond ONLY with valid JSON:
{{
  "tasks": [
    {{
      "email_id": "...",
      "email_subject": "...",
      "suggested_title": "Reply to John about Q3 budget",
      "suggested_description": "John asked for budget estimates by Friday",
      "suggested_due_date": "2026-04-11",
      "suggested_priority": 3,
      "suggested_project": "Work",
      "suggested_tags": ["email"],
      "confidence": 0.85
    }}
  ],
  "skipped": [
    {{"email_id": "...", "reason": "Newsletter, no action needed"}}
  ]
}}"""


def _fetch_emails_via_api(
    credentials_json: str,
    since_hours: int = 24,
) -> list[dict]:
    """Fetch recent emails using Google Gmail API.

    *credentials_json* should be a JSON string with the user's OAuth2
    credentials (access_token, refresh_token, token_uri, client_id,
    client_secret).
    """
    if google_build is None or GoogleCredentials is None:
        raise RuntimeError(
            "google-api-python-client and google-auth are required for Gmail integration"
        )

    creds_data = json.loads(credentials_json)
    creds = GoogleCredentials(
        token=creds_data.get("access_token"),
        refresh_token=creds_data.get("refresh_token"),
        token_uri=creds_data.get("token_uri", "https://oauth2.googleapis.com/token"),
        client_id=creds_data.get("client_id"),
        client_secret=creds_data.get("client_secret"),
        scopes=GMAIL_SCOPES,
    )

    service = google_build("gmail", "v1", credentials=creds)

    # Build query for recent emails
    from datetime import timedelta

    after_epoch = int(
        (datetime.now(timezone.utc) - timedelta(hours=since_hours)).timestamp()
    )
    query = f"after:{after_epoch} (is:unread OR is:starred)"

    results = (
        service.users()
        .messages()
        .list(userId="me", q=query, maxResults=20)
        .execute()
    )

    messages = results.get("messages", [])
    emails: list[dict] = []

    if not messages:
        return emails

    def callback(request_id: str, response: dict, exception: Exception | None) -> None:
        if exception is not None:
            logger.warning(f"Error fetching email {request_id}: {exception}")
            return

        headers = {h["name"]: h["value"] for h in response.get("payload", {}).get("headers", [])}
        emails.append({
            "email_id": response.get("id"),
            "subject": headers.get("Subject", "(no subject)"),
            "from": headers.get("From", ""),
            "date": headers.get("Date", ""),
            "snippet": response.get("snippet", "")[:500],
        })

    batch = service.new_batch_http_request()
    for msg_meta in messages:
        batch.add(
            service.users().messages().get(
                userId="me", id=msg_meta["id"], format="metadata",
                metadataHeaders=["Subject", "From", "Date"]
            ),
            callback=callback
        )

    batch.execute()

    return emails


async def scan_gmail(
    db: AsyncSession,
    user_id: int,
    since_hours: int = 24,
    credentials_json: str | None = None,
    emails_override: list[dict] | None = None,
) -> list[dict]:
    """Scan Gmail inbox and extract task suggestions via Claude Haiku.

    Parameters
    ----------
    db : AsyncSession
    user_id : int
    since_hours : int
        How many hours back to look (default 24).
    credentials_json : str | None
        JSON string with OAuth2 credentials for Gmail API.
    emails_override : list[dict] | None
        If provided, skip the Gmail API call and use these emails directly
        (useful for testing).

    Returns
    -------
    list[dict]
        The list of newly stored SuggestedTask dicts.
    """
    # 1. Fetch emails
    if emails_override is not None:
        emails = emails_override
    elif credentials_json:
        emails = _fetch_emails_via_api(credentials_json, since_hours)
    else:
        logger.warning("No Gmail credentials or email override provided")
        return []

    if not emails:
        logger.info("No emails found to scan")
        return []

    # 2. Gather user context
    projects = await get_user_projects(db, user_id)
    tags = await get_user_tags(db, user_id)
    today = date.today()

    # 3. Call Claude Haiku for extraction
    api_key = os.environ.get("ANTHROPIC_API_KEY") or settings.ANTHROPIC_API_KEY
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY is not set")

    if anthropic is None:
        raise RuntimeError("anthropic package is not installed")

    client = anthropic.Anthropic(api_key=api_key)
    prompt = _build_extraction_prompt(emails, projects, tags, today)

    last_error: Exception | None = None
    parsed: dict | None = None
    for attempt in range(2):
        try:
            message = client.messages.create(
                model="claude-haiku-4-5-20251001",
                max_tokens=1024,
                messages=[{"role": "user", "content": prompt}],
            )
            content = message.content[0].text
            content = _strip_code_fences(content)
            parsed = json.loads(content)
            break
        except (json.JSONDecodeError, KeyError, TypeError, IndexError) as e:
            last_error = e
            logger.error(f"Gmail extraction parse error (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse Gmail extraction after retry: {e}") from e
        except Exception as e:
            logger.error(f"Gmail extraction error: {e}")
            raise

    if parsed is None:
        raise ValueError(f"Failed to parse Gmail extraction: {last_error}")

    # 4. Build suggestion dicts and store
    extracted_tasks = parsed.get("tasks", [])
    suggestion_dicts: list[dict] = []
    for t in extracted_tasks:
        suggestion_dicts.append({
            "source_id": t.get("email_id", ""),
            "source_title": t.get("email_subject", ""),
            "source_url": None,
            "suggested_title": t["suggested_title"],
            "suggested_description": t.get("suggested_description"),
            "suggested_due_date": t.get("suggested_due_date"),
            "suggested_priority": t.get("suggested_priority"),
            "suggested_project": t.get("suggested_project"),
            "suggested_tags": t.get("suggested_tags"),
            "confidence": t.get("confidence", 0.5),
        })

    stored = await store_suggestions(
        db, user_id, suggestion_dicts, IntegrationSource.GMAIL
    )

    return [
        {
            "id": s.id,
            "source": s.source.value if hasattr(s.source, "value") else s.source,
            "source_id": s.source_id,
            "source_title": s.source_title,
            "suggested_title": s.suggested_title,
            "suggested_description": s.suggested_description,
            "suggested_due_date": s.suggested_due_date.isoformat() if s.suggested_due_date else None,
            "suggested_priority": s.suggested_priority,
            "suggested_project": s.suggested_project,
            "confidence": s.confidence,
            "status": s.status.value if hasattr(s.status, "value") else s.status,
        }
        for s in stored
    ]
