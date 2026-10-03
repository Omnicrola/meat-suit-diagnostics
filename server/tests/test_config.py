import pytest

from app import services
from app.models import Question


@pytest.fixture
def tired(session):
    return services.create_question(session, "How tired do you feel?", "scale", {"min": 1, "max": 10})


@pytest.fixture
def headache(session):
    return services.create_question(session, "Do you have a headache?", "boolean", {})


def test_empty_config(client):
    r = client.get("/api/v1/config")
    assert r.status_code == 200
    assert r.json() == {"version": 0, "questions": [], "checkins": []}


def test_config_lists_questions_and_checkins(client, session, tired, headache):
    services.create_checkin(session, "Evening", "21:00", [7, 1, 2], [headache.id, tired.id])
    body = client.get("/api/v1/config").json()

    assert [(q["id"], q["version"], q["type"]) for q in body["questions"]] == [(1, 1, "scale"), (2, 1, "boolean")]
    assert body["questions"][0]["config"]["max"] == 10
    assert body["checkins"] == [{
        "id": 1, "name": "Evening", "time_local": "21:00", "days_of_week": [1, 2, 7],
        "expires_after_minutes": 120, "question_ids": [2, 1],
    }]


def test_editing_creates_new_version_and_config_shows_only_latest(client, session, tired):
    services.update_question(session, tired.id, "How tired are you?", {"min": 0, "max": 5})
    body = client.get("/api/v1/config").json()

    assert len(body["questions"]) == 1
    assert body["questions"][0]["version"] == 2
    assert body["questions"][0]["text"] == "How tired are you?"
    # The original row is untouched.
    v1 = session.get(Question, (tired.id, 1))
    assert v1.text == "How tired do you feel?"
    assert v1.config["max"] == 10


def test_deleted_question_is_hidden_and_removed_from_checkins(client, session, tired, headache):
    services.create_checkin(session, "Evening", "21:00", [1], [tired.id, headache.id])
    services.delete_question(session, tired.id)
    body = client.get("/api/v1/config").json()

    assert [q["id"] for q in body["questions"]] == [headache.id]
    assert body["checkins"][0]["question_ids"] == [headache.id]
    assert session.get(Question, (tired.id, 2)).deleted is True


def test_deleted_question_cannot_be_edited_or_added(session, tired):
    services.delete_question(session, tired.id)
    with pytest.raises(services.NotFoundError):
        services.update_question(session, tired.id, "x", {})
    with pytest.raises(services.NotFoundError):
        services.create_checkin(session, "Morning", "08:00", [1], [tired.id])


def test_question_ids_are_not_reused_after_delete(session, tired, headache):
    services.delete_question(session, headache.id)
    assert services.create_question(session, "New", "boolean", {}).id == 3


def test_checkin_with_no_live_questions_is_omitted(client, session, tired):
    services.create_checkin(session, "Evening", "21:00", [1], [tired.id])
    services.delete_question(session, tired.id)
    assert client.get("/api/v1/config").json()["checkins"] == []


def test_disabled_and_deleted_checkins_are_omitted(client, session, tired):
    services.create_checkin(session, "Disabled", "08:00", [1], [tired.id], enabled=False)
    c = services.create_checkin(session, "Deleted", "09:00", [1], [tired.id])
    services.delete_checkin(session, c.id)
    assert client.get("/api/v1/config").json()["checkins"] == []


def test_update_checkin_replaces_question_order(client, session, tired, headache):
    c = services.create_checkin(session, "Evening", "21:00", [1], [tired.id, headache.id])
    services.update_checkin(session, c.id, "Night", "22:30", [6, 7], [headache.id, tired.id], 60, True)
    checkin = client.get("/api/v1/config").json()["checkins"][0]
    assert checkin["name"] == "Night"
    assert checkin["question_ids"] == [headache.id, tired.id]
    assert checkin["expires_after_minutes"] == 60


@pytest.mark.parametrize("kwargs", [
    {"time_local": "25:00"},
    {"days_of_week": [0]},
    {"days_of_week": [1, 1]},
    {"days_of_week": []},
    {"name": "  "},
    {"question_ids": []},
    {"expires_after_minutes": 0},
])
def test_invalid_checkin_rejected(session, tired, kwargs):
    args = {"name": "Evening", "time_local": "21:00", "days_of_week": [1], "question_ids": [tired.id]} | kwargs
    with pytest.raises(ValueError):
        services.create_checkin(session, **args)


def test_etag_returns_304_until_config_changes(client, session, tired):
    first = client.get("/api/v1/config")
    etag = first.headers["etag"]

    assert client.get("/api/v1/config", headers={"If-None-Match": etag}).status_code == 304

    services.create_question(session, "New", "boolean", {})
    second = client.get("/api/v1/config", headers={"If-None-Match": etag})
    assert second.status_code == 200
    assert second.headers["etag"] != etag
    assert second.json()["version"] == first.json()["version"] + 1


def test_question_versions_endpoint(client, session, tired):
    services.update_question(session, tired.id, "v2", {"min": 1, "max": 5})
    services.delete_question(session, tired.id)
    versions = client.get(f"/api/v1/questions/{tired.id}/versions").json()

    assert [(v["version"], v["text"], v["deleted"]) for v in versions] == [
        (1, "How tired do you feel?", False), (2, "v2", False), (3, "v2", True),
    ]
    assert versions[0]["created_at"].endswith("Z")
    assert client.get("/api/v1/questions/99/versions").status_code == 404
