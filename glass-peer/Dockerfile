# Glass-peer sidecar. Bind loopback only; do not publish 8080/8711.
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY *.py ./

RUN useradd -r -u 1000 glass && \
    mkdir -p /data && \
    chown glass:glass /data

USER glass

ENV GLASS_DATA_DIR=/data
ENV GLASS_PORT=8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health')" || exit 1

CMD ["python", "main.py"]
