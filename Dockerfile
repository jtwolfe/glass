# Build stage - compile native modules
FROM node:20-slim AS builder

RUN apt-get update && apt-get install -y \
    build-essential \
    python3 \
    make \
    g++ \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./

RUN npm ci --only=production

# Runtime stage - slim image
FROM node:20-slim

WORKDIR /app

# Create non-root user
RUN groupadd -g 1000 node_app && \
    useradd -u 1000 -g node_app -m -s /bin/bash node_app || true

# Copy built node_modules from builder
COPY --from=builder /app/node_modules ./node_modules

# Copy source
COPY src/ ./src/
COPY package.json ./

# Create data directory for SQLite
RUN mkdir -p /data/media/tts && chown -R 1000:1000 /data

ENV NODE_ENV=production
ENV PORT=3000
ENV GLASS_P2P_PORT=4001
ENV GLASS_DB_PATH=/data/glass.db

EXPOSE 3000 4001

USER 1000

CMD ["node", "src/index.js"]
