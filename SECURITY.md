# Glass Security Model

Glass is a single-operator stopgap: one `glass-peer` beside Grok Bot, one phone, TLS at **your** reverse proxy.

```
┌─────────────┐     wss://your.host/session      ┌──────────────┐     loopback      ┌─────────────┐
│   Phone     │────────────────────────────────►│ reverse proxy │──────────────────►│  glass-peer │
│  (Android)  │     system CA store              │     :443      │  127.0.0.1:8711  │  /session   │
└─────────────┘                                  └──────────────┘                   │  :8080      │
                                                                                    │  /pair /qr  │
                                                                                    │  /health    │
                                                                                    └─────────────┘
```

Confidentiality of the phone hop is **TLS to the operator’s proxy**. Confidentiality of proxy → plugin is **loopback** (or a private LAN bind you opted into). Pairing stops a random Internet client from binding as the phone. It is **not** end-to-end encryption against a compromised proxy host or a compromised Grok Bot machine.

`glass-peer` dials the Grok Bot gateway **outbound** using the host-local connection file. Do not put `/api/*` on the reverse proxy. The phone never sees the gateway URL or token.

## Threat Model

### What Glass Protects Against

1. **Casual eavesdropping on the phone hop**: The phone speaks `wss://` for a public host. Release `usesCleartextTraffic` is false. Trust is the Android system CA store (or a private CA the operator installed in the OS — not in Glass). `ws://` is allowed only to a LAN/private IP; the committed network-security config still forbids cleartext unless you add that IP locally.

2. **Unauthorized remint**: `/pair` and `/qr` require HTTP Basic auth and bind loopback (`127.0.0.1:8080`). They are not on the public vhost and not implemented on `:8711`.

3. **Invite replay**: Invites expire (`GLASS_INVITE_TTL`, default 300s). The code is consume-once on the hello that follows pair on **that** socket. A later remint invalidates unused codes.

4. **Stranger on `:443` becoming the phone**: Anyone can complete TLS and the WebSocket upgrade. Auth is the first frames: unused invite `code` on this socket, then `hello.peer`. Reconnect hello succeeds only if `hello.peer` equals the stored `phone_peer`. Hello without a pair on an unpaired plugin is rejected and does not persist.

### What Glass Does NOT Protect Against

1. **Compromised reverse proxy or Grok Bot host**: That host sees the cleartext session after TLS termination. There is no second-hop encryption and no certificate pin from the QR `pub`.

2. **Stolen `phone_peer`**: After pair, the 52-char `phone_peer` is a **bearer** on public `:443` (256-bit capability). Stolen from logs, backup, or a rooted phone ⇒ full session until remint, including **unplayed this-session replies** in the peer log, not only future live frames. Treat it like a session token. Remint is revocation. After pair, never log more than `phone_peer[:12]` (including DEBUG). The unpaired startup line and remint `QR {…}` line print the full invite on purpose so the operator can scan.

3. **Physical QR capture**: Anyone who photographs the QR during its TTL can attempt the first pair. Display it on the operator machine, not a public page.

4. **DoS on public `/session`**: The upgrade path is on the Internet. Use `limit_conn` / `limit_req` (or the equivalent). Extra candidates beyond `GLASS_SESSION_MAX_CANDIDATES` (8) are closed without touching pair state.

5. **Agent tools and desktop focus**: Desktop Grok Bot `localToolPermission: always` is a host trust boundary. Phone send switches the desktop sidebar to that PA (intended). ACP `GLASS_AGENT_ACP_YOLO` defaults to `true` and auto-allows the child’s permission RPCs (ACP fallback only).

6. **Missed replies**: Session is a live pipe. Background, doze, or plugin-off drops the socket. There is no FCM / ntfy. If the phone process is dead, replies wait in a **capped this-session log** (`session-log.json`) and are pushed on the next hello that includes integer `lastSeenSeq` as `live: false`. Hellos that omit `lastSeenSeq` get no flush. Errors are not stored. Remint / unpaired start wipe. Not a backup, not an inbox, not desktop history.

## Security Controls

### Authentication

| Surface | Bind | Public proxy? | Auth |
| --- | --- | --- | --- |
| WebSocket `/session` | `127.0.0.1:8711` | Yes — only public Glass path | Invite `code` once, then `phone_peer` on hello |
| `POST`/`GET` `/pair` | `127.0.0.1:8080` | **No** | HTTP Basic |
| `GET` `/qr` | `127.0.0.1:8080` | **No** | HTTP Basic |
| `GET` `/health` | `127.0.0.1:8080` | **No** | None (localhost supervisor) |
| `HttpBackend` / ACP stdio | loopback / pipes | **No** | Not exposed to the phone |
| Grok Bot gateway `POST /api/*` | outbound from host | **No** — never on the proxy | Bearer from `~/.grokbot/local-exec-daemon-connection.json` |

The proxy does not add a second HTTP password on `/session`. Do not put `?code=` on the WSS URL — access logs must not become a capability leak.

### Credential Management

**DO NOT** commit credentials, real hostnames, or LAN addresses to the repository.

```bash
GLASS_PAIR_PASSWORD=$(openssl rand -base64 32)
# Store in .env (gitignored) or the process environment
```

`GLASS_PAIR_USERNAME` and `GLASS_PAIR_PASSWORD` are required to start.

`scripts/setup-glass-peer` writes those values to `~/.config/glass/peer.env` (mode `0600`) and a systemd **user** unit. That file is a secret. Do not copy it into the repo or onto the phone. Uninstalling the unit leaves the env file and data dir in place until you delete them.

### Network

1. **Session bind defaults to loopback.** `Config.from_env` refuses `GLASS_SESSION_BIND` other than `127.0.0.1` / `::1` unless `GLASS_SESSION_ALLOW_NONLOCAL=1`.

2. **Operator HTTP is loopback.** Remint is `curl` to `http://127.0.0.1:8080/pair` on the glass-peer host.

3. **Same host/netns as the proxy** is the supported default. `proxy_pass http://127.0.0.1:8711` is then this machine. A split proxy is an explicit opt-in (private bind only).

4. **Do not WAN-forward `8711` or `8080`.** Do not listen them on a public address.

5. **No Glass TLS.** Certificates stay in the reverse proxy. The phone does not pin.

6. **Do not publish Grok Bot `/api/*`.** The gateway is not a glass-peer route. The proxy serves `/session` only.

### Data Protection

1. **Peer `state.json`**: current invite (`peer`, `pub`, `code`, `exp`), `phone_peer`, `session_id`, consume flag. Invite is not a long-term secret after consume.

2. **Phone**: pairing in EncryptedSharedPreferences; chat in this-install DataStore (`allowBackup=false`). Remint / unpair / `unpaired` / `wrong_peer` wipe the local thread.

3. **Bounded this-session reply log.** `{GLASS_DATA_DIR}/session-log.json` holds assistant `op:reply` frames for the current plugin `sessionId` (defaults: 200 / 256 KiB). Same host trust as `state.json`. Wipe on remint / unpaired start. Paired process start **keeps** `sessionId` and the log. Only the hello’d `phone_peer` receives flush. Reply text is never written to INFO logs. The gateway token is never copied into the log.

4. **QR has no passwords and no listen address.** Identity is `{v, peer, pub, code, exp}`. Optional `wss` is the URL you already configured.

5. **Voice tokens**: xAI bearer stays in `XaiAuthStore` on device.

6. **Grok Bot gateway file**: `~/.grokbot/local-exec-daemon-connection.json` stays on the host (typically mode `0600`). Never copy the token or `baseUrl` onto the phone, into the QR, into `state.json`, or into logs.

## Residual Risks

### Accepted

1. **The reverse proxy is in the trust base.** A compromised edge reads and can inject session frames.

2. **`phone_peer` is a capability.** Leak ⇒ session until remint, including unread this-session replies in the peer log.

3. **No certificate pinning / no E2E.** A CA-compromising attacker, or anyone who can mint a cert for your hostname, can MITM the phone hop.

4. **One phone.** A second successful pair is rejected until remint. Remint evicts the first.

5. **This-session reply log on disk.** `{GLASS_DATA_DIR}/session-log.json` holds assistant text until remint / unpaired start / eviction. Same host trust as `state.json`. Not a backup. Catch-up is capability-gated: no `lastSeenSeq` on hello ⇒ no flush.

### Mitigations

1. Short invite TTL (`GLASS_INVITE_TTL`) if the QR is displayed in a less private place.

2. Connection / request limits on `/session` (see README snippet).

3. Remint when a device is lost or a log may have leaked `phone_peer`.

4. Keep `/pair` off the public vhost even if the hostname is later shared with other apps.

## Secure Deployment Checklist

- [ ] Reverse proxy and `glass-peer` on the same host/netns (or a documented private bind)
- [ ] Dedicated `server_name` + exact `/session` only (Upgrade, long timeout, no buffering, no WebSocket extensions)
- [ ] No public `/pair`, `/qr`, `/health`, or `/api/*`
- [ ] Session and operator HTTP not published and not WAN-forwarded
- [ ] Strong random `GLASS_PAIR_PASSWORD` (32+ chars); `.env` gitignored
- [ ] `GLASS_PUBLIC_WSS_URL` is **your** `wss://hostname/session` (documentation examples only in-repo)
- [ ] Operator cert chains to a public CA, or the private CA is in the phone’s OS trust store
- [ ] Grok Bot gateway token stays host-local; never on the phone or in logs
- [ ] Desktop YOLO + sidebar-follows-phone-pick understood (intended)
- [ ] `GLASS_AGENT_ACP_YOLO` understood (ACP fallback; default auto-allows tools on this host)
- [ ] After pair, logs never print more than `phone_peer[:12]`. Unpaired/remint QR lines are supposed to contain the full invite.
- [ ] Remint understood as the wipe for `session-log.json` (paired restart keeps `sessionId` and the log)

## Vulnerability Reporting

Report security issues privately to the repository owner. Do not open public issues for security bugs.
