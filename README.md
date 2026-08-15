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
2. **Public HTTPS inbox** (Quay) — phone writes, Ashleigh reads. Grok Bot cannot hit localhost. Future MCP URL is `GLASS_MCP_URL` for the glass Cursor plugin.
3. **Ashleigh** — the person Jamie talks to. Routes one job. Returns a result, a decision, or a blocker.

## Contract (v0)

A message is:

- `from`: `jamie` | `ashleigh`
- `text`: string
- `at`: ISO-8601

A relay report (Ashleigh ← owner) is a glass-report: `result` | `decision` | `blocker`, one owner, one book or `none`.

No secrets in this repo. Inbox auth is configured outside git.

---

## Public HTTPS Inbox API

The inbox is a small HTTP service that stores messages from Jamie and Ashleigh.

### Endpoints

#### `GET /v0/health`

Unauthenticated liveness check.

```json
{ "ok": true }
```

#### `POST /v0/messages`

Create a message. **Requires authentication.**

Request body:

```json
{
  "from": "jamie",
  "text": "Hello Ashleigh",
  "at": "2024-01-15T10:30:00Z"
}
```

Response `201 Created`:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "from": "jamie",
  "text": "Hello Ashleigh",
  "at": "2024-01-15T10:30:00Z"
}
```

Validation:
- `from` must be `jamie` or `ashleigh`
- `text` must be a non-empty string
- `at` must be a valid ISO-8601 timestamp

#### `GET /v0/messages`

List all messages (full transcript) in chronological order. **Pane token only.**

Query parameters:
- `after` (optional): Return messages after this ISO-8601 timestamp (exclusive)
- `limit` (optional): Maximum messages to return (default 100, max 1000)

Response `200 OK`:

```json
{
  "messages": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "from": "jamie",
      "text": "Hello Ashleigh",
      "at": "2024-01-15T10:30:00Z"
    }
  ]
}
```

#### `GET /v0/replies`

List Ashleigh's replies only (for phone polling). **Phone token only.**

Query parameters:
- `after` (optional): Return replies after this ISO-8601 timestamp (exclusive)
- `limit` (optional): Maximum replies to return (default 50, max 1000)

Response `200 OK`:

```json
{
  "messages": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "from": "ashleigh",
      "text": "Hi Jamie",
      "at": "2024-01-15T10:31:00Z"
    }
  ]
}
```

#### `POST /v0/stt`

Speech-to-text transcription. **Phone token only.**

Request: `multipart/form-data` with a `file` field containing audio.

Response `200 OK`:

```json
{ "text": "transcribed text here" }
```

Errors:
- `503`: No xAI/Grok credential available (`{"error": "credential_unavailable", "detail": "..."}`)

#### `GET /v0/replies/:id/audio`

Text-to-speech for Ashleigh messages. **Phone token only.** Returns audio for the specified message ID. Only works for messages where `from=ashleigh`.

Response `200 OK`: `audio/mpeg` binary data.

Headers:
- `X-Cache: HIT` or `X-Cache: MISS`

Errors:
- `404`: Message not found
- `403`: Message is not from Ashleigh
- `503`: No xAI/Grok credential available

### Authentication

All endpoints except `/v0/health` require a Bearer token in the `Authorization` header:

```
Authorization: Bearer <token>
```

Two tokens are configured via environment variables:

| Environment Variable | Role | Permissions |
|---------------------|------|-------------|
| `GLASS_PHONE_TOKEN` | phone | POST as `jamie`; GET `/v0/replies`; POST `/v0/stt`; GET `/v0/replies/:id/audio` |
| `GLASS_PANE_TOKEN` | pane | POST as `ashleigh`; GET `/v0/messages` (full transcript) |

Responses:
- `401 Unauthorized`: Missing or invalid token
- `403 Forbidden`: Token valid but wrong endpoint or `from` value

Both tokens must be set and non-empty at process start.

### STT/TTS Credentials

The STT and TTS endpoints call xAI APIs (`https://api.x.ai/v1/stt` and `/v1/tts`). They require a credential file:

1. **xAI OAuth** (preferred): Mount at `/data/secrets/oauth.json`
2. **Grok auth** (fallback): Mount at `/home/node/.grok/auth.json` or set `GROK_AUTH_PATH`

If no credential is available, STT/TTS endpoints return `503` but the container still starts and `/v0/health` works.

### Running Locally

```bash
npm install

export GLASS_PHONE_TOKEN=your-phone-token
export GLASS_PANE_TOKEN=your-pane-token

npm start
```

### Running Tests

```bash
npm test
```

---

## Deployment

### Docker

Build the image locally:

```bash
docker build -t ghcr.io/jtwolfe/glass-inbox:0.1.0 .
```

Run locally:

```bash
docker run -d \
  -p 3000:3000 \
  -e GLASS_PHONE_TOKEN=your-phone-token \
  -e GLASS_PANE_TOKEN=your-pane-token \
  -v glass-data:/data \
  ghcr.io/jtwolfe/glass-inbox:0.1.0
```

The container:
- Runs as non-root (uid 1000)
- Stores SQLite at `/data/glass.db`
- Caches TTS audio at `/data/media/tts/`
- Requires `GLASS_PHONE_TOKEN` and `GLASS_PANE_TOKEN` at runtime

### Kubernetes

Manifests are in `k8s/`. Image is hosted on GHCR (private):

```
ghcr.io/jtwolfe/glass-inbox:0.1.0
ghcr.io/jtwolfe/glass-inbox:latest
```

To deploy:

1. Create the image pull secret (private GHCR needs auth):

```bash
# Option A: Using gh CLI (if authenticated)
kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=$(gh api user --jq .login) \
  --docker-password=$(gh auth token)

# Option B: Using a GitHub PAT with read:packages scope
kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=YOUR_GITHUB_USERNAME \
  --docker-password=YOUR_GITHUB_PAT
```

2. Create the app secrets (copy and edit the example):

```bash
cp k8s/secret.example.yaml k8s/secret.yaml
# Edit k8s/secret.yaml with real token values
# DO NOT commit secret.yaml
```

3. Apply:

```bash
kubectl apply -f k8s/pvc.yaml
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

The deployment:
- Single replica (SQLite is not multi-writer)
- PVC for `/data` (SQLite + TTS cache)
- Liveness/readiness probes on `/v0/health`
- Optional secret mounts for xAI/Grok credentials
- imagePullSecret for private GHCR image

TLS/Ingress is your responsibility — configure at your cluster level.

### Building the Image

To build and push locally (requires write access to GHCR):

```bash
docker build -t ghcr.io/jtwolfe/glass-inbox:0.1.0 .
docker tag ghcr.io/jtwolfe/glass-inbox:0.1.0 ghcr.io/jtwolfe/glass-inbox:latest

# Login to GHCR
echo $GITHUB_PAT | docker login ghcr.io -u YOUR_USERNAME --password-stdin

docker push ghcr.io/jtwolfe/glass-inbox:0.1.0
docker push ghcr.io/jtwolfe/glass-inbox:latest
```

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GLASS_PHONE_TOKEN` | Yes | — | Bearer token for phone role |
| `GLASS_PANE_TOKEN` | Yes | — | Bearer token for pane role |
| `PORT` | No | `3000` | HTTP listen port |
| `GLASS_DB_PATH` | No | `/data/glass.db` | SQLite database path |
| `GROK_AUTH_PATH` | No | `~/.grok/auth.json` | Path to Grok auth file |
| `XAI_OAUTH_PATH` | No | `/data/secrets/oauth.json` | Path to xAI OAuth file |
| `TTS_CACHE_DIR` | No | `/data/media/tts` | TTS audio cache directory |

---

## Future: MCP Integration

A later MCP server will expose the inbox to the glass Cursor plugin via `GLASS_MCP_URL`. This is not implemented in this repo yet.
