# Glass

Voice assistant bridge: phone talks to a peer over WebRTC, the peer relays to agents.

## Architecture

```
┌─────────────┐                              ┌─────────────┐
│   Phone     │   ntfy signaling (HTTPS)     │  glass-peer │
│  (Android)  │◄────────────────────────────►│ (container) │
│             │                              │             │
│  - QR scan  │   WebRTC DataChannel         │  - /pair    │
│  - Voice    │◄────────────────────────────►│  - /qr      │
│  - Chat UI  │   (encrypted, P2P)           │  - Agents   │
└─────────────┘                              └─────────────┘
        │                                           │
        │  xAI STT/TTS (voice only)                 │
        └───────────────────────────────────────────┘
```

### Components

1. **Android app** — Voice assistant that replaces default. Long-press home → talk → chat UI.
2. **glass-peer** — Container that accepts WebRTC connections and handles agent communication.
3. **ntfy** — Signaling server for WebRTC offer/answer/ICE exchange. Chat never goes through ntfy.

### Pairing Flow

1. Operator calls `/pair` (authenticated) → generates QR with `{v, peer, pub, code, exp}`
2. Phone scans QR → computes ntfy topic as SHA-256 hash
3. Phone creates WebRTC offer → publishes to ntfy topic
4. Peer receives offer → creates answer → publishes to ntfy topic
5. Both exchange ICE candidates via ntfy
6. DataChannel opens → phone sends `{op: "hello", peer: "<phone_peer_id>"}`
7. Peer persists phone_peer → stable topic computed for reconnects
8. **Reminting** a new QR clears the existing pair and starts fresh

### Security Model

- **QR contains no secrets**: Only peer identity, public key, one-time code, and expiry
- **Topics are unguessable**: SHA-256 of invite fields → 64-char hex
- **Chat is encrypted**: WebRTC DTLS, never through ntfy after handshake
- **STUN only**: No TURN relay. Fail closed if NAT prevents direct connection
- **Consume-once invite**: After hello, stable topic replaces invite topic

See [SECURITY.md](SECURITY.md) for full threat model.

## Quick Start

### Docker Compose (Single Machine)

```bash
# Clone and configure
cp .env.example .env
# Edit .env with strong password and your public URL

# Build and start
docker compose up -d

# Generate pairing QR
curl -u "$GLASS_PAIR_USERNAME:$GLASS_PAIR_PASSWORD" \
     http://localhost:8080/pair

# Get QR image
curl -u "$GLASS_PAIR_USERNAME:$GLASS_PAIR_PASSWORD" \
     http://localhost:8080/qr -o qr.svg
```

### Kubernetes (Helm)

```bash
# Create auth secret
kubectl create secret generic glass-auth \
  --from-literal=pair-username=admin \
  --from-literal=pair-password=$(openssl rand -base64 32)

# Install chart
helm install glass helm/glass \
  --set ingress.host=glass.example.com \
  --set ingress.tls.secretName=glass-tls
```

See [helm/glass/values.yaml](helm/glass/values.yaml) for all options.

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GLASS_PAIR_USERNAME` | Yes | — | HTTP Basic auth username for /pair, /qr |
| `GLASS_PAIR_PASSWORD` | Yes | — | HTTP Basic auth password (use strong random) |
| `GLASS_NTFY_INTERNAL_URL` | No | `http://ntfy:80` | ntfy URL for peer (internal network) |
| `GLASS_NTFY_PUBLIC_URL` | No | `https://glass.example.com/ntfy` | ntfy URL for phone (public) |
| `GLASS_STUN_SERVER` | No | `stun:stun.l.google.com:19302` | STUN server for NAT traversal |
| `GLASS_INVITE_TTL` | No | `300` | Invite expiry in seconds |
| `GLASS_DATA_DIR` | No | `/data` | Persistent state directory |
| `GLASS_PORT` | No | `8080` | HTTP server port |

### Reverse Proxy Setup

ntfy cannot have a path in its `BASE_URL`. If you use a path prefix like `/ntfy`, configure your reverse proxy to strip it:

**nginx:**
```nginx
location /ntfy/ {
    rewrite ^/ntfy/(.*)$ /$1 break;
    proxy_pass http://ntfy:80;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
}
```

**Traefik (Kubernetes Ingress):**
```yaml
annotations:
  nginx.ingress.kubernetes.io/rewrite-target: /$2
```

## API Reference

### `POST /pair`

Generate new pairing invite. **Requires authentication.**

Reminting clears any existing pair.

```bash
curl -u admin:password http://localhost:8080/pair
```

Response:
```json
{
  "invite": {
    "v": 1,
    "peer": "abcd...",
    "pub": "1234...",
    "code": "XYZW5678",
    "exp": "2024-01-01T00:05:00Z"
  },
  "topic": "a1b2c3d4...",
  "expires": "2024-01-01T00:05:00Z"
}
```

### `GET /qr`

Get QR code image for current invite. **Requires authentication.**

```bash
curl -u admin:password http://localhost:8080/qr -o qr.svg
```

Returns SVG image.

### `GET /health`

Health check. **No authentication.**

```bash
curl http://localhost:8080/health
```

Response:
```json
{
  "status": "ok",
  "paired": true,
  "connected": true
}
```

## Android App

Build and install the Android app:

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Configure as default assistant:
1. Settings → Apps → Default apps → Digital assistant
2. Select "Glass"

### App Configuration

The phone needs the public ntfy URL configured. This is set in app settings after first launch.

## Development

### Running Locally

```bash
# Install dependencies
cd glass-peer
pip install -r requirements.txt

# Set environment
export GLASS_PAIR_USERNAME=dev
export GLASS_PAIR_PASSWORD=dev
export GLASS_NTFY_INTERNAL_URL=http://localhost:8080
export GLASS_DATA_DIR=./data

# Run (requires ntfy running separately)
python main.py
```

### Running Tests

```bash
cd glass-peer
pip install pytest pytest-asyncio ruff
ruff check .
pytest tests/ -v
```

### Helm Chart Development

```bash
# Lint
helm lint helm/glass

# Template
helm template test helm/glass \
  --set auth.existingSecret=test \
  --set ingress.host=test.example.com
```

## Troubleshooting

### Connection Fails Immediately

- **Symmetric NAT**: Both endpoints behind symmetric NAT cannot connect with STUN only. Use a VPN or ensure one endpoint has a public IP.

### Pairing Works but Reconnect Fails

- **Invite expired**: Remint with `/pair`. The stable topic is only valid after successful hello.
- **State lost**: Check that `/data` is persisted across restarts.

### ntfy Errors

- **404 on topic**: ntfy is working. Topics don't exist until first publish.
- **Connection refused**: Check ntfy container is healthy and network allows access.

### WebRTC Errors

- **ICE failed**: NAT traversal failed. Check STUN server is reachable from both endpoints.
- **DTLS failed**: Clock skew between endpoints can cause this. Sync NTP.

## License

Private repository. See LICENSE file.
