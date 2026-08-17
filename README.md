# Glass — Assistant Interface for GrokBot

Glass is a **stopgap** Android interface for Grok Bot desktop personal assistants.

xAI / SpaceXAI does not yet ship a complete first-party Android personal assistant that can sit in front of those desktop agents. Glass fills that gap for a **single operator**: one human, one phone, one `glass-peer` process next to Grok Bot. When a first-party Android assistant exists, this project is meant to be retired — not extended into a product.

It is **not** an official xAI or SpaceXAI product.

## What it does

- Sideload an Android app and set it as the default assistant (long-press home).
- Pair that phone once to a host process (`glass-peer`) via a consume-once QR.
- Talk over a live WebSocket (`wss://your-hostname/session`) that a reverse proxy you already run terminates with TLS.
- Pick a Grok Bot desktop PA from the live roster. Sends go to that PA. Replies come back as one chat row per desktop bubble, spoken in order when the app is in the foreground.

It is a **live pipe**, not an inbox. Missed this-session replies can appear as silent text on the next hello. There is no FCM, ntfy, or message backup.

## What it is not

- Not a Grok Bot client that pairs the phone to the desktop app or to the `grok` CLI.
- Not a multi-user, multi-phone, or hosted service.
- Not a VPN / Tailscale / WebRTC / public-inbox stack.
- Not a certificate authority. Glass does not mint TLS, run ACME, or listen on the WAN.
- Not a replacement for a first-party Android PA. Tool use, desktop focus, and agent quality are whatever Grok Bot already does on the host.

## Pieces

| Piece | Role |
| --- | --- |
| **Android app** (`android/`) | Default-assistant UI, pairing, hold-to-talk, sequential TTS |
| **glass-peer** (`glass-peer/`) | Loopback session socket, pairing, Grok Bot adapter, this-session reply log |
| **Your reverse proxy** | TLS + WebSocket upgrade to `http://127.0.0.1:8711`. Only public Glass path: `/session` |

Default session bind is `127.0.0.1:8711`. Operator HTTP (`/pair`, `/qr`, `/health`) is `127.0.0.1:8080` and must not be published.

```
phone  --wss://your.host/session-->  reverse proxy :443  --loopback-->  glass-peer :8711
                                                                      |
                                                                      +--> Grok Bot gateway (host-local)
```

## Deploy

You already have a reverse proxy and a certificate for a **dedicated hostname**. Examples below use `chat.example.com`. Use your own host. Do not commit real hostnames, LAN IPs, or credentials.

1. Run **Grok Bot** (the desktop Electron app) on the same machine as `glass-peer` so `~/.grokbot/local-exec-daemon-connection.json` exists. That is not the `grok` CLI.
2. Put the reverse proxy and `glass-peer` on the **same host / network namespace** so `proxy_pass http://127.0.0.1:8711` is this machine. (If they cannot share a host, see [Split proxy](#split-proxy).)
3. Publish **only** `location = /session` (or the equivalent exact path). Do **not** publish `/pair`, `/qr`, `/health`, or Grok Bot `/api/*`.
4. Optional: `GLASS_PUBLIC_WSS_URL=wss://chat.example.com/session` so the QR includes a reachability hint.
5. Start `glass-peer` (see [glass-peer](#glass-peer)).
6. On the phone: set Session URL if the QR has no `wss` hint, scan the QR, pick a PA, talk. Header **Connected** means hello-ok.

### Reverse proxy (nginx)

Zones belong in `http { }` once:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

limit_conn_zone $binary_remote_addr zone=glass_conn:1m;
limit_req_zone  $binary_remote_addr zone=glass_req:1m rate=5r/s;
```

Dedicated vhost:

```nginx
server {
    listen 443 ssl http2;
    server_name chat.example.com;

    ssl_certificate     /etc/letsencrypt/live/chat.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/chat.example.com/privkey.pem;

    location = /session {
        limit_conn glass_conn 4;
        limit_req  zone=glass_req burst=10 nodelay;

        proxy_pass http://127.0.0.1:8711;
        proxy_http_version 1.1;

        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Sec-WebSocket-Extensions "";

        proxy_read_timeout 7d;
        proxy_send_timeout 7d;
        proxy_connect_timeout 10s;

        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
        gzip off;
    }
}
```

Notes:

- `http2` on `listen` is fine; the **upstream** hop must be HTTP/1.1.
- Use the `map` for `Connection`. Do not set `Connection "upgrade"` unconditionally.
- Do not `return 308 /session`. OkHttp does not follow redirects on the upgrade request. The product URL has no trailing slash.
- Path-prefix on a shared vhost (`/glass/`) works if the phone URL is `wss://existing.example.com/glass/` (trailing slash) and `proxy_pass` rewrites to `/session`. A dedicated host is simpler.
- On LAN, prefer the same `wss://` URL (hairpin NAT or split DNS). `ws://127.0.0.1` is the phone itself and is rejected.

### Split proxy

Default `GLASS_SESSION_BIND` is loopback and anything else is refused. If the TLS terminator is on another machine, set `GLASS_SESSION_ALLOW_NONLOCAL=1` and bind the session to a **private** address. Do not WAN-forward `:8711` or `:8080`. The phone still uses `wss://` to the public host. `ws://` to a LAN/private IP is allowed as a local fallback; the committed Android network-security config forbids cleartext, so a LAN `ws://` IP must be added locally if you use that path.

### glass-peer

```bash
cd glass-peer
pip install -r requirements.txt

export GLASS_PAIR_USERNAME=admin
export GLASS_PAIR_PASSWORD="$(openssl rand -base64 32)"
export GLASS_DATA_DIR=./data
export GLASS_PUBLIC_WSS_URL=wss://chat.example.com/session

python main.py
```

Startup logs listen addresses, the public `wss` hint when set, the selected backend, and the QR JSON when unpaired.

## Agents

**Grok Bot** is the desktop Electron app (named PAs, UUIDs from that roster). **Grok Build** / the `grok` CLI (`grok agent …`) is a different product.

`glass-peer` selects **`grokbot`** when the gateway file exists (or `GLASS_AGENT_BACKEND=grokbot`). After hello, Settings lists that live roster. A send switches the desktop sidebar to the chosen PA (intended). The gateway token stays in the host connection file; the phone never sees it.

Auto when `GLASS_AGENT_BACKEND` is unset:

1. gateway file present → `grokbot`
2. else ACP cmd set → `acp`
3. else `GLASS_AGENT_URL` set → `http`
4. else `echo`

`GLASS_AGENT_BACKEND=echo` or `=acp` still wins on a Grok Bot host. ACP (`grok agent --always-approve stdio`) is a **fallback**, not the desktop roster. If the desktop is down, Settings may show a last-known roster; send does **not** fall back to Echo or ACP.

## Phone

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Set as default assistant: Settings → Apps → Default apps → Digital assistant → Glass.

1. Settings → **Session URL** → `wss://chat.example.com/session` (your hostname). Required unless the QR includes a `wss` hint. Settings overrides the hint and survives unpair.
2. Settings → Pair Plugin → scan the QR (raw JSON, not a URL), or paste it.
3. Pick a PA. Talk when the header says **Connected**.

QR identity is `{v, peer, pub, code, exp}`. Optional `wss` is a reachability hint only. Crockford `code` is 8 characters from `0-9A-HJKMNP-TV-Z` (no I, L, O, U). **`0` and `1` are valid.**

Details: [android/README.md](android/README.md).

## Replies

One send can produce **several** assistant rows — one per Grok Bot desktop bubble. The peer keeps watching that turn until `GLASS_AGENT_TIMEOUT_SEC` (default 120s), another desktop user line, or cancel. There is no idle cutoff. A second send waits until that watch ends.

Each `op:reply` is written to `{GLASS_DATA_DIR}/session-log.json` (caps: 200 rows / 256 KiB) then pushed on the live socket. If the phone process is dead, the next hello that includes integer `lastSeenSeq` is flushed as silent catch-up (`live: false`). Hellos that omit the field get no flush. Catch-up is **this plugin session only** — not desktop history, not a backup, not push. Errors are live-only and are not stored.

Paired process restart keeps `sessionId`, the reply counter, and the log. Remint and unpaired start wipe the log.

On the phone, live foreground replies play TTS **in order** (xAI if signed in, else on-device). Catch-up and background frames are text only.

## Remint

On the glass-peer host only (localhost `:8080`, Basic auth):

```bash
curl -u "$GLASS_PAIR_USERNAME:$GLASS_PAIR_PASSWORD" \
     http://127.0.0.1:8080/pair
```

That mints a new identity, evicts the current phone, kicks the live pipe, and wipes the session log. The old phone must scan again. Local chat on the phone is wiped.

## Configuration

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `GLASS_PAIR_USERNAME` / `GLASS_PAIR_PASSWORD` | Yes | — | Basic auth for localhost `/pair` `/qr` |
| `GLASS_DATA_DIR` | No | `/data` | `state.json`, `qr.svg`, `session-log.json` (use `./data` locally) |
| `GLASS_HTTP_BIND` | No | `127.0.0.1` | Operator HTTP |
| `GLASS_PORT` | No | `8080` | Operator HTTP port |
| `GLASS_SESSION_BIND` | No | `127.0.0.1` | Session WS. Other binds require `GLASS_SESSION_ALLOW_NONLOCAL=1` |
| `GLASS_SESSION_ALLOW_NONLOCAL` | No | unset | Set `1` to allow a non-loopback session bind |
| `GLASS_SESSION_PORT` | No | `8711` | Session WS port |
| `GLASS_SESSION_PATH` | No | `/session` | Canonical WS route (also registered at `/session/`) |
| `GLASS_PUBLIC_WSS_URL` | No | unset | QR `wss` hint + startup log. Bare host → `/session` |
| `GLASS_SESSION_MAX_CANDIDATES` | No | `8` | Extra upgrades close; no state touch |
| `GLASS_INVITE_TTL` | No | `300` | QR expiry (seconds) |
| `GLASS_AGENT_BACKEND` | No | auto | `grokbot` / `echo` / `http` / `acp` |
| `GLASS_GROKBOT_GATEWAY_PATH` | No | `~/.grokbot/local-exec-daemon-connection.json` | Host-local Grok Bot connection JSON |
| `GLASS_GROKBOT_PERSISTENCE_DIR` | No | `~/.config/Grok Bot/sand-client-persistence` | Last-roster disk fallback |
| `GLASS_GROKBOT_POLL_SEC` | No | `0.5` | Transcript tail poll interval |
| `GLASS_GROKBOT_TAIL_LIMIT` | No | `50` | Transcript tail page size |
| `GLASS_GROKBOT_TAIL_PAGES` | No | `4` | Extra tail pages if the send echo is not on the first page |
| `GLASS_AGENT_URL` | No | unset | `HttpBackend` |
| `GLASS_AGENT_ACP_CMD` | No | unset | `AcpBackend` argv (fallback, not the desktop roster) |
| `GLASS_AGENT_ACP_CWD` | No | process cwd | ACP `session/new` cwd |
| `GLASS_AGENT_ACP_NAME` | No | `Grok` | ACP display name |
| `GLASS_AGENT_ACP_YOLO` | No | `true` | Auto-allow ACP tool permission RPCs on this host |
| `GLASS_AGENT_TIMEOUT_SEC` | No | `120` | Per-send watch bound |
| `GLASS_SESSION_LOG_MAX` | No | `200` | Max this-session replies in `session-log.json` |
| `GLASS_SESSION_LOG_MAX_BYTES` | No | `262144` | Max `session-log.json` size (bytes) |

See [SECURITY.md](SECURITY.md).

## Operator HTTP

These exist only on `127.0.0.1:8080`. They are not on the session port and must not be on the public vhost.

### `POST` / `GET` `/pair`

Remint. **Requires Basic auth.** Clears the pair, wipes the session log, and kicks every session.

```json
{
  "invite": {
    "v": 1,
    "peer": "abcd...",
    "pub": "1234...",
    "code": "K7M2Q9WH",
    "exp": "2026-08-17T00:05:00Z",
    "wss": "wss://chat.example.com/session"
  },
  "expires": "2026-08-17T00:05:00Z"
}
```

`wss` is present only when `GLASS_PUBLIC_WSS_URL` is set.

### `GET` `/qr`

Current invite as SVG. **Requires Basic auth.** Startup already logs QR JSON and writes `qr.svg`.

### `GET` `/health`

Unauthenticated, localhost only.

```json
{
  "status": "ok",
  "paired": true,
  "connected": true,
  "sessionPort": 8711,
  "sessionPath": "/session",
  "backend": "grokbot"
}
```

`connected` is a live hello’d session, not “the proxy is up.”

## Optional compose

Intended run is `python main.py` next to the reverse proxy. `docker-compose.yaml` is a **host-network** sidecar with **no published ports**. A compose file on a different VM than the proxy is a 502 by construction.

```bash
cp .env.example .env
docker compose up -d --build
```

Do not add `ports: ["8711:8711"]` or `["8080:8080"]`.

## Development

```bash
cd glass-peer
pip install pytest pytest-asyncio ruff
ruff check .
pytest tests/ -v
```

Android: open `android/` in Android Studio (not the repo root). See [android/README.md](android/README.md).

## Troubleshooting

**502 on `wss://…/session`** — plugin is down, or the proxy is not on the same host/netns as `glass-peer`. `curl -i http://127.0.0.1:8711/session` on the host should not connection-refuse.

**Phone says “Set session URL in Settings”** — Settings is empty and the QR has no `wss` hint.

**Header stays Offline** — not hello’d. Plugin off, wrong Session URL, invite expired, or remint evicted this phone.

**Scan rejected** — invite TTL is 5 minutes by default; remint. Codes with `0`/`1` are valid.

**Pair works, reconnect does not** — Settings URL must still be your `wss://` URL.

**`GLASS_SESSION_BIND must be 127.0.0.1 or ::1`** — set `GLASS_SESSION_ALLOW_NONLOCAL=1` only for a private bind, never a public one.

**“No Grok Bot agents”** — live `listAgents` returned empty. Create a PA in Grok Bot.

**“Last known roster — desktop unreachable.”** — already on `grokbot`; live list failed. Send will error.

**Replies say `echo:`** — no gateway file at process start. Start Grok Bot, then restart `glass-peer`.

**Force-stop then reopen: rows, no audio** — expected. Catch-up is this-session and silent.

**Next send seems stuck after a long reply** — the peer still watches until `GLASS_AGENT_TIMEOUT_SEC` or another desktop user line.

## License

Private repository. No public license is granted.
