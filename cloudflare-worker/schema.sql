CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT 'Bố Mẹ',
  default_pin TEXT NOT NULL DEFAULT '1601',
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  pairing_code TEXT NOT NULL UNIQUE,
  owner_user_id INTEGER,
  device_name TEXT NOT NULL,
  device_type TEXT NOT NULL DEFAULT 'tablet',
  app_flavor TEXT NOT NULL DEFAULT 'guard',
  model TEXT NOT NULL DEFAULT '',
  pin_code TEXT NOT NULL DEFAULT '1601',
  last_seen_at INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_devices_owner ON devices(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_devices_pairing ON devices(pairing_code);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
