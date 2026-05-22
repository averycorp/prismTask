import re

with open("backend/tests/test_analytics.py", "r") as f:
    content = f.read()

# remove the import
content = content.replace("from app.services.analytics import determine_trend\n", "")

# add it at the top
content = content.replace("from unittest.mock import MagicMock, patch", "from unittest.mock import MagicMock, patch\nfrom app.services.analytics import determine_trend")

with open("backend/tests/test_analytics.py", "w") as f:
    f.write(content)
