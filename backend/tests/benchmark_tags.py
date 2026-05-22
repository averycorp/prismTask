import asyncio
import time
from httpx import AsyncClient
from app.main import app
from app.database import get_db, async_session_maker
from app.models import User, Goal, Project, Task, Tag, TaskTag
import pytest

# A script to benchmark the set_task_tags endpoint
