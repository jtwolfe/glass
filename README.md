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

List messages in chronological order. **Requires authentication.**

Query parameters:
- `after` (optional): Return messages after this ISO-8601 timestamp
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

### Authentication

All endpoints except `/v0/health` require a Bearer token in the `Authorization` header:

```
Authorization: Bearer <token>
```

Two tokens are configured via environment variables:

| Environment Variable | Role | Permissions |
|---------------------|------|-------------|
| `GLASS_PHONE_TOKEN` | phone | POST messages as `jamie` only |
| `GLASS_PANE_TOKEN` | pane | GET all messages; POST messages as `ashleigh` only |

Responses:
- `401 Unauthorized`: Missing or invalid token
- `403 Forbidden`: Token valid but wrong role for the operation

Both tokens must be set and non-empty at process start.

### Running Locally

```bash
# Install dependencies
npm install

# Set required environment variables
export GLASS_PHONE_TOKEN=your-phone-token
export GLASS_PANE_TOKEN=your-pane-token

# Start the server (defaults to port 3000)
npm start

# Or specify a port
PORT=8080 npm start
```

### Running Tests

```bash
npm test
```

### Deployment

Build and run with Docker:

```bash
docker build -t glass-inbox .

docker run -d \
  -p 3000:3000 \
  -e GLASS_PHONE_TOKEN=your-phone-token \
  -e GLASS_PANE_TOKEN=your-pane-token \
  -v glass-data:/app/glass.db \
  glass-inbox
```

The service stores messages in SQLite (`glass.db`). Mount a volume to persist data across container restarts.

Optional environment variables:
- `PORT`: HTTP port (default `3000`)
- `GLASS_DB_PATH`: Path to SQLite database file (default `./glass.db`)

Deploy to any public HTTPS host (e.g., Railway, Fly.io, Cloud Run). Configure TLS at the load balancer or reverse proxy level.

### Future: MCP Integration

A later MCP server will expose the inbox to the glass Cursor plugin via `GLASS_MCP_URL`. This is not implemented in this repo yet.
