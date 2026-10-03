import csv
import io
import uuid

import pytest

from app import services
from app.schemas import MAX_UPLOAD_BATCH


@pytest.fixture
def tired(session):
    return services.create_question(session, "How tired do you feel?", "scale", {"min": 1, "max": 10})


@pytest.fixture
def bp(session):
    return services.create_question(session, "Blood pressure and heart rate?", "numeric", {"fields": [
        {"key": "systolic", "label": "Systolic", "min": 50, "max": 250},
        {"key": "diastolic", "label": "Diastolic", "min": 30, "max": 150},
        {"key": "hr", "label": "Heart rate", "min": 20, "max": 250},
    ]})


def make(question, value=7, status="answered", answered_at="2026-10-03T01:04:12Z", **extra):
    return {
        "id": str(uuid.uuid4()),
        "question_id": question.id,
        "question_version": question.version,
        "status": status,
        "value": value,
        "answered_at": answered_at,
        **extra,
    }


def upload(client, *responses):
    r = client.post("/api/v1/responses", json={"responses": list(responses)})
    assert r.status_code == 200, r.text
    return r.json()


def fetch(client, **params):
    params = {"from": "2026-01-01T00:00:00Z", "to": "2027-01-01T00:00:00Z"} | params
    r = client.get("/api/v1/responses", params=params)
    assert r.status_code == 200, r.text
    return r


def test_upload_and_fetch_round_trip(client, session, tired, bp):
    checkin = services.create_checkin(session, "Evening", "21:00", [1], [tired.id, bp.id])
    instance = str(uuid.uuid4())
    a = make(tired, 7, checkin_id=checkin.id, instance_id=instance, scheduled_for="2026-10-03T01:00:00Z")
    b = make(bp, {"systolic": 121, "diastolic": 79, "hr": 64}, checkin_id=checkin.id, instance_id=instance,
             answered_at="2026-10-03T01:05:00Z")

    result = upload(client, a, b)
    assert result == {"accepted": [a["id"], b["id"]], "duplicates": [], "rejected": []}

    rows = fetch(client).json()["responses"]
    assert [r["id"] for r in rows] == [a["id"], b["id"]]
    first = rows[0]
    assert first["value"] == 7
    assert first["question_version"] == 1
    assert first["instance_id"] == instance
    assert first["scheduled_for"] == "2026-10-03T01:00:00.000Z"
    assert first["answered_at"] == "2026-10-03T01:04:12.000Z"
    assert first["received_at"].endswith("Z")


def test_non_utc_offsets_are_converted_to_utc(client, tired):
    upload(client, make(tired, answered_at="2026-10-02T21:04:12.5-04:00"))
    assert fetch(client).json()["responses"][0]["answered_at"] == "2026-10-03T01:04:12.500Z"


def test_reupload_is_idempotent(client, tired):
    a = make(tired)
    assert upload(client, a)["accepted"] == [a["id"]]
    assert upload(client, a) == {"accepted": [], "duplicates": [a["id"]], "rejected": []}
    assert len(fetch(client).json()["responses"]) == 1


def test_duplicate_within_one_batch(client, tired):
    a = make(tired)
    assert upload(client, a, a) == {"accepted": [a["id"]], "duplicates": [a["id"]], "rejected": []}


def test_bad_items_rejected_individually(client, tired):
    good = make(tired, 5)
    out_of_range = make(tired, 11)
    unknown_version = make(tired) | {"question_version": 2}
    malformed = {"id": "not-a-uuid", "question_id": 1}

    result = upload(client, good, out_of_range, unknown_version, malformed)
    assert result["accepted"] == [good["id"]]
    rejected = {r["id"]: r["error"] for r in result["rejected"]}
    assert "between 1 and 10" in rejected[out_of_range["id"]]
    assert "unknown question 1 version 2" in rejected[unknown_version["id"]]
    assert "not-a-uuid" in rejected


@pytest.mark.parametrize("change", [
    {"status": "skipped"},                       # skipped must not have a value
    {"value": None},                             # answered needs a value
    {"status": "bogus"},
    {"answered_at": "2026-10-03T01:04:12"},      # no offset
    {"checkin_id": 99},
    {"unexpected": True},
])
def test_rejected_variants(client, tired, change):
    result = upload(client, make(tired) | change)
    assert result["accepted"] == []
    assert len(result["rejected"]) == 1


def test_skipped_and_missed_have_null_value(client, tired):
    upload(client, make(tired, None, "skipped"), make(tired, None, "missed"))
    rows = fetch(client).json()["responses"]
    assert sorted(r["status"] for r in rows) == ["missed", "skipped"]
    assert all(r["value"] is None for r in rows)


def test_answers_to_old_and_deleted_versions_are_accepted(client, session, tired):
    v1 = make(tired, 9)  # answered against version 1, range 1-10
    services.update_question(session, tired.id, "Tired?", "scale", {"min": 1, "max": 5})
    services.delete_question(session, tired.id)

    result = upload(client, v1)
    assert result["accepted"] == [v1["id"]]

    # Validated against version 1's config, not the latest one.
    v2_too_high = make(tired, 9) | {"question_version": 2}
    assert upload(client, v2_too_high)["rejected"][0]["id"] == v2_too_high["id"]


def test_batch_size_limit(client, tired):
    r = client.post("/api/v1/responses", json={"responses": [make(tired)] * (MAX_UPLOAD_BATCH + 1)})
    assert r.status_code == 422


def test_date_range_is_inclusive_exclusive_and_sorted(client, tired):
    early = make(tired, 1, answered_at="2026-10-01T00:00:00Z")
    middle = make(tired, 2, answered_at="2026-10-01T12:00:00Z")
    late = make(tired, 3, answered_at="2026-10-02T00:00:00Z")
    upload(client, late, early, middle)

    rows = fetch(client, **{"from": "2026-10-01T00:00:00Z", "to": "2026-10-02T00:00:00Z"}).json()["responses"]
    assert [r["value"] for r in rows] == [1, 2]


def test_filter_by_question(client, tired, bp):
    upload(client, make(tired), make(bp, {"systolic": 120, "diastolic": 80, "hr": 60}))
    rows = fetch(client, question_id=bp.id).json()["responses"]
    assert [r["question_id"] for r in rows] == [bp.id]


def test_invalid_range_params(client):
    assert client.get("/api/v1/responses", params={"from": "2026-10-02T00:00:00Z", "to": "2026-10-01T00:00:00Z"}).status_code == 422
    assert client.get("/api/v1/responses", params={"from": "2026-10-01T00:00:00", "to": "2026-10-02T00:00:00Z"}).status_code == 422
    assert client.get("/api/v1/responses", params={"to": "2026-10-02T00:00:00Z"}).status_code == 422


def test_csv_export(client, session, tired, bp):
    bed = services.create_question(session, "When did you go to bed?", "time", {})
    upload(
        client,
        make(tired, 7, answered_at="2026-10-01T08:00:00Z"),
        make(bp, {"systolic": 120, "diastolic": 80, "hr": 60}, answered_at="2026-10-01T09:00:00Z"),
        make(tired, None, "skipped", answered_at="2026-10-01T10:00:00Z"),
        make(bed, "23:15", answered_at="2026-10-01T11:00:00Z"),
    )
    r = fetch(client, format="csv")
    assert r.headers["content-type"].startswith("text/csv")
    assert 'filename="responses-20260101-20270101.csv"' in r.headers["content-disposition"]

    rows = list(csv.DictReader(io.StringIO(r.text)))
    assert [row["question_text"] for row in rows] == [tired.text, bp.text, tired.text, bed.text]
    assert rows[0]["value"] == "7"
    assert rows[1]["value"] == '{"systolic": 120, "diastolic": 80, "hr": 60}'
    assert rows[2]["value"] == ""
    assert rows[2]["status"] == "skipped"
    assert rows[3]["value"] == "23:15"
