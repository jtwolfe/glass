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

The inbox is **not publicly exposed**. Nash (phone) connects via libp2p with one-time pairing.

### Listen Addresses

- **HTTP** `:3000` — In-cluster health checks only (`/v0/health`)
- **libp2p TCP** `:4001` — P2P swarm

### Pairing (glass-pair/v0)

Inbox mints pairing. On startup it prints:
1. The **short code** (6-8 alphanumeric chars)
2. The **QR payload** (JSON)

```json
{
  "v": 0,
  "peer": "<inbox libp2p peer id>",
  "addrs": ["<inbox multiaddr>", ...],
  "proto": "/glass/inbox/v0",
  "code": "ABC123"
}
```

### Pairing Flow

1. Inbox starts, mints code, prints QR JSON and code to logs
2. Nash scans QR or user enters short code
3. Phone dials inbox at `addrs`, opens `/glass/inbox/v0` stream
4. Phone sends first message with `{"code": "ABC123"}`
5. If code matches, inbox allowlists phone's peer ID → `{"status": 200, "body": {"paired": true}}`
6. Subsequent requests use normal v0 verbs with Bearer token

### Protocol: `/glass/inbox/v0`

| Verb | Description |
|------|-------------|
| `POST /v0/messages` | Create message (phone: as jamie, pane: as ashleigh) |
| `GET /v0/replies` | Ashleigh replies only (phone) |
| `GET /v0/messages` | Full transcript (pane) |
| `GET /v0/health` | Liveness check |

Phone sends `Authorization: Bearer <GLASS_PHONE_TOKEN>` in request headers.

### Private Swarm

- Unpaired peers get `401 Unauthorized`
- After pairing, only the paired peer can use `/glass/inbox/v0`
- One-time code: after pair succeeds, code cannot be reused

### Relay (Optional)

If NAT traversal needs a meeting point, set `GLASS_RELAY_ADDRS` to comma-separated libp2p relay multiaddrs. The inbox is a **circuit-relay client only** — it does NOT run a relay server.

---

## Inbox Protocol (`/glass/inbox/v0`)

JSON request/response over libp2p streams. Same contract as HTTP v0.

### Request Format

```json
{
  "method": "GET|POST",
  "path": "/v0/...",
  "headers": { "Authorization": "Bearer <token>" },
  "body": { ... },
  "query": { "after": "...", "limit": "50" }
}
```

### Pairing Request (first message only)

```json
{ "code": "ABC123" }
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
| `GLASS_PAIR_CODE` | No | (minted) | Pairing code (6-8 chars). If not set, inbox mints one. |
| `GLASS_RELAY_ADDRS` | No | — | Comma-separated relay multiaddrs for NAT traversal |
| `GLASS_ENABLE_P2P` | No | `true` | Set to `false` to disable libp2p |
| `PORT` | No | `3000` | HTTP listen port |
| `GLASS_P2P_PORT` | No | `4001` | libp2p TCP listen port |
| `GLASS_DB_PATH` | No | `/data/glass.db` | SQLite database path |

---

## Running Locally

```bash
npm install

export GLASS_PHONE_TOKEN=test-phone
export GLASS_PANE_TOKEN=test-pane
export GLASS_PAIR_CODE=abcd1234abcd1234

npm start
```

### Running Tests

```bash
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
