import pytest
from fastapi.testclient import TestClient

from app.admin.auth import hash_password
from app.db import Base
from app.main import create_app
from app.security import issue_api_key
from app.settings import Settings


ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = "correct horse battery staple"
ADMIN_PASSWORD_HASH = hash_password(ADMIN_PASSWORD)


@pytest.fixture
def app(tmp_path):
    app = create_app(Settings(
        database_path=str(tmp_path / "test.db"),
        admin_username=ADMIN_USERNAME,
        admin_password_hash=ADMIN_PASSWORD_HASH,
        session_secret="test-secret",
        secure_cookies=False,  # the test client talks plain HTTP
    ))
    Base.metadata.create_all(app.state.engine)
    yield app
    app.state.engine.dispose()


@pytest.fixture
def session(app):
    with app.state.sessionmaker() as session:
        yield session


@pytest.fixture
def api_key(session):
    raw, _ = issue_api_key(session)
    return raw


@pytest.fixture
def client(app, api_key):
    with TestClient(app, headers={"X-API-Key": api_key}) as client:
        yield client


@pytest.fixture
def anon_client(app):
    with TestClient(app) as client:
        yield client
