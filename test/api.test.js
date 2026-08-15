import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { createApp } from '../src/routes.js';
import { initDb, closeDb, getDb } from '../src/db.js';

const PHONE_TOKEN = 'test-phone-token';
const PANE_TOKEN = 'test-pane-token';

process.env.GLASS_PHONE_TOKEN = PHONE_TOKEN;
process.env.GLASS_PANE_TOKEN = PANE_TOKEN;

let app;

beforeAll(() => {
  initDb(':memory:');
  app = createApp();
});

afterAll(() => {
  closeDb();
});

beforeEach(() => {
  getDb().exec('DELETE FROM messages');
});

describe('GET /v0/health', () => {
  it('returns ok without auth', async () => {
    const res = await app.request('/v0/health');
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toEqual({ ok: true });
  });
});

describe('Authentication', () => {
  it('returns 401 when no token provided', async () => {
    const res = await app.request('/v0/messages', { method: 'POST' });
    expect(res.status).toBe(401);
  });

  it('returns 401 with invalid token', async () => {
    const res = await app.request('/v0/messages', {
      method: 'POST',
      headers: { Authorization: 'Bearer invalid-token' },
    });
    expect(res.status).toBe(401);
  });

  it('returns 401 with malformed Authorization header', async () => {
    const res = await app.request('/v0/messages', {
      method: 'POST',
      headers: { Authorization: 'Basic abc123' },
    });
    expect(res.status).toBe(401);
  });
});

describe('POST /v0/messages', () => {
  describe('Authorization (role-based)', () => {
    it('phone token can post as jamie', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'jamie',
          text: 'Hello Ashleigh',
          at: '2024-01-15T10:30:00Z',
        }),
      });
      expect(res.status).toBe(201);
    });

    it('phone token cannot post as ashleigh (403)', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'ashleigh',
          text: 'Hello',
          at: '2024-01-15T10:30:00Z',
        }),
      });
      expect(res.status).toBe(403);
    });

    it('pane token can post as ashleigh', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PANE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'ashleigh',
          text: 'Hi Jamie',
          at: '2024-01-15T10:31:00Z',
        }),
      });
      expect(res.status).toBe(201);
    });

    it('pane token cannot post as jamie (403)', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PANE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'jamie',
          text: 'Hello',
          at: '2024-01-15T10:30:00Z',
        }),
      });
      expect(res.status).toBe(403);
    });
  });

  describe('Validation', () => {
    it('rejects invalid from value', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'unknown',
          text: 'Hello',
          at: '2024-01-15T10:30:00Z',
        }),
      });
      expect(res.status).toBe(400);
      const body = await res.json();
      expect(body.details).toContain("'from' must be one of: jamie, ashleigh");
    });

    it('rejects empty text', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'jamie',
          text: '',
          at: '2024-01-15T10:30:00Z',
        }),
      });
      expect(res.status).toBe(400);
      const body = await res.json();
      expect(body.details).toContain("'text' must be a non-empty string");
    });

    it('rejects whitespace-only text', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'jamie',
          text: '   ',
          at: '2024-01-15T10:30:00Z',
        }),
      });
      expect(res.status).toBe(400);
    });

    it('rejects invalid at timestamp', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'jamie',
          text: 'Hello',
          at: 'not-a-date',
        }),
      });
      expect(res.status).toBe(400);
      const body = await res.json();
      expect(body.details).toContain("'at' must be a valid ISO-8601 timestamp");
    });

    it('rejects invalid JSON body', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: 'not json',
      });
      expect(res.status).toBe(400);
    });
  });

  describe('Success', () => {
    it('returns 201 with id, from, text, at', async () => {
      const res = await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'jamie',
          text: 'Hello Ashleigh',
          at: '2024-01-15T10:30:00Z',
        }),
      });
      expect(res.status).toBe(201);
      const body = await res.json();
      expect(body.id).toBeDefined();
      expect(body.from).toBe('jamie');
      expect(body.text).toBe('Hello Ashleigh');
      expect(body.at).toBe('2024-01-15T10:30:00Z');
    });
  });
});

describe('GET /v0/messages', () => {
  it('returns 403 for phone token', async () => {
    const res = await app.request('/v0/messages', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(403);
  });

  it('returns empty array when no messages', async () => {
    const res = await app.request('/v0/messages', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toEqual([]);
  });

  it('returns messages ordered by at, then id', async () => {
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'jamie',
        text: 'Second',
        at: '2024-01-15T10:31:00Z',
      }),
    });
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'jamie',
        text: 'First',
        at: '2024-01-15T10:30:00Z',
      }),
    });

    const res = await app.request('/v0/messages', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toHaveLength(2);
    expect(body.messages[0].text).toBe('First');
    expect(body.messages[1].text).toBe('Second');
  });

  it('supports after parameter', async () => {
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'jamie',
        text: 'First',
        at: '2024-01-15T10:30:00Z',
      }),
    });
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'jamie',
        text: 'Second',
        at: '2024-01-15T10:31:00Z',
      }),
    });

    const res = await app.request('/v0/messages?after=2024-01-15T10:30:00Z', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toHaveLength(1);
    expect(body.messages[0].text).toBe('Second');
  });

  it('supports limit parameter', async () => {
    for (let i = 0; i < 5; i++) {
      await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PHONE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'jamie',
          text: `Message ${i}`,
          at: `2024-01-15T10:3${i}:00Z`,
        }),
      });
    }

    const res = await app.request('/v0/messages?limit=2', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toHaveLength(2);
  });

  it('rejects invalid limit', async () => {
    const res = await app.request('/v0/messages?limit=-1', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(400);
  });
});

describe('GET /v0/replies', () => {
  it('returns 403 for pane token', async () => {
    const res = await app.request('/v0/replies', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(403);
  });

  it('allows phone token', async () => {
    const res = await app.request('/v0/replies', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toEqual([]);
  });

  it('returns only ashleigh messages', async () => {
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'jamie',
        text: 'Hello Ashleigh',
        at: '2024-01-15T10:30:00Z',
      }),
    });
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PANE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'ashleigh',
        text: 'Hi Jamie',
        at: '2024-01-15T10:31:00Z',
      }),
    });
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'jamie',
        text: 'How are you?',
        at: '2024-01-15T10:32:00Z',
      }),
    });

    const res = await app.request('/v0/replies', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toHaveLength(1);
    expect(body.messages[0].from).toBe('ashleigh');
    expect(body.messages[0].text).toBe('Hi Jamie');
  });

  it('supports after parameter (exclusive on at)', async () => {
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PANE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'ashleigh',
        text: 'First reply',
        at: '2024-01-15T10:30:00Z',
      }),
    });
    await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PANE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'ashleigh',
        text: 'Second reply',
        at: '2024-01-15T10:31:00Z',
      }),
    });

    const res = await app.request('/v0/replies?after=2024-01-15T10:30:00Z', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toHaveLength(1);
    expect(body.messages[0].text).toBe('Second reply');
  });

  it('supports limit parameter', async () => {
    for (let i = 0; i < 5; i++) {
      await app.request('/v0/messages', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${PANE_TOKEN}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: 'ashleigh',
          text: `Reply ${i}`,
          at: `2024-01-15T10:3${i}:00Z`,
        }),
      });
    }

    const res = await app.request('/v0/replies?limit=2', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toHaveLength(2);
  });

  it('rejects invalid limit', async () => {
    const res = await app.request('/v0/replies?limit=-1', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(400);
  });
});

describe('POST /v0/stt', () => {
  it('returns 401 without auth', async () => {
    const res = await app.request('/v0/stt', { method: 'POST' });
    expect(res.status).toBe(401);
  });

  it('returns 403 for pane token', async () => {
    const formData = new FormData();
    formData.append('file', new Blob(['test'], { type: 'audio/wav' }), 'test.wav');
    
    const res = await app.request('/v0/stt', {
      method: 'POST',
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
      body: formData,
    });
    expect(res.status).toBe(403);
  });

  it('returns 400 without multipart form data', async () => {
    const res = await app.request('/v0/stt', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({}),
    });
    expect(res.status).toBe(400);
  });

  it('returns 503 when no grok credential available', async () => {
    const formData = new FormData();
    formData.append('file', new Blob(['test audio data'], { type: 'audio/wav' }), 'test.wav');
    
    const res = await app.request('/v0/stt', {
      method: 'POST',
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
      body: formData,
    });
    expect(res.status).toBe(503);
    const body = await res.json();
    expect(body.error).toBe('credential_unavailable');
  });
});

describe('GET /v0/replies/:id/audio', () => {
  it('returns 401 without auth', async () => {
    const res = await app.request('/v0/replies/some-id/audio');
    expect(res.status).toBe(401);
  });

  it('returns 403 for pane token', async () => {
    const res = await app.request('/v0/replies/some-id/audio', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(403);
  });

  it('returns 404 for non-existent message', async () => {
    const res = await app.request('/v0/replies/non-existent-id/audio', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(404);
  });

  it('returns 403 for jamie messages (ashleigh only)', async () => {
    const createRes = await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PHONE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'jamie',
        text: 'Hello',
        at: '2024-01-15T10:30:00Z',
      }),
    });
    const { id } = await createRes.json();

    const res = await app.request(`/v0/replies/${id}/audio`, {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(403);
    const body = await res.json();
    expect(body.error).toBe('Forbidden: audio only available for ashleigh messages');
  });

  it('returns 503 when no grok credential available for ashleigh message', async () => {
    const createRes = await app.request('/v0/messages', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${PANE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        from: 'ashleigh',
        text: 'Hi there',
        at: '2024-01-15T10:30:00Z',
      }),
    });
    const { id } = await createRes.json();

    const res = await app.request(`/v0/replies/${id}/audio`, {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(503);
    const body = await res.json();
    expect(body.error).toBe('credential_unavailable');
  });
});

describe('GET /v0/pair (no P2P)', () => {
  it('returns 404 when no invite (P2P not started)', async () => {
    const res = await app.request('/v0/pair', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(404);
    const body = await res.json();
    expect(body.error).toBe('No active invite');
  });

  it('returns 403 for phone token', async () => {
    const res = await app.request('/v0/pair', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(403);
    const body = await res.json();
    expect(body.error).toBe('Forbidden: phone token cannot access pair endpoint');
  });

  it('returns 401 with invalid token (not loopback)', async () => {
    const res = await app.request('/v0/pair', {
      headers: { Authorization: 'Bearer invalid-token' },
    });
    expect(res.status).toBe(401);
  });

  it('returns 401 without auth (not loopback)', async () => {
    const res = await app.request('/v0/pair');
    expect(res.status).toBe(401);
  });
});

describe('GET /v0/pair (with P2P)', () => {
  let p2pApp;
  
  beforeAll(async () => {
    const { createP2PNode, stopP2PNode } = await import('../src/p2p.js');
    const { handleInboxRequest } = await import('../src/inbox-handler.js');
    
    await createP2PNode({
      listenPort: 0,
      onInboxRequest: handleInboxRequest,
    });
    
    p2pApp = createApp();
  });
  
  afterAll(async () => {
    const { stopP2PNode } = await import('../src/p2p.js');
    await stopP2PNode();
  });

  it('returns invite with pane token', async () => {
    const res = await p2pApp.request('/v0/pair', {
      headers: { Authorization: `Bearer ${PANE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.v).toBe(0);
    expect(body.peer).toBeDefined();
    expect(body.addrs).toBeInstanceOf(Array);
    expect(body.proto).toBe('/glass/inbox/v0');
    expect(body.code).toBeDefined();
    expect(body.psk).toBeDefined();
    expect(body.exp).toBeDefined();
  });

  it('returns 403 for phone token even with active invite', async () => {
    const res = await p2pApp.request('/v0/pair', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(403);
  });
});
