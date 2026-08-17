# Glass

One human, one `glass-peer` process, one phone. The phone talks `wss://` to **your** hostname. nginx on the **same host/netns** as `glass-peer` terminates TLS and upgrades WebSocket to `http://127.0.0.1:8711`. After upgrade the session is a live JSON pipe (pair → hello → send / `op:reply`). It is not an inbox. This-session replies that miss a live socket wait in a capped log until the next hello (text only).

Glass does not mint certificates, run ACME, or listen on the WAN.

## The one journey

You already have nginx and a certificate for a dedicated hostname (examples use `chat.example.com`).

1. Run **Grok Bot** (the desktop Electron app) on this host so `~/.grokbot/local-exec-daemon-connection.json` exists. That is not the `grok` CLI — see [Agents](#agents).
2. Put nginx and `glass-peer` on the **same host / network namespace** so `proxy_pass http://127.0.0.1:8711` is this machine.
3. Point that vhost at the session socket with `location = /session` (snippet below). Product URL: `wss://chat.example.com/session`. Do **not** publish `/api/*`.
4. Set `GLASS_PUBLIC_WSS_URL=wss://chat.example.com/session` (QR `wss` hint + startup log). Bare `wss://chat.example.com` also becomes `/session`.
5. `python main.py` — session binds `127.0.0.1:8711` (`GLASS_SESSION_BIND` other than `127.0.0.1` / `::1` is refused). Operator HTTP binds `127.0.0.1:8080`. With the gateway file present, the agent backend is `grokbot`.
6. QR JSON is in the process log (and `qr.svg` under `GLASS_DATA_DIR`).
7. On the phone, Settings → **Session URL** if the QR has no `wss` hint. Placeholder: `wss://chat.example.com/session`. Bare host → `/session`.
8. Scan the v1 QR (Crockford codes include `0` and `1`; they are valid).
9. Settings shows the live Grok Bot roster. Pick a PA and talk. Header **Connected** means hello-ok to this plugin. The desktop sidebar follows the phone pick. Plugin off → nginx `502` / WS fail → **Offline**.
10. Remint (new phone / revoke): `curl -u "$GLASS_PAIR_USERNAME:$GLASS_PAIR_PASSWORD" http://127.0.0.1:8080/pair` on this host.
11. Escape hatches: `GLASS_AGENT_BACKEND=echo` or `=acp`. ACP (`grok agent --always-approve stdio`) is a **fallback**, not the desktop roster.

## nginx

nginx and `glass-peer` are the **same host/netns**. This is the entire public Glass surface. Do not add `/pair`, `/qr`, `/health`, or Grok Bot `/api/*` here.

Zones belong in `http { }` (once per nginx):

```nginx
# http { } context
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

limit_conn_zone $binary_remote_addr zone=glass_conn:1m;
limit_req_zone  $binary_remote_addr zone=glass_req:1m rate=5r/s;
```

Copy-paste vhost (dedicated `server_name` + exact `/session`):

```nginx
server {
    listen 443 ssl http2;
    # listen [::]:443 ssl http2;   # optional IPv6
    server_name chat.example.com;   # operator hostname

    # Existing certs — Glass never reads these files.
    ssl_certificate     /etc/letsencrypt/live/chat.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/chat.example.com/privkey.pem;

    # Exact match only. Prefix `location /session` would also hit /sessionbackup.
    # Do NOT add location /pair, /qr, /health, or /api/* here.
    location = /session {
        limit_conn glass_conn 4;
        limit_req  zone=glass_req burst=10 nodelay;

        proxy_pass http://127.0.0.1:8711;   # plugin GLASS_SESSION_PATH=/session
        proxy_http_version 1.1;

        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # OkHttp may offer permessage-deflate; some nginx versions mishandle it.
        proxy_set_header Sec-WebSocket-Extensions "";

        # Default proxy_read_timeout is 60s and will kill an idle WS.
        # App + OkHttp + aiohttp ping at 30s; 7d is belt-and-suspenders.
        proxy_read_timeout 7d;
        proxy_send_timeout 7d;
        proxy_connect_timeout 10s;

        # Buffering / cache will stall or slice WS frames.
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_cache off;
        gzip off;
    }

    # Do NOT `return 308 /session` here. OkHttp does not follow redirects on
    # the upgrade request. Product URL has no trailing slash.
}
```

Checklist:

- nginx and `glass-peer` are the **same host/netns**. `127.0.0.1:8711` is this machine.
- No `location /pair`, `/qr`, `/health`, or `/api/*` on this vhost.
- No `listen` of `8711` or `8080` on a public address. Do not WAN-forward those ports.
- Dedicated `server_name` + `location = /session` (exact). Product URL: `wss://chat.example.com/session`.
- `http2` on `listen` is fine; the **upstream** hop is HTTP/1.1 (`proxy_http_version 1.1`).
- Use the `map` for `Connection`. Do not set `proxy_set_header Connection "upgrade"` unconditionally.

Path-prefix on a shared vhost (`/glass/`) is supported, not the default. Phone URL must be `wss://existing.example.com/glass/` (trailing slash). Prefer `proxy_pass http://127.0.0.1:8711/session` (no extra slash) so `/glass/` becomes `/session`. A trailing slash on that replacement maps to `/session/`, which the plugin also registers — the risk is nginx joining a leftover suffix onto `/session`, not a 404 on `/session/`. Dedicated-host operators can ignore this.

LAN uses the **same** `wss://` URL (hairpin NAT or split DNS). The phone does not dial `ws://127.0.0.1` or a LAN IP `:8711`.

## glass-peer

```bash
cd glass-peer
pip install -r requirements.txt

export GLASS_PAIR_USERNAME=admin
export GLASS_PAIR_PASSWORD="$(openssl rand -base64 32)"
export GLASS_DATA_DIR=./data
export GLASS_PUBLIC_WSS_URL=wss://chat.example.com/session
# Optional escape hatches (otherwise the gateway file selects grokbot)
# export GLASS_AGENT_BACKEND=echo
# export GLASS_AGENT_BACKEND=acp
# export GLASS_AGENT_ACP_CMD='grok agent --always-approve stdio'

python main.py
```

Startup logs the listen addresses, `public_wss hint=…` when set, `Agent: grokbot|acp|http|echo`, and the QR JSON when unpaired. Session bind other than loopback is a hard error.

## Agents

**Grok Bot** is the desktop Electron app (named PAs, UUIDs from the desktop roster). **Grok Build** / the `grok` CLI (`grok agent …`, `~/.grok/`) is a different product. Do not treat `grok agent --agent <desktop-uuid>` as a desktop PA.

`glass-peer` selects **`grokbot`** when `~/.grokbot/local-exec-daemon-connection.json` exists (or `GLASS_AGENT_BACKEND=grokbot`). After hello, Settings lists that live roster. The phone picks a PA; the process is still `glass-peer`. A send switches the Grok Bot desktop sidebar to that PA (intended). Roster order is live and not a product property — do not rely on a first-name sequence.

ACP (`GLASS_AGENT_ACP_CMD='grok agent --always-approve stdio'`) is a **fallback**, not the Grok Bot desktop PA roster. Auto when `GLASS_AGENT_BACKEND` is unset:

1. gateway file present → `grokbot`
2. else ACP cmd set → `acp`
3. else `GLASS_AGENT_URL` set → `http`
4. else `echo`

`GLASS_AGENT_BACKEND=echo` or `=acp` still wins on a Grok Bot host. Glass does not ship Grok Bot or the `grok` CLI.

Never put Grok Bot `/api/*` on nginx. The gateway token stays in the host connection file; the phone never sees it.

If the desktop is down, Settings may show a last-known roster (“desktop unreachable”). Send does **not** fall back to Echo or ACP.

## Phone

Build and install:

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Set as default assistant: Settings → Apps → Default apps → Digital assistant → Glass.

1. Settings → **Session URL** → `wss://chat.example.com/session` (or your hostname). Required unless the QR includes a `wss` hint. Settings overrides the hint and survives unpair.
2. Settings → Pair Plugin → scan the QR (raw JSON, not a URL), or paste the JSON.
3. Settings lists the live Grok Bot roster after hello. Pick a PA, then talk when the header says **Connected**.

QR identity is `{v, peer, pub, code, exp}`. Optional `wss` is a reachability hint only. Pair code is never a query param on the WSS URL.

Crockford `code` is 8 characters from `0-9A-HJKMNP-TV-Z` (no I, L, O, U). **`0` and `1` are valid.**

One send can produce several assistant rows. See [Replies](#replies) and [android/README.md](android/README.md).

## Replies

One phone send can produce **several** assistant rows — one per Grok Bot desktop bubble, in that order. After the send, the peer keeps watching that turn until `GLASS_AGENT_TIMEOUT_SEC` (default **120s** from the send), another desktop user line, or cancel. There is **no idle cutoff** and no `GLASS_GROKBOT_IDLE_SEC`. A second send waits until that watch ends.

Each `op:reply` is written to `{GLASS_DATA_DIR}/session-log.json` (caps: `GLASS_SESSION_LOG_MAX` **200**, `GLASS_SESSION_LOG_MAX_BYTES` **262144**) then pushed on the live socket. If the phone process is dead, the row stays in the log. The next hello that includes integer `lastSeenSeq` is flushed `seq > lastSeenSeq` as silent catch-up (`live: false`). Hellos that omit `lastSeenSeq` get **no** flush. Catch-up is **this plugin `sessionId` only** — not desktop history from before this session, not a backup, not an inbox, and not push (no FCM / ntfy). Errors are live-only and are not stored.

Paired `python main.py` keeps `sessionId`, the reply counter, and the log. Remint and unpaired start wipe the log and start a new session.

On the phone, live foreground replies play TTS **in order**. The next clip is prefetched so bubbles do not overlap or cut each other. Catch-up and background frames are **text only** (including on resume).

## Remint

On the glass-peer host only (same host/netns as nginx; localhost `:8080`, Basic auth):

```bash
curl -u "$GLASS_PAIR_USERNAME:$GLASS_PAIR_PASSWORD" \
     http://127.0.0.1:8080/pair
```

That mints a new identity, evicts the current phone, kicks the live pipe, and wipes `{GLASS_DATA_DIR}/session-log.json`. The old phone must scan again. Local chat on the phone is wiped. Process restart while paired does **not** remint and **keeps** `sessionId` and the log.

## Not this product

- No Glass TLS, ACME, or certificate pinning. TLS is your nginx.
- No VPN / Tailscale / Headscale / WireGuard requirement.
- No public `/pair`, `/qr`, `/health`, or Grok Bot `/api/*`.
- No WAN-forward of `8711` or `8080`.
- No split-machine nginx (TLS terminator on a different host than `glass-peer`).
- No pairing the phone to Grok Bot or to the `grok` CLI. The phone pairs to `glass-peer`.
- No multi-device / second phone.
- No message backup or restore-after-wipe. The this-session log is not a backup.
- No “online” while the plugin is off.
- No background / always-on pipe. No FCM / ntfy. Catch-up after a dead process is this-session text only.
- No baked public host.
- No mDNS / LAN TCP join path.

## Configuration

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `GLASS_PAIR_USERNAME` / `GLASS_PAIR_PASSWORD` | Yes | — | Basic auth for localhost `/pair` `/qr` |
| `GLASS_DATA_DIR` | No | `/data` | `state.json`, `qr.svg`, `session-log.json` (use `./data` for a local run) |
| `GLASS_HTTP_BIND` | No | `127.0.0.1` | Operator HTTP |
| `GLASS_PORT` | No | `8080` | Operator HTTP port |
| `GLASS_SESSION_BIND` | No | `127.0.0.1` | Session WS. `from_env` refuses anything other than `127.0.0.1` / `::1` |
| `GLASS_SESSION_PORT` | No | `8711` | Session WS port |
| `GLASS_SESSION_PATH` | No | `/session` | Canonical WS route (also registered at `/session/`, no 308) |
| `GLASS_PUBLIC_WSS_URL` | No | unset | QR `wss` hint + startup log. Not a listen address. Bare host → `/session` |
| `GLASS_SESSION_MAX_CANDIDATES` | No | `8` | Extra upgrades close; no state touch |
| `GLASS_INVITE_TTL` | No | `300` | QR expiry (seconds) |
| `GLASS_AGENT_BACKEND` | No | auto | `grokbot` / `echo` / `http` / `acp`. Auto: gateway file → `grokbot`; else ACP cmd → `acp`; else `GLASS_AGENT_URL` → `http`; else `echo` |
| `GLASS_GROKBOT_GATEWAY_PATH` | No | `~/.grokbot/local-exec-daemon-connection.json` | Grok Bot connection JSON. Token stays host-local |
| `GLASS_GROKBOT_PERSISTENCE_DIR` | No | `~/.config/Grok Bot/sand-client-persistence` | last-roster disk fallback for the picker |
| `GLASS_GROKBOT_POLL_SEC` | No | `0.5` | Transcript tail poll interval |
| `GLASS_GROKBOT_TAIL_LIMIT` | No | `50` | Transcript tail page size |
| `GLASS_GROKBOT_TAIL_PAGES` | No | `4` | Extra tail pages if the send echo is not on the first page |
| `GLASS_AGENT_URL` | No | unset | `HttpBackend` (`http://127.0.0.1:…`) |
| `GLASS_AGENT_ACP_CMD` | No | unset | `AcpBackend` argv (shell-split, no shell). Fallback, not the desktop roster |
| `GLASS_AGENT_ACP_CWD` | No | process cwd | ACP `session/new` cwd |
| `GLASS_AGENT_ACP_NAME` | No | `Grok` | ACP `list_agents` display name |
| `GLASS_AGENT_ACP_YOLO` | No | `true` | Auto-allow ACP tool permission RPCs on this host |
| `GLASS_AGENT_TIMEOUT_SEC` | No | `120` | Per-send watch bound (from that send). No idle cutoff. There is no `GLASS_GROKBOT_IDLE_SEC` |
| `GLASS_SESSION_LOG_MAX` | No | `200` | Max this-session replies in `session-log.json` |
| `GLASS_SESSION_LOG_MAX_BYTES` | No | `262144` | Max `session-log.json` size (bytes) |

See [SECURITY.md](SECURITY.md).

## Operator HTTP

These exist only on `127.0.0.1:8080`. They are not on the session port and must not be on the public vhost.

### `POST` / `GET` `/pair`

Remint. **Requires Basic auth.** Clears the pair, wipes the session log, and kicks every session.

```bash
curl -u "$GLASS_PAIR_USERNAME:$GLASS_PAIR_PASSWORD" \
     http://127.0.0.1:8080/pair
```

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

Current invite as SVG. **Requires Basic auth.** Start already logs QR JSON and writes `qr.svg`.

```bash
curl -u "$GLASS_PAIR_USERNAME:$GLASS_PAIR_PASSWORD" \
     http://127.0.0.1:8080/qr -o qr.svg
```

### `GET` `/health`

Unauthenticated, localhost only.

```bash
curl http://127.0.0.1:8080/health
```

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

`connected` is a live hello’d session, not “nginx is up.” `backend` is `grokbot` when the gateway file selected that adapter (`echo` / `acp` / `http` otherwise).

## Optional compose

Intended run is `python main.py` next to nginx. `docker-compose.yaml` is a **host-network** sidecar with **no published ports**. A compose file on a different VM than nginx is a 502 by construction.

```bash
cp .env.example .env
# set GLASS_PAIR_USERNAME, GLASS_PAIR_PASSWORD, optional GLASS_PUBLIC_WSS_URL
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

**502 on `wss://…/session`** — plugin is down, or nginx is not on the same host/netns as `glass-peer`. `curl -i http://127.0.0.1:8711/session` on the host should not connection-refuse.

**Phone says “Set session URL in Settings”** — Settings is empty and the QR has no `wss` hint. Save `wss://your.host/session`.

**Header stays Offline** — not hello’d. Plugin off, wrong Session URL, invite expired, or remint evicted this phone. **Connected** is only hello-ok.

**Scan rejected** — invite TTL is 5 minutes by default; remint. Codes with `0`/`1` are valid.

**Pair works, reconnect does not** — Settings URL must still be the operator `wss://` URL. Remint requires a new scan.

**Configuration error: `GLASS_SESSION_BIND must be 127.0.0.1 or ::1`** — do not bind the session to a public address.

**Settings says “No Grok Bot agents. Create one on the desktop.”** — live `listAgents` returned an empty roster. Create a PA in Grok Bot. A missing gateway file does **not** produce this (that selects Echo).

**Settings says “Last known roster — desktop unreachable.”** — already on `grokbot`; live list failed. Grok Bot or the gateway is down. Send errors; there is no Echo / ACP fallback.

**Replies say `echo:`** — backend is Echo (no gateway file at process start, and no ACP cmd). Start Grok Bot **then restart** `glass-peer` — `backend_kind()` only sees the connection file at startup. Or set `GLASS_AGENT_BACKEND=grokbot` **and** have the connection file (explicit `=grokbot` without the file still fails send with `no_gateway`).

**Force-stop then reopen: rows, no audio** — expected. Catch-up is this-session and silent. Remint wipes the log.

**Next send seems stuck after a long reply** — the peer still watches until `GLASS_AGENT_TIMEOUT_SEC` or another desktop user line. Not a dead pipe.

## License

Private repository. See LICENSE file.
