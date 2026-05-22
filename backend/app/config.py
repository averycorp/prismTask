from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    DATABASE_URL: str = "postgresql+asyncpg://averytask:averytask@localhost:5432/averytask"
    JWT_SECRET_KEY: str = ""
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 15
    REFRESH_TOKEN_EXPIRE_DAYS: int = 7
    ANTHROPIC_API_KEY: str = ""
    DEPLOY_API_KEY: str = ""
    GOOGLE_CLIENT_ID: str = ""
    GOOGLE_CLIENT_SECRET: str = ""
    GOOGLE_CALENDAR_REDIRECT_URI: str = ""
    GOOGLE_CALENDAR_WEBHOOK_URL: str = ""
    # Symmetric key used to encrypt Google OAuth refresh tokens at rest.
    # Production MUST set this; dev auto-generates a per-process key which
    # means tokens stored across restarts cannot be decrypted — acceptable
    # locally.
    INTEGRATION_ENCRYPTION_KEY: str = ""
    FIREBASE_SERVICE_ACCOUNT_KEY: str = ""
    FIREBASE_SERVICE_ACCOUNT_KEY_PATH: str = ""
    FIREBASE_STORAGE_BUCKET: str = "prismtask-app.firebasestorage.app"
    # Bearer token Grafana Cloud's hosted scraper presents on every GET
    # /metrics. Empty default keeps the endpoint inert in dev — the route
    # is registered unconditionally but returns 503 until a token is set.
    METRICS_SCRAPE_TOKEN: str = ""
    ENVIRONMENT: str = "dev"
    # Default to an explicit allowlist. The wildcard is NOT included by
    # default — operators must opt in by setting CORS_ORIGINS="*" in dev.
    # In production the wildcard is stripped even if set; see
    # effective_cors_origins below.
    CORS_ORIGINS: list[str] = [
        "http://localhost:5173",
        "http://localhost:3000",
        "https://web-prismtask-production.up.railway.app",
        "https://app.prismtask.app",
        "https://prism.averykarlin.org",
    ]
    # Emails auto-promoted to admin (is_admin=True) on register / sign-in.
    # Matched case-insensitively. Operators can override via the
    # ADMIN_EMAILS env var (comma-separated).
    ADMIN_EMAILS: list[str] = [
        "avery.karlin@gmail.com",
    ]
    # Phase 1 feature flag for the AI Assistant agentic read-tool loop.
    # When False, chat falls back to today's single-shot Claude call with
    # the curated context bundle + write-chip tools only — exactly the
    # pre-loop behavior. Default off so prod can flip the loop on per env.
    AI_ASSISTANT_TOOL_USE_ENABLED: bool = False

    @field_validator("CORS_ORIGINS", mode="before")
    @classmethod
    def _parse_cors_origins(cls, v: object) -> object:
        if isinstance(v, str):
            return [origin.strip() for origin in v.split(",") if origin.strip()]
        return v

    @field_validator("ADMIN_EMAILS", mode="before")
    @classmethod
    def _parse_admin_emails(cls, v: object) -> object:
        if isinstance(v, str):
            return [email.strip() for email in v.split(",") if email.strip()]
        return v

    def is_admin_email(self, email: str | None) -> bool:
        if not email:
            return False
        normalized = email.strip().lower()
        return any(normalized == allowed.strip().lower() for allowed in self.ADMIN_EMAILS)

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT == "production"

    @property
    def debug(self) -> bool:
        return not self.is_production

    # These origins are always allowed in production regardless of CORS_ORIGINS env var.
    # This prevents an accidental CORS_ORIGINS=* env var from locking out the web app.
    _REQUIRED_PRODUCTION_ORIGINS: list[str] = [
        "https://app.prismtask.app",
        "https://web-prismtask-production.up.railway.app",
        "https://prism.averykarlin.org",
    ]

    @property
    def effective_cors_origins(self) -> list[str]:
        if self.is_production:
            origins = [o for o in self.CORS_ORIGINS if o != "*"]
            for origin in self._REQUIRED_PRODUCTION_ORIGINS:
                if origin not in origins:
                    origins.append(origin)
            return origins
        return self.CORS_ORIGINS

    def get_jwt_secret(self) -> str:
        if not self.JWT_SECRET_KEY:
            raise RuntimeError("JWT_SECRET_KEY must be set")
        return self.JWT_SECRET_KEY

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()
