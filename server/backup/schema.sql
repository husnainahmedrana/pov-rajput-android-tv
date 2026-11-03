-- POV Rajput account backup. The server never sees passwords, recovery keys or the data key:
-- `secrets` is end-to-end encrypted on the device; only `settings` can be opened with the server-held settings key.
CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,            -- SHA-256 of the normalised email
  email TEXT NOT NULL,            -- only used to send verification and password-reset codes
  auth_hash TEXT NOT NULL,        -- SHA-256 of the login token the app derives from the password
  wrapped_pw TEXT NOT NULL,       -- data key, locked with the password (opened on the device only)
  wrapped_rk TEXT NOT NULL,       -- data key, locked with the recovery key (opened on the device only)
  settings_key TEXT NOT NULL,     -- key for the settings layer (Home rows, preferences, local progress)
  settings TEXT NOT NULL DEFAULT '',
  secrets TEXT NOT NULL DEFAULT '',
  created INTEGER NOT NULL,
  updated INTEGER NOT NULL,
  seen INTEGER NOT NULL,
  failed INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0
);

-- Six-digit email codes, stored hashed. purpose: signup | reset
CREATE TABLE IF NOT EXISTS codes (
  id TEXT NOT NULL,
  purpose TEXT NOT NULL,
  code_hash TEXT NOT NULL,
  sent INTEGER NOT NULL,
  expires INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (id, purpose)
);

-- Short-lived permission to set a new password after a verified reset code.
CREATE TABLE IF NOT EXISTS resets (
  token_hash TEXT PRIMARY KEY,
  id TEXT NOT NULL,
  expires INTEGER NOT NULL
);

-- Phone sign-in relay: the phone encrypts to a key that only the TV holds (it travels in the QR's #fragment).
CREATE TABLE IF NOT EXISTS links (
  id TEXT PRIMARY KEY,
  payload TEXT NOT NULL DEFAULT '',
  expires INTEGER NOT NULL
);

-- Feature requests and bug reports from feature.rajput.cc / bug.rajput.cc. Also emailed to FEEDBACK_TO (a secret).
CREATE TABLE IF NOT EXISTS feedback (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,             -- feature | bug
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  email TEXT NOT NULL DEFAULT '', -- optional, for a reply
  app TEXT NOT NULL DEFAULT '',   -- app version, device and Android version, as shown on the form
  ip_hash TEXT NOT NULL,          -- SHA-256 of the sender's IP, only for the hourly limit
  created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS feedback_ip ON feedback (ip_hash, created);
