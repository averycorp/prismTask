import importlib.util
import os
import sqlalchemy as sa
from alembic.runtime.migration import MigrationContext
from alembic.operations import Operations
import alembic

def test_migration_030_idempotency():
    # 1. Create a clean in-memory SQLite database
    engine = sa.create_engine("sqlite:///:memory:")
    
    # 2. Create the target table 'chat_messages' without the 'tool_calls' column
    with engine.begin() as conn:
        conn.execute(sa.text("CREATE TABLE chat_messages (id VARCHAR(64) PRIMARY KEY)"))
        
    # 3. Load the migration module dynamically
    migration_path = os.path.join(
        os.path.dirname(__file__),
        "../alembic/versions/030_add_chat_messages_tool_calls.py"
    )
    spec = importlib.util.spec_from_file_location("migration_030", migration_path)
    migration_030 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration_030)
    
    # 4. Bind alembic.op to our Operations instance and run upgrade()
    with engine.begin() as conn:
        ctx = MigrationContext.configure(conn)
        op_instance = Operations(ctx)
        
        # Set the global proxy so that `from alembic import op` works
        alembic.op._proxy = op_instance
        
        # Run upgrade for the first time: it should add the column
        migration_030.upgrade()
        
        # Verify the column exists now
        inspector = sa.inspect(conn)
        columns = [col["name"] for col in inspector.get_columns("chat_messages")]
        assert "tool_calls" in columns
        
        # Run upgrade a second time: it should be idempotent and NOT crash/fail
        migration_030.upgrade()
        
        # Verify it is still there
        columns = [col["name"] for col in inspector.get_columns("chat_messages")]
        assert "tool_calls" in columns

    # 5. Run downgrade() to verify it works
    with engine.begin() as conn:
        ctx = MigrationContext.configure(conn)
        op_instance = Operations(ctx)
        alembic.op._proxy = op_instance
        
        migration_030.downgrade()
        
        # Verify the column is gone
        inspector = sa.inspect(conn)
        columns = [col["name"] for col in inspector.get_columns("chat_messages")]
        assert "tool_calls" not in columns
        
        # Run downgrade a second time: it should be idempotent and NOT crash/fail
        migration_030.downgrade()
        
        columns = [col["name"] for col in inspector.get_columns("chat_messages")]
        assert "tool_calls" not in columns
