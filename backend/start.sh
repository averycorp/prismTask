#!/bin/bash
# Fail fast: if `alembic upgrade head` or any other setup step errors,
# do NOT launch gunicorn with a stale schema. A successful boot must
# imply the deployed code matches the live database.
set -euo pipefail

if [ -f /app/alembic.ini ]; then
  cd /app
elif [ -f /app/backend/alembic.ini ]; then
  cd /app/backend
fi
alembic upgrade head
exec gunicorn app.main:app -c gunicorn.conf.py
