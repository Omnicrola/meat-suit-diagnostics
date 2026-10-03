# Meat Suit Diagnostics — Design

A single-user system for recording health check-ins on an Android phone (Samsung Galaxy A14), backed by a small API on Railway.

## 1. Components

| Component | Tech | Purpose |
|---|---|---|
| Android app | Kotlin, Jetpack Compose, Room, DataStore, Retrofit/OkHttp, kotlinx.serialization, WorkManager, AlarmManager | Fetches config, fires check-in notifications, collects answers, queues answers offline and syncs them |
| API server | Python 3.13, FastAPI, SQLAlchemy 2 + Alembic, SQLite (WAL mode), Uvicorn (single worker) | JSON API for the phone |
| Admin web app | Jinja2 templates + HTMX, served by the same FastAPI process | Manage questions, check-ins and the API key; download backups |
| Hosting | Railway service + Railway Volume mounted at `/data` | HTTPS is provided by Railway; the DB file lives on the volume |

Repository layout (monorepo):

```
HealthTracker/   (repo folder; product name is "Meat Suit Diagnostics")
  docs/DESIGN.md
  server/      FastAPI app, admin UI, Alembic migrations, tests
  android/     Gradle project, single :app module
```

## 2. Core concepts

- **Question**: a single prompt with a type and type-specific config, identified by `(id, version)`. Editing a question never modifies a row; it inserts a new row with the same `id` and `version + 1`. The highest version is the **current** one and all earlier versions are hidden by default. Deleting a question inserts a new version marked `deleted`. Nothing is ever hard-deleted.
- **Check-in**: a named group of ordered questions, asked at a fixed local time on chosen days of the week (e.g. "Evening", 21:00, Mon–Sun).
- **Check-in instance**: one occurrence of a check-in on the phone (e.g. "Evening on 2026-10-03"). Exists only on the phone.
- **Response**: one answer to one question. It is either part of a check-in instance or ad hoc ("answer now").

### Time rules
- All timestamps are stored and sent as **UTC** (ISO 8601 with a `Z` suffix).
- A check-in's schedule time is a **local wall-clock time** (`"21:00"`). The phone works out when that is in whatever timezone it is currently in.
- Answers to `time` questions ("When did you go to bed?") are wall-clock strings (`"23:15"`), not timestamps. They are never converted.

## 3. Question types

`config` is JSON. `value` is the JSON stored in a response.

| type | config | value example |
|---|---|---|
| `scale` | `{"min":1,"max":10,"step":1,"min_label":"Wide awake","max_label":"Exhausted"}` | `7` |
| `boolean` | `{"true_label":"Yes","false_label":"No"}` | `true` |
| `text` | `{"multiline":true,"max_length":1000}` | `"Chicken salad"` |
| `time` | `{}` | `"23:15"` |
| `numeric` | `{"fields":[{"key":"systolic","label":"Systolic","unit":"mmHg","min":50,"max":250,"decimals":0},{"key":"diastolic",...},{"key":"hr","label":"Heart rate","unit":"bpm",...}]}` | `{"systolic":121,"diastolic":79,"hr":64}` |
| `single_select` | `{"options":[{"key":"am","label":"Morning"},...]}` | `"am"` |
| `multi_select` | `{"options":[...],"min":0,"max":null}` | `["nausea","dizzy"]` |

`decimals` sets how many decimal places a numeric field allows (0 means integers only).

Every response has a `status`:
- `answered`: `value` is set.
- `skipped`: the user deliberately skipped the question; `value` is null.
- `missed`: the check-in expired without being answered; `value` is null. This makes missed check-ins visible in the data.

## 4. Database schema (SQLite)

```sql
questions (
  id            INTEGER NOT NULL,       -- stable question id, shared by all versions
  version       INTEGER NOT NULL,       -- 1, 2, 3...; highest = current
  text          TEXT NOT NULL,
  type          TEXT NOT NULL,
  config        TEXT NOT NULL,          -- JSON
  deleted       INTEGER NOT NULL DEFAULT 0,  -- 1 on the version that deleted the question
  created_at    TEXT NOT NULL,          -- UTC; when this version was created
  PRIMARY KEY (id, version)
)
-- New question ids come from MAX(id) + 1.

checkins (
  id                    INTEGER PRIMARY KEY,
  name                  TEXT NOT NULL,
  time_local            TEXT NOT NULL,  -- "HH:MM"
  days_of_week          TEXT NOT NULL,  -- JSON, ISO days [1..7], 1 = Monday
  expires_after_minutes INTEGER NOT NULL DEFAULT 120,
  enabled               INTEGER NOT NULL DEFAULT 1,
  created_at, updated_at, deleted_at
)

checkin_questions (
  checkin_id   INTEGER REFERENCES checkins(id),
  question_id  INTEGER REFERENCES questions(id),
  position     INTEGER NOT NULL,
  PRIMARY KEY (checkin_id, question_id)
)

responses (
  id                 TEXT PRIMARY KEY,   -- UUID generated on the phone (makes uploads idempotent)
  question_id        INTEGER NOT NULL,
  question_version   INTEGER NOT NULL,   -- (question_id, question_version) -> questions
  checkin_id         INTEGER REFERENCES checkins(id),  -- NULL = ad hoc
  instance_id        TEXT,               -- UUID of the check-in instance; groups answers
  status             TEXT NOT NULL,      -- answered | skipped | missed
  value              TEXT,               -- JSON
  scheduled_for      TEXT,               -- UTC, NULL for ad hoc
  answered_at        TEXT NOT NULL,      -- UTC (for missed: the time it expired)
  received_at        TEXT NOT NULL       -- UTC, set by the server
)
CREATE INDEX ix_responses_answered_at ON responses(answered_at);

api_keys (
  id            INTEGER PRIMARY KEY,
  key_hash      TEXT NOT NULL UNIQUE,    -- SHA-256 of the key
  prefix        TEXT NOT NULL,           -- first 8 chars, for display
  created_at    TEXT NOT NULL,
  revoked_at    TEXT,
  last_used_at  TEXT
)

meta (
  key    TEXT PRIMARY KEY,
  value  TEXT
)  -- holds config_version, which is incremented on every question/check-in change
```

Each response points to the exact `(question_id, question_version)` that was shown. Old versions are never modified, so editing a question's wording or range later never changes what an old answer meant. `checkin_questions` references only `question_id`, so a check-in always asks the current version. Deleted questions are left out of `/config`.

## 5. Authentication

### Phone → API
- The `X-API-Key` header is required on every `/api/*` route; a missing or bad key gets `401`.
- Key format: `msd_` + 32 random bytes, base64url-encoded. Only the SHA-256 hash is stored. A plain hash is enough because the key is high-entropy, unlike a password.
- At most one active key at a time. Generating a new key revokes the old one.
- The only unauthenticated route is `GET /healthz`. It returns `ok` and nothing else, and exists for Railway's health check.

### Admin web app
- Single admin account. The username and an Argon2 password hash come from env vars (`ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH`).
- Login is a form that sets a signed session cookie (`HttpOnly`, `Secure`, `SameSite=Strict`), with a 12-hour session lifetime.
- Login attempts are rate-limited (e.g. 5 per minute per IP).
- POST forms carry a CSRF token.

### Provisioning the phone
1. In the admin app, click **Generate API key**.
2. The page shows the key **once**, as text and as a QR code. The QR code encodes `meatsuit://setup?url=https%3A%2F%2F<host>&key=msd_...`.
3. The phone scans it with Google Code Scanner (`play-services-code-scanner`). That scanner needs no camera permission in the app.
4. The admin app's key page shows the active key's prefix, when it was created, when it was last used, and a **Revoke** button.

## 6. API (`/api/v1`, JSON)

### `GET /config`
Returns all active questions and enabled check-ins. Supports `ETag`/`If-None-Match` (the ETag is `config_version`), so it returns `304` when nothing has changed.
```json
{
  "version": 14,
  "questions": [{"id":1,"version":3,"text":"How tired do you feel?","type":"scale","config":{...}}],
  "checkins":  [{"id":1,"name":"Evening","time_local":"21:00","days_of_week":[1,2,3,4,5,6,7],
                 "expires_after_minutes":120,"question_ids":[1,2,4]}]
}
```

### `POST /responses`
Batch upload, up to 500 responses per request. Idempotent on `id`: a response the server already has is acknowledged without being stored again.
```json
{"responses":[{"id":"uuid","question_id":1,"question_version":3,"checkin_id":1,"instance_id":"uuid",
  "status":"answered","value":7,
  "scheduled_for":"2026-10-03T01:00:00Z","answered_at":"2026-10-03T01:04:12Z"}]}
```
Response: `{"accepted":["uuid",...],"duplicates":["uuid",...],"rejected":[{"id":"uuid","error":"..."}]}`.
The server validates each `value` against the type and config of the referenced question version. Answers to an older version, or to a version that was deleted after the phone cached it, are still accepted, because the phone may have been offline when the question changed.

### `GET /questions/{id}/versions`
Returns every version of a question, oldest first, so you can interpret responses to older versions.

### `GET /responses?from=<UTC>&to=<UTC>[&question_id=][&format=json|csv]`
Filters on `answered_at` (`from` inclusive, `to` exclusive). Sorted ascending. CSV puts `value` as a JSON string in a single column.

## 7. Admin web app (`/admin`)

| Page | Function |
|---|---|
| Login / logout | |
| Questions | List, create, edit, soft-delete. Type-specific config form with a live preview of the question |
| Check-ins | List, create, edit, soft-delete, enable/disable. Name, time, day checkboxes, expiry window, ordered question picker |
| API key | Generate (shows the key and QR code once), revoke, last-used info |
| Backup | **Download database** button. Uses SQLite's online backup API to make a consistent snapshot, then streams `meatsuit-YYYYMMDD-HHMMSS.db` |

Any change to questions or check-ins increments `config_version`.

## 8. Android app

### Target
- App name "Meat Suit Diagnostics", application id `com.meatsuitdiagnostics.app`.
- `minSdk 33`, `targetSdk` = latest stable. Phone only, portrait.
- Distributed as a sideloaded APK signed with a personal release keystore. Back the keystore up: losing it means uninstalling the app (and losing unsynced data) before you can install an update.

### Local storage
- **Room**: `questions`, `checkins`, `checkin_questions` (a cache of `/config`); `checkin_instances` (`id`, `checkin_id`, `scheduled_for`, `status` = pending/snoozed/completed/expired, `snoozed_until`); `outbox` (responses waiting to upload).
- **DataStore**: server URL, API key (encrypted with an Android Keystore AES key), ETag, last sync time.
- `android:allowBackup="false"`. The network security config sets `cleartextTrafficPermitted="false"`.

### Screens
1. **Setup**: scan the QR code, test the connection, run the first sync, request permissions.
2. **Home**: pending check-ins (tap to answer), the next scheduled check-in, an **Answer now** button, and the count of answers waiting to upload.
3. **Check-in flow**: one question per page with type-specific input, plus Back, Skip and Next. A summary page, then Submit. Partial answers survive the app being killed.
4. **Answer now**: pick any active question and answer it as an ad hoc response.
5. **Settings**: **Sync now**, last sync result, server URL, re-scan the QR code, a permission and health checklist (notifications, exact alarms, battery optimization, Samsung "Never sleeping apps" guidance), and the app version.

### Scheduling
- For each enabled check-in, schedule only the **next** occurrence with `AlarmManager.setExactAndAllowWhileIdle`. When it fires, create the instance, show the notification and schedule the following occurrence.
- Alarms are rescheduled after a config sync and on `BOOT_COMPLETED`, `TIMEZONE_CHANGED`, `TIME_SET` and `MY_PACKAGE_REPLACED`.
- Days of the week and times are evaluated in the phone's current timezone.

### Notifications
- Channel "Check-ins" with high importance.
- Notification actions: **Answer**, **Snooze 30 min**, **Snooze 60 min**.
- Snoozing is allowed **once**. The re-shown notification offers only **Answer**.
- The instance **expires** at `scheduled_for + expires_after_minutes`, or when the next occurrence of the same check-in fires, whichever comes first. The notification is then cancelled (`setTimeoutAfter`) and a `missed` response is queued for each question in the check-in.
- Expiry is computed from timestamps when the app opens, so it doesn't depend on a background alarm actually firing.

### Sync
- A periodic `WorkManager` job every **6 hours**, requiring network: `GET /config` with `If-None-Match`, then upload the outbox in batches.
- A one-off expedited job after every check-in submission and every ad hoc answer.
- **Sync now** in Settings enqueues the same one-off job and shows its result.
- Retries use exponential backoff. A response leaves the outbox only once the server returns it as `accepted` or `duplicates`. A `rejected` response would fail on every retry, so it moves to a "failed uploads" list shown in Settings (with the server's error) instead of retrying forever.
- A `401` stops syncing and shows a "Key revoked — re-scan QR" banner.

### Permissions
`INTERNET`, `POST_NOTIFICATIONS` (requested at runtime), `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## 9. Deployment (Railway)

- Builds from `server/` with a Dockerfile. Start command: `alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port $PORT`.
- Volume mounted at `/data`, with `DATABASE_PATH=/data/meatsuit.db`.
- Env vars: `DATABASE_PATH`, `SESSION_SECRET`, `ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH`.
- Health check path: `/healthz`.
- HTTPS on the `*.up.railway.app` domain, or optionally a custom domain.
- Exactly one replica, which SQLite requires.

## 10. Deferred
- Charts and trends (in the app or the admin app).
- Automated off-site backups.
- Multiple users or devices.

## 11. Build order
1. Server: schema, migrations, `/config`, `/responses` (POST/GET), API-key auth, and pytest coverage of value validation and idempotency.
2. Admin UI: login, question and check-in CRUD, key generation with QR code, backup download.
3. Deploy to Railway and smoke-test with `curl`.
4. Android: setup via QR, config sync, check-in flow, outbox upload.
5. Android: alarms, notifications, snooze and expiry, boot/timezone handling.
6. Android: Settings, permission checklist, polish. Test on the A14 overnight with the screen off.
