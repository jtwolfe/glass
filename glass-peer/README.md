# glass-peer

Host process for **Glass — Assistant Interface for GrokBot**.

Pairs one phone, holds the live session WebSocket, and talks to Grok Bot on this machine. It is not the phone app and not Grok Bot itself.

See the [root README](../README.md) for purpose, deploy, configuration, and the [security model](../SECURITY.md).

```bash
pip install -r requirements.txt
export GLASS_PAIR_USERNAME=admin
export GLASS_PAIR_PASSWORD="$(openssl rand -base64 32)"
export GLASS_DATA_DIR=./data
# export GLASS_PUBLIC_WSS_URL=wss://chat.example.com/session
python main.py
```

```bash
pip install pytest pytest-asyncio ruff
ruff check .
pytest tests/ -v
```
