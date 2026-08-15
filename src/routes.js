import { Hono } from 'hono';
import { createMessage, listMessages, listReplies } from './db.js';
import { authMiddleware, canPost, canGetMessages, canGetReplies } from './auth.js';

const VALID_SENDERS = ['jamie', 'ashleigh'];

function isValidIso8601(str) {
  if (typeof str !== 'string') return false;
  const date = new Date(str);
  return !isNaN(date.getTime());
}

function validateMessageBody(body) {
  const errors = [];
  
  if (!body || typeof body !== 'object') {
    return ['Request body must be a JSON object'];
  }
  
  if (!VALID_SENDERS.includes(body.from)) {
    errors.push(`'from' must be one of: ${VALID_SENDERS.join(', ')}`);
  }
  
  if (typeof body.text !== 'string' || body.text.trim() === '') {
    errors.push("'text' must be a non-empty string");
  }
  
  if (!isValidIso8601(body.at)) {
    errors.push("'at' must be a valid ISO-8601 timestamp");
  }
  
  return errors;
}

export function createApp() {
  const app = new Hono();
  
  app.get('/v0/health', (c) => {
    return c.json({ ok: true });
  });
  
  const api = new Hono();
  api.use('*', authMiddleware());
  
  api.post('/messages', async (c) => {
    const role = c.get('role');
    
    let body;
    try {
      body = await c.req.json();
    } catch {
      return c.json({ error: 'Invalid JSON body' }, 400);
    }
    
    const errors = validateMessageBody(body);
    if (errors.length > 0) {
      return c.json({ error: 'Validation failed', details: errors }, 400);
    }
    
    if (!canPost(role, body.from)) {
      return c.json({ error: 'Forbidden: cannot post as this sender' }, 403);
    }
    
    const message = createMessage({
      from: body.from,
      text: body.text.trim(),
      at: body.at,
    });
    
    return c.json(message, 201);
  });
  
  api.get('/messages', (c) => {
    const role = c.get('role');
    
    if (!canGetMessages(role)) {
      return c.json({ error: 'Forbidden: cannot list messages' }, 403);
    }
    
    const after = c.req.query('after');
    const limitParam = c.req.query('limit');
    let limit = 100;
    
    if (limitParam) {
      limit = parseInt(limitParam, 10);
      if (isNaN(limit) || limit < 1) {
        return c.json({ error: "'limit' must be a positive integer" }, 400);
      }
    }
    
    const messages = listMessages({ after, limit });
    return c.json({ messages });
  });
  
  api.get('/replies', (c) => {
    const role = c.get('role');
    
    if (!canGetReplies(role)) {
      return c.json({ error: 'Forbidden: cannot list replies' }, 403);
    }
    
    const after = c.req.query('after');
    const limitParam = c.req.query('limit');
    let limit = 50;
    
    if (limitParam) {
      limit = parseInt(limitParam, 10);
      if (isNaN(limit) || limit < 1) {
        return c.json({ error: "'limit' must be a positive integer" }, 400);
      }
    }
    
    const messages = listReplies({ after, limit });
    return c.json({ messages });
  });
  
  app.route('/v0', api);
  
  return app;
}
