import { describe, it, expect, beforeAll, afterAll, beforeEach, vi } from 'vitest';
import { createP2PNode, stopP2PNode, isPeerAllowed, allowPeer, getNodeInfo, getInvite, getPairTopic } from '../src/p2p.js';
import { initDb, closeDb } from '../src/db.js';

const PHONE_TOKEN = 'test-phone-token';
const PANE_TOKEN = 'test-pane-token';

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

  it('generates invite with correct format', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const invite = getInvite();
    expect(invite.v).toBe(0);
    expect(invite.peer).toBeDefined();
    expect(invite.addrs).toBeInstanceOf(Array);
    expect(invite.addrs.length).toBeGreaterThan(0);
    expect(invite.proto).toBe('/glass/inbox/v0');
    expect(invite.code).toBeDefined();
    expect(invite.psk).toBeDefined();
    expect(invite.exp).toBeDefined();
  });

  it('generates 8-char Crockford code', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const invite = getInvite();
    expect(invite.code.length).toBe(8);
    expect(invite.code).toMatch(/^[ABCDEFGHJKMNPQRSTVWXYZ2345679]+$/);
  });

  it('generates 64-char hex PSK (32 bytes)', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const invite = getInvite();
    expect(invite.psk.length).toBe(64);
    expect(invite.psk).toMatch(/^[0-9a-f]+$/);
  });

  it('sets exp to ~15 minutes in future', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const before = Date.now();
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });
    const after = Date.now();

    const invite = getInvite();
    const expTime = new Date(invite.exp).getTime();
    
    const minExp = before + 14 * 60 * 1000;
    const maxExp = after + 16 * 60 * 1000;
    
    expect(expTime).toBeGreaterThan(minExp);
    expect(expTime).toBeLessThan(maxExp);
  });

  it('initially has no allowed peers', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
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

  it('rejects peers not allowlisted via isPeerAllowed', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const unknownPeer = { toString: () => '12D3KooWUnknownPeer456' };
    expect(isPeerAllowed(unknownPeer)).toBe(false);
  });

  it('listens on TCP', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const info = getNodeInfo();
    expect(info.addrs.some(a => a.includes('/tcp/'))).toBe(true);
  });

  it('includes invite in node info', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const info = getNodeInfo();
    expect(info.invite).toBeDefined();
    expect(info.invite.code).toBeDefined();
    expect(info.invite.psk).toBeDefined();
  });

  it('does not set up pubsub topic without relay', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    expect(getPairTopic()).toBeNull();
  });
});

describe('P2P Pubsub Rendezvous', () => {
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

  it('sets up pair topic when relay configured', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const subscribeCalls = [];
    const publishCalls = [];
    
    const createMockPubsub = () => {
      return () => ({
        subscribe: (topic) => { subscribeCalls.push(topic); },
        unsubscribe: vi.fn(),
        publish: (topic, data) => { 
          publishCalls.push({ topic, data }); 
          return Promise.resolve();
        },
        getTopics: () => [],
        getPeers: () => [],
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        start: () => Promise.resolve(),
        stop: () => Promise.resolve(),
      });
    };
    
    await createP2PNode({
      listenPort: 0,
      relayAddrs: ['/ip4/127.0.0.1/tcp/4002/p2p/12D3KooWFakeRelayPeerId'],
      onInboxRequest: handleInboxRequest,
      pubsubImpl: createMockPubsub(),
    });

    const invite = getInvite();
    const expectedTopic = `/glass/pair/${invite.code}`;
    
    expect(subscribeCalls).toContain(expectedTopic);
    expect(getPairTopic()).toBe(expectedTopic);
  });

  it('publishes invite JSON on the pair topic', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    const publishCalls = [];
    
    const createMockPubsub = () => {
      return () => ({
        subscribe: vi.fn(),
        unsubscribe: vi.fn(),
        publish: (topic, data) => { 
          publishCalls.push({ topic, data }); 
          return Promise.resolve();
        },
        getTopics: () => [],
        getPeers: () => [],
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        start: () => Promise.resolve(),
        stop: () => Promise.resolve(),
      });
    };
    
    await createP2PNode({
      listenPort: 0,
      relayAddrs: ['/ip4/127.0.0.1/tcp/4002/p2p/12D3KooWFakeRelayPeerId'],
      onInboxRequest: handleInboxRequest,
      pubsubImpl: createMockPubsub(),
    });

    await new Promise(resolve => setTimeout(resolve, 1500));

    const invite = getInvite();
    const expectedTopic = `/glass/pair/${invite.code}`;
    
    expect(publishCalls.length).toBeGreaterThan(0);
    const call = publishCalls.find(c => c.topic === expectedTopic);
    expect(call).toBeDefined();
    
    const publishedInvite = JSON.parse(new TextDecoder().decode(call.data));
    expect(publishedInvite.v).toBe(0);
    expect(publishedInvite.code).toBe(invite.code);
    expect(publishedInvite.psk).toBe(invite.psk);
    expect(publishedInvite.peer).toBe(invite.peer);
    expect(publishedInvite.proto).toBe('/glass/inbox/v0');
  });

  it('does not set up pubsub without relay addrs', async () => {
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });

    const info = getNodeInfo();
    expect(info.pairTopic).toBeNull();
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
