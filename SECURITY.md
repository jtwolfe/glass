# Glass Security Model

This document describes the security model for Glass, a WebRTC-based phone-to-peer chat system.

## Architecture Overview

```
┌─────────────┐       ntfy (signaling)       ┌─────────────┐
│   Phone     │◄────────────────────────────►│  glass-peer │
│  (Android)  │                              │ (container) │
│             │◄────────────────────────────►│             │
└─────────────┘     WebRTC DataChannel       └─────────────┘
                    (encrypted chat)
```

## Threat Model

### What Glass Protects Against

1. **Eavesdropping on chat content**: All chat messages flow over WebRTC DataChannel with DTLS encryption. Messages never traverse ntfy after the initial handshake.

2. **Unauthorized pairing**: The `/pair` and `/qr` endpoints require HTTP Basic authentication. Only operators with credentials can generate new pairing invites.

3. **Invite replay**: Invites expire after a configurable TTL (default 5 minutes). The invite code is consumed on first successful hello.

4. **Topic guessing**: ntfy topics are SHA-256 hashes of `peer + pub + code`, making them computationally infeasible to guess.

### What Glass Does NOT Protect Against

1. **Compromised endpoint**: If the phone or peer container is compromised, all bets are off. Glass does not implement end-to-end encryption with out-of-band key verification.

2. **Network-level adversaries with TURN**: Glass uses STUN only. If both endpoints are behind symmetric NAT, the connection fails closed. No TURN server means no relay through a third party, but also means some network configurations won't work.

3. **DoS on ntfy**: Public ntfy topics can receive unwanted messages. The signaling protocol ignores malformed messages, but a flood attack on ntfy could disrupt pairing.

4. **Physical QR code capture**: Anyone who photographs the QR code during its TTL window can attempt to pair. Display QR codes securely.

## Security Controls

### Authentication

| Endpoint | Auth Required | Purpose |
|----------|---------------|---------|
| `/pair` | HTTP Basic | Generate new invite (remints, clears existing pair) |
| `/qr` | HTTP Basic | Get QR code image for current invite |
| `/health` | None | Health check for orchestration |
| ntfy topics | None | Signaling (topics are unguessable hashes) |

### Credential Management

**DO NOT** commit credentials to the repository.

For Docker Compose:
```bash
# Generate strong password
GLASS_PAIR_PASSWORD=$(openssl rand -base64 32)

# Store in .env (gitignored)
echo "GLASS_PAIR_PASSWORD=$GLASS_PAIR_PASSWORD" >> .env
```

For Kubernetes:
```bash
kubectl create secret generic glass-auth \
  --from-literal=pair-username=admin \
  --from-literal=pair-password=$(openssl rand -base64 32)
```

### Network Security

1. **ntfy web UI is disabled**: The ntfy container runs with `NTFY_WEB_ROOT=disable` to prevent browser access.

2. **ntfy auth is write-only**: Default access is `write-only`, preventing subscription enumeration. Glass-peer subscribes internally.

3. **Internal ntfy URL**: The peer connects to ntfy via the internal Docker/k8s network (`http://ntfy:80`), never hairpinning through the public ingress.

4. **STUN only**: No TURN server means:
   - Pro: No third-party relay of encrypted traffic
   - Con: Symmetric NAT configurations fail to connect
   - This is fail-closed behavior by design

### Data Protection

1. **Persistent state**: Only the following is persisted in `/data/state.json`:
   - Current invite (peer, pub, code, exp) - not a secret after pairing
   - Phone peer ID (52-char base32 hash)
   - Paired-at timestamp

2. **No message persistence by default**: Chat messages are held in memory only. They're lost on peer restart. Implement external storage if persistence is required.

3. **No secrets in QR**: The QR code contains `{v, peer, pub, code, exp}`. No passwords, no host URL, no IP addresses.

## Residual Risks

### Accepted Risks

1. **Public ntfy topics are capability URLs**: Anyone with the topic hash can publish to it. The topic is derived from secret invite fields, so this is equivalent to knowing the invite.

2. **STUN metadata leakage**: STUN servers see IP addresses of both endpoints. Use a private STUN server if this is a concern.

3. **No certificate pinning**: WebRTC uses standard DTLS certificate validation. A sophisticated attacker with CA compromise could theoretically MITM (extremely unlikely in practice).

### Mitigations for High-Security Deployments

1. **Private STUN**: Run your own STUN server and set `GLASS_STUN_SERVER`.

2. **VPN/Tailscale**: Put both endpoints on a private network to avoid public STUN entirely.

3. **Short invite TTL**: Reduce `GLASS_INVITE_TTL` to minimize the window for QR capture attacks.

4. **Rate limiting**: Add rate limiting to `/pair` to prevent invite exhaustion attacks.

## Secure Deployment Checklist

- [ ] Strong, random password for `GLASS_PAIR_PASSWORD` (32+ chars)
- [ ] HTTPS on public ingress (TLS termination)
- [ ] ntfy not exposed directly (only through ingress with path strip)
- [ ] `.env` file is gitignored
- [ ] Kubernetes secret created out-of-band (not in repo)
- [ ] Review ingress annotations for your controller
- [ ] Consider network policies to restrict ntfy egress

## Vulnerability Reporting

Report security vulnerabilities privately. Do not open public issues for security bugs.

Contact: [security contact to be configured]
