from app.security import issue_api_key, revoke_api_keys


def test_healthz_needs_no_key(anon_client):
    r = anon_client.get("/healthz")
    assert r.status_code == 200
    assert r.text == "ok"


def test_missing_key_is_rejected(anon_client, api_key):
    assert anon_client.get("/api/v1/config").status_code == 401


def test_wrong_key_is_rejected(anon_client, api_key):
    r = anon_client.get("/api/v1/config", headers={"X-API-Key": api_key + "x"})
    assert r.status_code == 401


def test_valid_key_is_accepted_and_last_used_recorded(client, session):
    assert client.get("/api/v1/config").status_code == 200
    from app.security import get_active_api_key
    session.expire_all()
    assert get_active_api_key(session).last_used_at is not None


def test_regenerating_revokes_old_key(client, anon_client, session, api_key):
    new_key, _ = issue_api_key(session)
    assert anon_client.get("/api/v1/config", headers={"X-API-Key": api_key}).status_code == 401
    assert anon_client.get("/api/v1/config", headers={"X-API-Key": new_key}).status_code == 200


def test_revoked_key_is_rejected(client, session):
    revoke_api_keys(session)
    assert client.get("/api/v1/config").status_code == 401


def test_key_format(api_key):
    assert api_key.startswith("msd_")
    assert len(api_key) > 40


def test_docs_disabled_by_default(anon_client):
    assert anon_client.get("/docs").status_code == 404
    assert anon_client.get("/openapi.json").status_code == 404
