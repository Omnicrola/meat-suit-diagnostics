import html
import json
import re
import sqlite3
import uuid
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient

from app import services
from app.models import Question
from app.security import get_active_api_key, hash_api_key
from tests.conftest import ADMIN_PASSWORD, ADMIN_USERNAME


def csrf_from(response) -> str:
    match = re.search(r'name="csrf_token" value="([^"]+)"', response.text)
    assert match, "no CSRF token on page"
    return match.group(1)


class Admin:
    """Test client that keeps a CSRF token from the last page it loaded."""

    def __init__(self, client: TestClient):
        self.client = client
        self.token = None

    def get(self, url, **kwargs):
        r = self.client.get(url, **kwargs)
        if 'name="csrf_token"' in r.text:
            self.token = csrf_from(r)
        return r

    def post(self, url, data=None, **kwargs):
        if self.token is None:
            self.get("/admin/login")
        data = {"csrf_token": self.token, **(data or {})}
        r = self.client.post(url, data=data, follow_redirects=False, **kwargs)
        if 'name="csrf_token"' in r.text:
            self.token = csrf_from(r)
        return r


@pytest.fixture
def browser(app):
    with TestClient(app) as client:
        yield Admin(client)


@pytest.fixture
def admin(browser):
    r = browser.post("/admin/login", {"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
    assert r.status_code == 303
    browser.get("/admin/questions")
    return browser


# --- authentication -----------------------------------------------------------


@pytest.mark.parametrize("path", ["/admin", "/admin/questions", "/admin/checkins/new", "/admin/api-key", "/admin/backup/download"])
def test_pages_require_login(browser, path):
    r = browser.client.get(path, follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"].startswith("/admin/login?next=")


def test_login_redirects_back_to_requested_page(browser):
    browser.get("/admin/login?next=/admin/checkins")
    r = browser.post("/admin/login", {"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD, "next": "/admin/checkins"})
    assert r.headers["location"] == "/admin/checkins"


@pytest.mark.parametrize("next_url", ["https://evil.example", "//evil.example", "/api/v1/config"])
def test_login_never_redirects_outside_admin(browser, next_url):
    r = browser.post("/admin/login", {"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD, "next": next_url})
    assert r.headers["location"] == "/admin"


@pytest.mark.parametrize("username,password", [(ADMIN_USERNAME, "wrong"), ("someone", ADMIN_PASSWORD), ("", "")])
def test_bad_credentials(browser, username, password):
    r = browser.post("/admin/login", {"username": username, "password": password})
    assert r.status_code == 401
    assert "Wrong username or password" in r.text
    assert browser.client.get("/admin/questions", follow_redirects=False).status_code == 303


def test_login_rate_limited_after_five_failures(browser):
    for _ in range(5):
        assert browser.post("/admin/login", {"username": ADMIN_USERNAME, "password": "wrong"}).status_code == 401
    r = browser.post("/admin/login", {"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD})
    assert r.status_code == 429


def test_login_disabled_without_credentials_configured(app, browser):
    from dataclasses import replace
    browser.get("/admin/login")  # picks up a CSRF token while the form is still shown
    app.state.settings = replace(app.state.settings, admin_password_hash=None)
    r = browser.get("/admin/login")
    assert "isn't configured" in r.text
    assert browser.post("/admin/login", {"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}).status_code == 503


def test_post_without_csrf_token_is_rejected(admin):
    r = admin.client.post("/admin/questions/new", data={"text": "x", "type": "time", "config_json": "{}"})
    assert r.status_code == 403
    r = admin.client.post("/admin/questions/new", data={"csrf_token": "forged", "text": "x", "type": "time", "config_json": "{}"})
    assert r.status_code == 403


def test_logout(admin):
    assert admin.post("/admin/logout").status_code == 303
    assert admin.client.get("/admin/questions", follow_redirects=False).status_code == 303


def test_session_cookie_flags(app, tmp_path):
    from dataclasses import replace
    from app.main import create_app
    secure_app = create_app(replace(app.state.settings, secure_cookies=True))
    with TestClient(secure_app, base_url="https://testserver") as client:
        r = client.get("/admin/login")
    cookie = r.headers["set-cookie"].lower()
    assert "httponly" in cookie and "secure" in cookie and "samesite=strict" in cookie and "path=/admin" in cookie


def test_security_headers(admin):
    r = admin.get("/admin/questions")
    assert "default-src 'self'" in r.headers["content-security-policy"]
    assert r.headers["x-frame-options"] == "DENY"
    assert r.headers["cache-control"] == "no-store"


def test_static_files_served(browser):
    for name in ("admin.css", "admin.js", "question_editor.js"):
        assert browser.client.get(f"/admin/static/{name}").status_code == 200


# --- questions ----------------------------------------------------------------


def create_question(admin, text, qtype, config):
    return admin.post("/admin/questions/new", {"text": text, "type": qtype, "config_json": json.dumps(config)})


def test_create_and_list_question(admin, session):
    r = create_question(admin, "How tired do you feel?", "scale", {"min": 1, "max": 10, "min_label": "Wide awake"})
    assert r.status_code == 303
    page = admin.get("/admin/questions")
    assert "How tired do you feel?" in page.text
    assert "Created question 1." in page.text
    q = session.get(Question, (1, 1))
    assert q.config == {"min": 1, "max": 10, "step": 1, "min_label": "Wide awake", "max_label": None}


def test_create_question_validation_error_keeps_input(admin, session):
    r = create_question(admin, "Blood pressure", "numeric", {"fields": [{"key": "Bad Key", "label": "Systolic"}]})
    assert r.status_code == 400
    assert "should match pattern" in r.text
    assert 'value="Blood pressure"' in r.text
    data = json.loads(html.unescape(re.search(r'id="question-data">(.*?)</script>', r.text).group(1)))
    assert data == {"type": "numeric", "config": {"fields": [{"key": "Bad Key", "label": "Systolic"}]}}
    assert session.scalar(services.current_questions_query()) is None


def test_create_question_with_malformed_json(admin):
    r = admin.post("/admin/questions/new", {"text": "x", "type": "scale", "config_json": "{nope"})
    assert r.status_code == 400


def test_edit_question_creates_new_version(admin, session):
    create_question(admin, "Tired?", "scale", {"min": 1, "max": 10})
    page = admin.get("/admin/questions/1")
    assert "Save as version 2" in page.text

    r = admin.post("/admin/questions/1", {"text": "How tired?", "config_json": json.dumps({"min": 1, "max": 5})})
    assert r.status_code == 303
    assert "Saved question 1 as version 2." in admin.get("/admin/questions").text
    session.expire_all()
    assert services.get_current_question(session, 1).text == "How tired?"
    assert session.get(Question, (1, 1)).text == "Tired?"
    assert "Version history" in admin.get("/admin/questions/1").text


def test_edit_without_changes_does_not_create_version(admin, session):
    create_question(admin, "Tired?", "scale", {"min": 1, "max": 10})
    admin.post("/admin/questions/1", {"text": "Tired?", "config_json": json.dumps({"min": 1, "max": 10})})
    assert "No changes to save." in admin.get("/admin/questions").text
    assert services.get_current_question(session, 1).version == 1


def test_edit_ignores_attempted_type_change(admin, session):
    create_question(admin, "Tired?", "scale", {"min": 1, "max": 10})
    r = admin.post("/admin/questions/1", {"text": "Tired?", "type": "boolean", "config_json": "{}"})
    assert r.status_code == 400  # {} is not a valid scale config; the type stays "scale"
    assert services.get_current_question(session, 1).type == "scale"


def test_delete_question(admin, session):
    create_question(admin, "Tired?", "scale", {"min": 1, "max": 10})
    assert admin.post("/admin/questions/1/delete").status_code == 303
    assert "Tired?" not in admin.get("/admin/questions").text
    assert "Tired?" in admin.get("/admin/questions?deleted=true").text
    page = admin.get("/admin/questions/1")
    assert "was deleted" in page.text and "Save as version" not in page.text
    assert admin.post("/admin/questions/1/delete").status_code == 404


def test_unknown_question_404(admin):
    assert admin.get("/admin/questions/99").status_code == 404


# --- check-ins ----------------------------------------------------------------


@pytest.fixture
def two_questions(session):
    a = services.create_question(session, "Tired?", "scale", {"min": 1, "max": 10})
    b = services.create_question(session, "Headache?", "boolean", {})
    return a, b


def checkin_form(**overrides):
    form = {"name": "Evening", "time_local": "21:00", "days_of_week": ["1", "3", "5"],
            "question_ids": ["2", "1"], "expires_after_minutes": "90", "enabled": "on"}
    return form | overrides


def test_create_checkin(admin, session, two_questions):
    r = admin.post("/admin/checkins/new", checkin_form())
    assert r.status_code == 303
    page = admin.get("/admin/checkins")
    assert "Evening" in page.text and "Mon, Wed, Fri" in page.text
    checkin = session.scalar(services.active_checkins_query())
    assert [m.question_id for m in checkin.questions] == [2, 1]
    assert checkin.expires_after_minutes == 90


def test_checkin_validation_error_keeps_input(admin, two_questions):
    r = admin.post("/admin/checkins/new", checkin_form(days_of_week=[], name="Lunch"))
    assert r.status_code == 400
    assert 'value="Lunch"' in r.text
    assert "days_of_week" in r.text


def test_checkin_with_deleted_question_rejected(admin, session, two_questions):
    services.delete_question(session, 1)
    r = admin.post("/admin/checkins/new", checkin_form())
    assert r.status_code == 400
    assert "question 1 not found" in r.text


def test_edit_and_disable_checkin(admin, session, two_questions):
    admin.post("/admin/checkins/new", checkin_form())
    page = admin.get("/admin/checkins/1")
    assert page.text.index("Headache?") < page.text.index("Tired?")  # rendered in saved order

    form = checkin_form(name="Night", question_ids=["1"])
    del form["enabled"]
    assert admin.post("/admin/checkins/1", form).status_code == 303
    session.expire_all()
    assert session.scalar(services.active_checkins_query()) is None  # disabled
    assert "disabled" in admin.get("/admin/checkins").text


def test_delete_checkin(admin, two_questions):
    admin.post("/admin/checkins/new", checkin_form())
    assert admin.post("/admin/checkins/1/delete").status_code == 303
    assert "Evening" not in admin.get("/admin/checkins").text.split("</h1>")[1]
    assert admin.get("/admin/checkins/1").status_code == 404


# --- API key ------------------------------------------------------------------


def test_generate_key_shows_qr_once(admin, session, anon_client):
    page = admin.get("/admin/api-key")
    assert "no active key" in page.text

    r = admin.post("/admin/api-key/generate")
    assert r.status_code == 200
    assert "<svg" in r.text
    key = re.search(r'<code class="secret">(msd_[^<]+)</code>', r.text).group(1)
    assert get_active_api_key(session).key_hash == hash_api_key(key)
    assert anon_client.get("/api/v1/config", headers={"X-API-Key": key}).status_code == 200

    # Not shown again.
    assert key not in admin.get("/admin/api-key").text


def test_regenerate_revokes_previous_key(admin, anon_client):
    first = re.search(r"(msd_[^<]+)</code>", admin.post("/admin/api-key/generate").text).group(1)
    admin.get("/admin/api-key")
    second = re.search(r"(msd_[^<]+)</code>", admin.post("/admin/api-key/generate").text).group(1)
    assert anon_client.get("/api/v1/config", headers={"X-API-Key": first}).status_code == 401
    assert anon_client.get("/api/v1/config", headers={"X-API-Key": second}).status_code == 200


def test_revoke_key(admin, session, api_key, anon_client):
    assert admin.post("/admin/api-key/revoke").status_code == 303
    assert get_active_api_key(session) is None
    assert anon_client.get("/api/v1/config", headers={"X-API-Key": api_key}).status_code == 401


def test_setup_uri_format():
    from app.admin.system import setup_uri
    uri = setup_uri("https://msd.up.railway.app", "msd_abc-_123")
    parsed = urlparse(uri)
    assert parsed.scheme == "meatsuit" and parsed.netloc == "setup"
    assert parse_qs(parsed.query) == {"url": ["https://msd.up.railway.app"], "key": ["msd_abc-_123"]}


def test_warns_when_server_url_is_not_https(admin):
    assert "isn't HTTPS" in admin.post("/admin/api-key/generate").text


# --- backup -------------------------------------------------------------------


def test_backup_download_is_a_complete_database(admin, session, two_questions, client, tmp_path):
    client.post("/api/v1/responses", json={"responses": [{
        "id": str(uuid.uuid4()), "question_id": 1, "question_version": 1, "status": "answered",
        "value": 4, "answered_at": "2026-10-03T08:00:00Z",
    }]})
    assert "Answers</dt><dd>1" in admin.get("/admin/backup").text

    r = admin.get("/admin/backup/download")
    assert r.status_code == 200
    assert re.match(r'attachment; filename="meatsuit-\d{8}-\d{6}\.db"', r.headers["content-disposition"])
    path = tmp_path / "backup.db"
    path.write_bytes(r.content)
    with sqlite3.connect(path) as db:
        assert db.execute("select count(*) from responses").fetchone() == (1,)
        assert db.execute("select count(*) from questions").fetchone() == (2,)
