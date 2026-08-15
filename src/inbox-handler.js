import { createMessage, listMessages, listReplies, getMessageById } from './db.js';
import { getTokenRole, canPost, canGetMessages, canGetReplies } from './auth.js';

export async function handleInboxRequest(request) {
  const { method, path, headers = {}, body } = request;
  
  const authHeader = headers['Authorization'] || headers['authorization'];
  const token = authHeader?.replace(/^Bearer\s+/i, '');
  const role = getTokenRole(token);
  
  if (path === '/v0/health' && method === 'GET') {
    return { status: 200, body: { ok: true } };
  }
  
  if (!role) {
    return { status: 401, body: { error: 'Unauthorized' } };
  }
  
  if (path === '/v0/messages' && method === 'POST') {
    if (!body || typeof body !== 'object') {
      return { status: 400, body: { error: 'Invalid JSON body' } };
    }
    
    const errors = validateMessageBody(body);
    if (errors.length > 0) {
      return { status: 400, body: { error: 'Validation failed', details: errors } };
    }
    
    if (!canPost(role, body.from)) {
      return { status: 403, body: { error: 'Forbidden: cannot post as this sender' } };
    }
    
    const message = createMessage({
      from: body.from,
      text: body.text.trim(),
      at: body.at,
    });
    
    return { status: 201, body: message };
  }
  
  if (path === '/v0/messages' && method === 'GET') {
    if (!canGetMessages(role)) {
      return { status: 403, body: { error: 'Forbidden: cannot list messages' } };
    }
    
    const after = request.query?.after;
    const limit = parseInt(request.query?.limit || '100', 10);
    
    if (isNaN(limit) || limit < 1) {
      return { status: 400, body: { error: "'limit' must be a positive integer" } };
    }
    
    const messages = listMessages({ after, limit });
    return { status: 200, body: { messages } };
  }
  
  if (path === '/v0/replies' && method === 'GET') {
    if (!canGetReplies(role)) {
      return { status: 403, body: { error: 'Forbidden: cannot list replies' } };
    }
    
    const after = request.query?.after;
    const limit = parseInt(request.query?.limit || '50', 10);
    
    if (isNaN(limit) || limit < 1) {
      return { status: 400, body: { error: "'limit' must be a positive integer" } };
    }
    
    const messages = listReplies({ after, limit });
    return { status: 200, body: { messages } };
  }
  
  return { status: 404, body: { error: 'Not found' } };
}

const VALID_SENDERS = ['jamie', 'ashleigh'];

function isValidIso8601(str) {
  if (typeof str !== 'string') return false;
  const date = new Date(str);
  return !isNaN(date.getTime());
}

function validateMessageBody(body) {
  const errors = [];
  
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
