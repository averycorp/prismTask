import pytest
from app.services.integrations.token_crypto import encrypt_json, decrypt_json

def test_encrypt_decrypt():
    # Model allows extra fields now
    data = {"access_token": "abc", "refresh_token": "def", "hello": "world", "num": 123}
    encrypted = encrypt_json(data)
    assert isinstance(encrypted, str)
    decrypted = decrypt_json(encrypted)
    assert decrypted["access_token"] == "abc"
    assert decrypted["refresh_token"] == "def"
    assert decrypted["hello"] == "world"

def test_invalid_json():
    from cryptography.fernet import Fernet
    from app.services.integrations.token_crypto import _fernet
    f = _fernet()
    enc = f.encrypt(b"invalid json").decode("utf-8")
    with pytest.raises(ValueError, match="payload is invalid"):
        decrypt_json(enc)
