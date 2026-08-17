# glass-peer

Host process for **Glass — Assistant Interface for GrokBot**.

Pairs one phone, holds the live session WebSocket, and talks to Grok Bot on this machine. It is not the phone app and not Grok Bot itself.

See the [root README](../README.md) for purpose, deploy, configuration, and the [security model](../SECURITY.md).

Recommended (venv + systemd `--user`, survives logout/reboot when lingering is on):

```bash
../scripts/setup-glass-peer
systemctl --user status glass-peer
journalctl --user -u glass-peer -f
```

Foreground (no service):

```bash
pip install -r requirements.txt
export GLASS_PAIR_USERNAME=admin
export GLASS_PAIR_PASSWORD="$(openssl rand -base64 32)"
export GLASS_DATA_DIR=./data
# export GLASS_PUBLIC_WSS_URL=wss://chat.example.com/session
python main.py
```

Paired restarts keep `phone_peer`, `sessionId`, and `session-log.json` in `GLASS_DATA_DIR`. The socket is new each start.

```bash
pip install pytest pytest-asyncio ruff
ruff check .
pytest tests/ -v
```
