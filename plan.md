1. **Understand CI Failure**
   - The CI is failing during `ruff check app/ tests/`.
   - The errors output by `ruff` are unused imports in tests:
     - `pytest` in `backend/tests/services/test_token_crypto_schema.py:1`
     - `pydantic.ValidationError` in `backend/tests/services/test_token_crypto_schema.py:3`
     - `pytest` in `backend/tests/test_migration_030.py:3` (Wait, this wasn't created by us, but let's fix it anyway or wait, maybe we can run `ruff check --fix app/ tests/` or manually remove them).

2. **Fix `backend/tests/services/test_token_crypto_schema.py`**
   - Remove the unused `import pytest` and `from pydantic import ValidationError`.

3. **Fix `backend/tests/test_migration_030.py`**
   - Remove `import pytest` on line 3.

4. **Verify the change**
   - Run `ruff check app/ tests/` from the `backend/` directory to ensure there are no more errors.

5. **Submit PR**
   - Submit the changes using the existing branch name `fix-unsafe-token-deserialization-3532161504853716723`.
