import { readFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const XAI_OAUTH_PATH = process.env.XAI_OAUTH_PATH || '/data/secrets/oauth.json';
const GROK_AUTH_PATH = process.env.GROK_AUTH_PATH || join(homedir(), '.grok', 'auth.json');

let cachedCredential = null;
let credentialSource = null;

function loadJsonFile(path) {
  try {
    if (!existsSync(path)) return null;
    const content = readFileSync(path, 'utf-8');
    return JSON.parse(content);
  } catch {
    return null;
  }
}

export function resolveBearer() {
  if (cachedCredential) {
    return { bearer: cachedCredential, source: credentialSource };
  }

  const xaiOauth = loadJsonFile(XAI_OAUTH_PATH);
  if (xaiOauth && xaiOauth.access_token) {
    cachedCredential = xaiOauth.access_token;
    credentialSource = 'xai_oauth';
    return { bearer: cachedCredential, source: credentialSource };
  }

  const grokAuth = loadJsonFile(GROK_AUTH_PATH);
  if (grokAuth && grokAuth.auth_token) {
    cachedCredential = grokAuth.auth_token;
    credentialSource = 'grok_build';
    return { bearer: cachedCredential, source: credentialSource };
  }

  return { bearer: null, source: null };
}

export function hasCredential() {
  const { bearer } = resolveBearer();
  return bearer !== null;
}

export function clearCredentialCache() {
  cachedCredential = null;
  credentialSource = null;
}
