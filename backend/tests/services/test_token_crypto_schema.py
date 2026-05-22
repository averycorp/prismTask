import pytest
from app.services.integrations.token_crypto import OAuthTokenPayload
from pydantic import ValidationError

def test_oauth_token_payload_schema():
    payload = OAuthTokenPayload.model_validate_json('{"access_token": "token", "refresh_token": "refresh", "unknown_field": "val"}')
    dump = payload.model_dump(exclude_unset=True)
    assert dump == {'access_token': 'token', 'refresh_token': 'refresh', 'unknown_field': 'val'}

def test_oauth_token_payload_schema_empty():
    payload = OAuthTokenPayload.model_validate_json('{}')
    dump = payload.model_dump(exclude_unset=True)
    assert dump == {}
