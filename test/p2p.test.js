import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { createP2PNode, stopP2PNode, isPeerAllowed, allowPeer, getNodeInfo, getPairingPayload } from '../src/p2p.js';
import { initDb, closeDb } from '../src/db.js';

const PHONE_TOKEN = 'test-phone-token';
const PANE_TOKEN = 'test-pane-token';
const TEST_CODE = 'ABC123';

process.env.GLASS_PHONE_TOKEN = PHONE_TOKEN;
process.env.GLASS_PANE_TOKEN = PANE_TOKEN;

describe('P2P Node', () => {
  beforeAll(() => {
    initDb(':memory:');
  });

  afterAll(async () => {
    await stopP2PNode();
    closeDb();
  });

  beforeEach(async () => {
    await stopP2PNode();
  });

  it('uses provided pair code', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      code: TEST_CODE,
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const info = getNodeInfo();
    expect(info.pairCode).toBe(TEST_CODE);
    expect(info.pairingComplete).toBe(false);
    expect(info.peerId).toBeDefined();
    expect(info.addrs.length).toBeGreaterThan(0);
  });

  it('generates pair code if not provided', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const info = getNodeInfo();
    expect(info.pairCode).toBeDefined();
    expect(info.pairCode.length).toBe(6);
  });

  it('rejects pair code shorter than 6 chars', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await expect(createP2PNode({
      code: 'ABC',
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    })).rejects.toThrow('Pair code must be at least 6 characters');
  });

  it('generates glass-pair/v0 payload', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      code: TEST_CODE,
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const payload = getPairingPayload();
    expect(payload.v).toBe(0);
    expect(payload.peer).toBeDefined();
    expect(payload.addrs).toBeInstanceOf(Array);
    expect(payload.addrs.length).toBeGreaterThan(0);
    expect(payload.proto).toBe('/glass/inbox/v0');
    expect(payload.code).toBe(TEST_CODE);
  });

  it('initially has no allowed peers', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      code: TEST_CODE,
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const info = getNodeInfo();
    expect(info.allowedPeers).toEqual([]);
    expect(info.pairingComplete).toBe(false);
  });

  it('can manually allowlist a peer', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      code: TEST_CODE,
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const fakePeerId = { toString: () => '12D3KooWFakePeerId123' };
    expect(isPeerAllowed(fakePeerId)).toBe(false);
    
    allowPeer(fakePeerId);
    expect(isPeerAllowed(fakePeerId)).toBe(true);
    
    const info = getNodeInfo();
    expect(info.allowedPeers).toContain('12D3KooWFakePeerId123');
  });

  it('rejects peers that are not allowlisted via isPeerAllowed', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      code: TEST_CODE,
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const unknownPeer = { toString: () => '12D3KooWUnknownPeer456' };
    expect(isPeerAllowed(unknownPeer)).toBe(false);
  });

  it('listens on specified port', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      code: TEST_CODE,
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const info = getNodeInfo();
    expect(info.addrs.some(a => a.includes('/tcp/'))).toBe(true);
  });
});

describe('Inbox Handler', () => {
  beforeAll(() => {
    initDb(':memory:');
  });

  afterAll(() => {
    closeDb();
  });

  it('returns health check without auth', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'GET',
      path: '/v0/health',
      headers: {},
    });
    
    expect(response.status).toBe(200);
    expect(response.body).toEqual({ ok: true });
  });

  it('returns 401 without token for authenticated endpoints', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'GET',
      path: '/v0/replies',
      headers: {},
    });
    
    expect(response.status).toBe(401);
  });

  it('returns 401 with invalid token', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'GET',
      path: '/v0/replies',
      headers: { Authorization: 'Bearer invalid-token' },
    });
    
    expect(response.status).toBe(401);
  });

  it('allows phone token to GET /v0/replies', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'GET',
      path: '/v0/replies',
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    
    expect(response.status).toBe(200);
    expect(response.body.messages).toBeDefined();
  });

  it('returns 403 for phone token on GET /v0/messages', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'GET',
      path: '/v0/messages',
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    
    expect(response.status).toBe(403);
  });

  it('allows pane token to GET /v0/messages', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'GET',
      path: '/v0/messages',
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    
    expect(response.status).toBe(200);
    expect(response.body.messages).toBeDefined();
  });

  it('allows phone to POST /v0/messages as jamie', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'POST',
      path: '/v0/messages',
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
      body: {
        from: 'jamie',
        text: 'Hello from P2P test',
        at: '2024-01-15T10:30:00Z',
      },
    });
    
    expect(response.status).toBe(201);
    expect(response.body.from).toBe('jamie');
    expect(response.body.id).toBeDefined();
  });

  it('returns 403 for phone posting as ashleigh', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const response = await handleInboxRequest({
      method: 'POST',
      path: '/v0/messages',
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
      body: {
        from: 'ashleigh',
        text: 'Should fail',
        at: '2024-01-15T10:30:00Z',
      },
    });
    
    expect(response.status).toBe(403);
  });
});
