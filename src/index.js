import { serve } from '@hono/node-server';
import { createApp } from './routes.js';
import { initDb } from './db.js';
import { validateTokensOnStartup } from './auth.js';
import { createP2PNode } from './p2p.js';
import { handleInboxRequest } from './inbox-handler.js';

const PORT = parseInt(process.env.PORT || '3000', 10);
const P2P_PORT = parseInt(process.env.GLASS_P2P_PORT || '4001', 10);
const PAIR_CODE = process.env.GLASS_PAIR_CODE;
const RELAY_ADDRS = (process.env.GLASS_RELAY_ADDRS || '').split(',').filter(Boolean);

try {
  validateTokensOnStartup();
} catch (err) {
  console.error('Startup failed:', err.message);
  process.exit(1);
}

initDb();

const app = createApp();

console.log(`glass inbox HTTP listening on port ${PORT}`);
serve({ fetch: app.fetch, port: PORT });

if (PAIR_CODE) {
  try {
    await createP2PNode({
      pairSecret: PAIR_CODE,
      relayAddrs: RELAY_ADDRS,
      listenPort: P2P_PORT,
      onInboxRequest: handleInboxRequest,
    });
    console.log(`libp2p listening on port ${P2P_PORT}`);
  } catch (err) {
    console.error('libp2p startup failed:', err.message);
    console.error('HTTP server still running for health checks');
  }
} else {
  console.log('GLASS_PAIR_CODE not set - libp2p disabled, HTTP-only mode');
}
