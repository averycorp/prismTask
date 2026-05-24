import asyncio
from datetime import date
from app.services.ai_productivity import generate_daily_briefing

result = generate_daily_briefing(
    today=date.today(),
    overdue_tasks=[],
    today_tasks=[],
    planned_tasks=[],
    habits=[{"name": "Exercise", "frequency": "daily"}, {"name": "Meditate", "frequency": "daily"}],
    completed_tasks=[]
)
print(result)
