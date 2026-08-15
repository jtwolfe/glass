import Database from 'better-sqlite3';
import { randomUUID } from 'node:crypto';

let db = null;

export function initDb(dbPath = process.env.GLASS_DB_PATH || './glass.db') {
  db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  
  db.exec(`
    CREATE TABLE IF NOT EXISTS messages (
      id TEXT PRIMARY KEY,
      sender TEXT NOT NULL CHECK(sender IN ('jamie', 'ashleigh')),
      text TEXT NOT NULL CHECK(length(text) > 0),
      at TEXT NOT NULL,
      created_at TEXT DEFAULT (datetime('now'))
    )
  `);
  
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_messages_at_id ON messages(at, id)
  `);
  
  return db;
}

export function getDb() {
  if (!db) {
    throw new Error('Database not initialized. Call initDb() first.');
  }
  return db;
}

export function createMessage({ from, text, at }) {
  const id = randomUUID();
  const stmt = getDb().prepare(`
    INSERT INTO messages (id, sender, text, at)
    VALUES (?, ?, ?, ?)
  `);
  stmt.run(id, from, text, at);
  return { id, from, text, at };
}

export function listMessages({ after, limit = 100 }) {
  let query = 'SELECT id, sender as "from", text, at FROM messages';
  const params = [];
  
  if (after) {
    query += ' WHERE at > ?';
    params.push(after);
  }
  
  query += ' ORDER BY at ASC, id ASC';
  
  if (limit) {
    query += ' LIMIT ?';
    params.push(Math.min(limit, 1000));
  }
  
  return getDb().prepare(query).all(...params);
}

export function listReplies({ after, limit = 50 }) {
  let query = "SELECT id, sender as \"from\", text, at FROM messages WHERE sender = 'ashleigh'";
  const params = [];
  
  if (after) {
    query += ' AND at > ?';
    params.push(after);
  }
  
  query += ' ORDER BY at ASC, id ASC';
  
  if (limit) {
    query += ' LIMIT ?';
    params.push(Math.min(limit, 1000));
  }
  
  return getDb().prepare(query).all(...params);
}

export function getMessageById(id) {
  const stmt = getDb().prepare('SELECT id, sender as "from", text, at FROM messages WHERE id = ?');
  return stmt.get(id) || null;
}

export function closeDb() {
  if (db) {
    db.close();
    db = null;
  }
}
