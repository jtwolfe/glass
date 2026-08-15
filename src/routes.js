import { Hono } from 'hono';
import { createMessage, listMessages, listReplies, getMessageById } from './db.js';
import { authMiddleware, canPost, canGetMessages, canGetReplies, canUseStt, canGetAudio, getTokenRole } from './auth.js';
import { speechToText, textToSpeech } from './media.js';
import { getInvite } from './p2p.js';

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
  
  app.get('/v0/pair', (c) => {
    const authHeader = c.req.header('Authorization');
    const token = authHeader?.match(/^Bearer\s+(.+)$/i)?.[1] || null;
    const role = getTokenRole(token);
    
    const remoteIp = c.req.header('x-forwarded-for')?.split(',')[0]?.trim() 
      || c.req.header('x-real-ip')
      || c.env?.incoming?.socket?.remoteAddress
      || '';
    const isLoopback = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remoteIp);
    
    if (role === 'phone') {
      return c.json({ error: 'Forbidden: phone token cannot access pair endpoint' }, 403);
    }
    
    if (role !== 'pane' && !isLoopback) {
      return c.json({ error: 'Unauthorized' }, 401);
    }
    
    const invite = getInvite();
    if (!invite) {
      return c.json({ error: 'No active invite' }, 404);
    }
    
    const expDate = new Date(invite.exp);
    if (expDate <= new Date()) {
      return c.json({ error: 'Invite expired' }, 410);
    }
    
    return c.json(invite);
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
  
  api.get('/replies/:id/audio', async (c) => {
    const role = c.get('role');
    
    if (!canGetAudio(role)) {
      return c.json({ error: 'Forbidden: cannot get audio' }, 403);
    }
    
    const id = c.req.param('id');
    const message = getMessageById(id);
    
    if (!message) {
      return c.json({ error: 'Message not found' }, 404);
    }
    
    if (message.from !== 'ashleigh') {
      return c.json({ error: 'Forbidden: audio only available for ashleigh messages' }, 403);
    }
    
    const result = await textToSpeech(id, message.text);
    
    if (result.error === 'credential_unavailable') {
      return c.json({ error: result.error, detail: result.detail }, 503);
    }
    
    if (result.error) {
      return c.json({ error: result.error, detail: result.detail }, 502);
    }
    
    return new Response(result.audioBuffer, {
      status: 200,
      headers: {
        'Content-Type': 'audio/mpeg',
        'Content-Length': result.audioBuffer.length.toString(),
        'X-Cache': result.cached ? 'HIT' : 'MISS',
      },
    });
  });
  
  api.post('/stt', async (c) => {
    const role = c.get('role');
    
    if (!canUseStt(role)) {
      return c.json({ error: 'Forbidden: cannot use STT' }, 403);
    }
    
    const contentType = c.req.header('Content-Type') || '';
    
    if (!contentType.includes('multipart/form-data')) {
      return c.json({ error: 'Content-Type must be multipart/form-data' }, 400);
    }
    
    let formData;
    try {
      formData = await c.req.formData();
    } catch {
      return c.json({ error: 'Invalid multipart form data' }, 400);
    }
    
    const file = formData.get('file');
    if (!file || !(file instanceof File)) {
      return c.json({ error: "Missing 'file' in form data" }, 400);
    }
    
    const audioBuffer = Buffer.from(await file.arrayBuffer());
    const result = await speechToText(audioBuffer, file.type);
    
    if (result.error === 'credential_unavailable') {
      return c.json({ error: result.error, detail: result.detail }, 503);
    }
    
    if (result.error) {
      return c.json({ error: result.error, detail: result.detail }, 502);
    }
    
    return c.json({ text: result.text });
  });
  
  app.route('/v0', api);
  
  return app;
}
