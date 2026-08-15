import { serve } from '@hono/node-server';
import { createApp } from './routes.js';
import { initDb } from './db.js';
import { validateTokensOnStartup } from './auth.js';
import { createP2PNode, getInvite } from './p2p.js';
import { handleInboxRequest } from './inbox-handler.js';

const PORT = parseInt(process.env.PORT || '3000', 10);
const P2P_PORT = parseInt(process.env.GLASS_P2P_PORT || '4001', 10);
const RELAY_ADDRS = (process.env.GLASS_RELAY_ADDRS || '').split(',').filter(Boolean);
const ANNOUNCE_ADDRS = (process.env.GLASS_ANNOUNCE_ADDRS || '').split(',').filter(Boolean);
const ENABLE_P2P = process.env.GLASS_ENABLE_P2P !== 'false';

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

if (ENABLE_P2P) {
  try {
    await createP2PNode({
      relayAddrs: RELAY_ADDRS,
      announceAddrs: ANNOUNCE_ADDRS,
      listenPort: P2P_PORT,
      onInboxRequest: handleInboxRequest,
    });
    
    const invite = getInvite();
    
    console.log('');
    console.log('=== GLASS INVITE ===');
    console.log(`Code: ${invite.code}`);
    console.log(`Expires: ${invite.exp}`);
    console.log('');
    console.log('QR (raw JSON UTF-8):');
    console.log(JSON.stringify(invite));
    console.log('====================');
    console.log('');
    console.log(`libp2p listening on port ${P2P_PORT}`);
  } catch (err) {
    console.error('libp2p startup failed:', err.message);
    console.error('HTTP server still running for health checks');
  }
} else {
  console.log('P2P disabled (GLASS_ENABLE_P2P=false)');
}
