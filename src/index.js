import { serve } from '@hono/node-server';
import { createApp } from './routes.js';
import { initDb } from './db.js';
import { validateTokensOnStartup } from './auth.js';

const PORT = parseInt(process.env.PORT || '3000', 10);

try {
  validateTokensOnStartup();
} catch (err) {
  console.error('Startup failed:', err.message);
  process.exit(1);
}

initDb();

const app = createApp();

console.log(`glass inbox listening on port ${PORT}`);
serve({ fetch: app.fetch, port: PORT });
