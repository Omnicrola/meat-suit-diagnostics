# Server image. Lives at the repo root so Railway finds it without a Root Directory setting;
# everything it needs is under server/.
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DATABASE_PATH=/data/meatsuit.db

WORKDIR /srv

COPY server/pyproject.toml ./
COPY server/app ./app
RUN pip install --no-cache-dir .

COPY server/alembic.ini ./
COPY server/migrations ./migrations

# Railway terminates TLS and forwards plain HTTP to $PORT.
CMD ["sh", "-c", "mkdir -p \"$(dirname \"$DATABASE_PATH\")\" && alembic upgrade head && exec uvicorn app.main:create_app --factory --host 0.0.0.0 --port ${PORT:-8000} --proxy-headers --forwarded-allow-ips='*'"]
