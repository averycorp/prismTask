"""Background-task package for FastAPI cron/APScheduler jobs.

Each module owns its scheduler registration; ``main.py`` calls each
``start_scheduler()`` / ``stop_scheduler()`` on startup / shutdown.
"""
