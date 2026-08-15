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
  it('allows phone token to GET (poll for ashleigh replies)', async () => {
    const res = await app.request('/v0/messages', {
      headers: { Authorization: `Bearer ${PHONE_TOKEN}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.messages).toEqual([]);
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
