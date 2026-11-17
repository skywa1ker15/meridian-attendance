# syntax=docker/dockerfile:1.7
# Meridian Attendance API — multi-stage build, non-root (UID/GID 10001), read-only-rootfs safe.

ARG PYTHON_VERSION=3.13

FROM python:${PYTHON_VERSION}-slim AS builder
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /build
COPY requirements.txt .
RUN python -m venv /opt/venv \
 && /opt/venv/bin/pip install --upgrade pip \
 && /opt/venv/bin/pip install -r requirements.txt

FROM python:${PYTHON_VERSION}-slim AS runtime
ARG APP_VERSION=1.0.0
LABEL org.opencontainers.image.title="meridian-attendance-api" \
      org.opencontainers.image.description="Meridian Group Employee Attendance System backend" \
      org.opencontainers.image.version="${APP_VERSION}"

ENV PATH="/opt/venv/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TMPDIR=/tmp \
    APP_VERSION=${APP_VERSION} \
    ENV_FILE=/nonexistent/.env \
    PORT=8000

RUN groupadd --system --gid 10001 meridian \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin meridian

COPY --from=builder /opt/venv /opt/venv
WORKDIR /app
COPY --chown=10001:10001 alembic.ini ./
COPY --chown=10001:10001 alembic ./alembic
COPY --chown=10001:10001 app ./app
COPY --chown=10001:10001 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
# Byte-compile at build time so nothing needs to be written at runtime (read-only root FS).
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh \
 && python -m compileall -q /app /opt/venv >/dev/null \
 && chmod -R a-w /app

USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=4).status == 200 else 1)"
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
