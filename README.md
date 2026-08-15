# glass

Minimize the distance between Jamie and the teams.

Jamie long-presses the phone (replaces Gemini). Jamie talks to **Ashleigh**. Ashleigh talks back, then relays to **one** owner. Not a committee.

## Owners Ashleigh relays to

- Implementation / betanamycs — Clippy
- Desk — Rupert (never mix LARGE / PAPER_US / sleeves)
- Firm — Heather
- EA / BPM — Aki
- Mesh / platform how-to — Chel

This repo is not MyMesh, not carrier hardware, not the desk books.

## Pieces

1. **Android client** (Nash) — `VoiceInteractionService` / `ACTION_ASSIST` / `ROLE_ASSISTANT`. Chat UI is Ashleigh only.
2. **Inbox** (this repo) — P2P pairing with Nash. Not publicly exposed. Runs on Jamie's k8s cluster.
3. **Ashleigh** — the person Jamie talks to. Routes one job. Returns a result, a decision, or a blocker.

## Contract (v0)

A message is:

- `from`: `jamie` | `ashleigh`
- `text`: string
- `at`: ISO-8601

A relay report (Ashleigh ← owner) is a glass-report: `result` | `decision` | `blocker`, one owner, one book or `none`.

No secrets in this repo. Inbox auth is configured outside git.

---

## P2P Architecture

The inbox is **not publicly exposed**. Nash (phone) connects via libp2p with one-time invite.

### Listen Addresses

- **HTTP** `:3000` — Health checks (`/v0/health`) and invite fetch (`/v0/pair`)
- **libp2p TCP** `:4001` — P2P swarm

### Invite (glass-pair/v0)

Inbox mints one invite on startup. Prints QR (raw JSON UTF-8) and short code:

```json
{
  "v": 0,
  "peer": "<inbox libp2p peer id>",
  "addrs": ["<multiaddr>", ...],
  "proto": "/glass/inbox/v0",
  "code": "K7M2Q9WH",
  "psk": "<64 hex, 32-byte swarm key>",
  "exp": "<ISO-8601>"
}
```

| Field | Description |
|-------|-------------|
| `code` | 8 chars, Crockford base32 (A-Z2-7, no I/L/O/0/1) |
| `psk` | 32-byte private swarm key (QR only, 64 hex chars) |
| `exp` | Expires 15 minutes from mint |

### Pairing Flow

**QR scan:**
1. Inbox starts, mints invite, prints QR JSON and code to logs
2. Nash scans QR (gets full invite with PSK)
3. Phone dials inbox at `addrs`, opens `/glass/inbox/v0` stream
4. Phone sends `{"psk": "<64 hex>"}` as first message
5. If PSK matches and not expired → peer allowlisted, invite consumed
6. Subsequent requests use v0 verbs with Bearer token

**Short-code only (via relay):**
1. User enters short code on phone
2. Phone subscribes to `/glass/pair/<code>` topic on relay
3. Inbox (if connected to same relay) publishes full invite JSON once
4. Phone receives invite, proceeds as above

### Invite Rules

- **Accepted once**: first successful `/glass/inbox/v0` stream using PSK before exp consumes invite
- **Expired**: `410 Gone` if past exp
- **Already used**: `410 Gone` if pairing already complete
- **Invalid PSK**: `401 Unauthorized`

### GET /v0/pair (HTTP)

Fetch the current invite JSON over HTTP (no docker logs scraping).

- **Auth**: Pane token OR loopback (127.0.0.1 / ::1). Phone token → 403.
- **200**: Returns the same `glass-pair/v0` JSON as the QR
- **404**: No active invite (P2P disabled)
- **410**: Invite expired or already consumed

### Protocol: `/glass/inbox/v0`

After pairing, phone uses HTTP-like JSON requests:

| Verb | Description |
|------|-------------|
| `POST /v0/messages` | Create message (phone: as jamie, pane: as ashleigh) |
| `GET /v0/replies` | Ashleigh replies only (phone) |
| `GET /v0/messages` | Full transcript (pane) |
| `GET /v0/health` | Liveness check |

Phone sends `Authorization: Bearer <GLASS_PHONE_TOKEN>` in request headers.

### Relay (Optional)

Set `GLASS_RELAY_ADDRS` to comma-separated libp2p relay multiaddrs for:
- NAT traversal (hole-punching rendezvous)
- Short-code-only pairing (`/glass/pair/<code>` topic)

The inbox is a **circuit-relay client only** — it does NOT run a relay server.

---

## Inbox Protocol (`/glass/inbox/v0`)

JSON request/response over libp2p streams. Same contract as HTTP v0.

### Pairing Request (first message, unpaired peer)

```json
{ "psk": "<64 hex from invite>" }
```

### Request Format (after pairing)

```json
{
  "method": "GET|POST",
  "path": "/v0/...",
  "headers": { "Authorization": "Bearer <token>" },
  "body": { ... },
  "query": { "after": "...", "limit": "50" }
}
```

### Response Format

```json
{
  "status": 200,
  "body": { ... }
}
```

### Endpoints

| Path | Method | Auth | Description |
|------|--------|------|-------------|
| `/v0/health` | GET | No | Liveness check |
| `/v0/messages` | POST | Yes | Create message |
| `/v0/messages` | GET | Pane | Full transcript |
| `/v0/replies` | GET | Phone | Ashleigh replies only |

Phone sends `Authorization: Bearer <GLASS_PHONE_TOKEN>` in request headers.

---

## Kubernetes Deployment

**ClusterIP only. No Ingress. No public hostname.**

Image on private GHCR:

```
ghcr.io/jtwolfe/glass-inbox:0.1.0
ghcr.io/jtwolfe/glass-inbox:latest
```

### 1. Create Image Pull Secret

```bash
# Using gh CLI
kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=$(gh api user --jq .login) \
  --docker-password=$(gh auth token)
```

### 2. Create App Secrets

```bash
cp k8s/secret.example.yaml k8s/secret.yaml
# Edit with real values:
# - GLASS_PHONE_TOKEN
# - GLASS_PANE_TOKEN  
# - GLASS_PAIR_CODE (hex string, min 8 chars)
# - GLASS_RELAY_ADDRS (optional, comma-separated multiaddrs)
#
# DO NOT commit secret.yaml
```

### 3. Apply

```bash
kubectl apply -f k8s/
```

### What Gets Deployed

- **Deployment**: 1 replica (SQLite not multi-writer), ports 3000 + 4001
- **Service**: ClusterIP exposing 3000 (HTTP) and 4001 (libp2p)
- **PVC**: Persistent storage for SQLite at `/data`

TLS/Ingress is Jamie's responsibility — and Jamie is choosing not to have public ingress.

---

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GLASS_PHONE_TOKEN` | Yes | — | Bearer token for phone role |
| `GLASS_PANE_TOKEN` | Yes | — | Bearer token for pane role |
| `GLASS_RELAY_ADDRS` | No | — | Comma-separated relay multiaddrs |
| `GLASS_ANNOUNCE_ADDRS` | No | — | Comma-separated multiaddrs to advertise in invite (e.g. `/ip4/192.168.1.200/tcp/4001`) |
| `GLASS_ENABLE_P2P` | No | `true` | Set to `false` to disable libp2p |
| `PORT` | No | `3000` | HTTP listen port |
| `GLASS_P2P_PORT` | No | `4001` | libp2p TCP listen port |
| `GLASS_DB_PATH` | No | `/data/glass.db` | SQLite database path |

Invite is minted automatically on startup (8-char code + 32-byte PSK + 15-min expiry).

**`GLASS_ANNOUNCE_ADDRS`**: Without this, the invite `addrs` contain only 127.0.0.1 or Docker bridge IPs (172.17.0.x) which the phone cannot dial. Set to your host's LAN IP so the QR contains `/ip4/192.168.1.200/tcp/4001` (or your actual LAN IP).

---

## Running Locally (Docker)

```bash
docker build -t glass-inbox-local .

docker run -d --name glass-inbox-local \
  -e GLASS_PHONE_TOKEN="$GLASS_PHONE_TOKEN" \
  -e GLASS_PANE_TOKEN="$GLASS_PANE_TOKEN" \
  -e GLASS_ANNOUNCE_ADDRS=/ip4/192.168.1.200/tcp/4001 \
  -p 127.0.0.1:3000:3000 \
  -p 127.0.0.1:4001:4001 \
  glass-inbox-local
```

Set `GLASS_PHONE_TOKEN` and `GLASS_PANE_TOKEN` in your shell env before running. Replace `192.168.1.200` with your host's LAN IP.

### Pairing

```bash
# Fetch invite JSON (loopback or pane token)
curl -s http://127.0.0.1:3000/v0/pair | jq

# Or check logs for QR
docker logs glass-inbox-local
```

Phone scans QR or enters short code → connects via `/glass/inbox/v0`.

### Health Check

```bash
curl http://127.0.0.1:3000/v0/health
```

### Running Tests

```bash
npm install
npm test
```

---

## Building the Image

```bash
docker build -t ghcr.io/jtwolfe/glass-inbox:0.1.0 .
docker tag ghcr.io/jtwolfe/glass-inbox:0.1.0 ghcr.io/jtwolfe/glass-inbox:latest

# Login to GHCR
echo $GITHUB_PAT | docker login ghcr.io -u YOUR_USERNAME --password-stdin

docker push ghcr.io/jtwolfe/glass-inbox:0.1.0
docker push ghcr.io/jtwolfe/glass-inbox:latest
```

---

## STT/TTS (Optional, Fail-Closed)

HTTP endpoints exist for STT/TTS but are **not required**. Product STT/TTS is phone-side.

If xAI credentials are mounted at `/data/secrets/oauth.json`, the endpoints work. Otherwise they return `503`. The container starts either way.

---

## Future: MCP Integration

A later MCP server will expose the inbox to the glass Cursor plugin via `GLASS_MCP_URL`. This is not implemented in this repo yet.
