# Glass-peer container
FROM python:3.12-slim

# Install system dependencies for aiortc (WebRTC)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libavcodec-dev \
    libavformat-dev \
    libavutil-dev \
    libswscale-dev \
    libsrtp2-dev \
    libvpx-dev \
    libopus-dev \
    pkg-config \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY *.py ./

# Create non-root user
RUN useradd -r -u 1000 glass && \
    mkdir -p /data && \
    chown glass:glass /data

USER glass

# Default data directory
ENV GLASS_DATA_DIR=/data
ENV GLASS_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8080/health')" || exit 1

CMD ["python", "main.py"]
