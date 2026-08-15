function getPhoneToken() {
  return process.env.GLASS_PHONE_TOKEN;
}

function getPaneToken() {
  return process.env.GLASS_PANE_TOKEN;
}

export function validateTokensOnStartup() {
  const errors = [];
  const phoneToken = getPhoneToken();
  const paneToken = getPaneToken();
  
  if (!phoneToken || phoneToken.trim() === '') {
    errors.push('GLASS_PHONE_TOKEN must be set and non-empty');
  }
  if (!paneToken || paneToken.trim() === '') {
    errors.push('GLASS_PANE_TOKEN must be set and non-empty');
  }
  if (errors.length > 0) {
    throw new Error(`Auth configuration error:\n${errors.join('\n')}`);
  }
}

export function getTokenRole(token) {
  if (!token) return null;
  if (token === getPhoneToken()) return 'phone';
  if (token === getPaneToken()) return 'pane';
  return null;
}

function extractToken(c) {
  const authHeader = c.req.header('Authorization');
  if (!authHeader) return null;
  
  const match = authHeader.match(/^Bearer\s+(.+)$/i);
  return match ? match[1] : null;
}

export function authMiddleware() {
  return async (c, next) => {
    const token = extractToken(c);
    const role = getTokenRole(token);
    
    if (!role) {
      return c.json({ error: 'Unauthorized' }, 401);
    }
    
    c.set('role', role);
    await next();
  };
}

export function canPost(role, from) {
  if (role === 'phone' && from === 'jamie') return true;
  if (role === 'pane' && from === 'ashleigh') return true;
  return false;
}

export function canGetMessages(role) {
  return role === 'pane';
}

export function canGetReplies(role) {
  return role === 'phone';
}

export function canUseStt(role) {
  return role === 'phone';
}

export function canGetAudio(role) {
  return role === 'phone';
}
