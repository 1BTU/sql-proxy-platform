CREATE TABLE IF NOT EXISTS admin_user (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    must_change_password INTEGER NOT NULL DEFAULT 1,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS proxy_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    db_type TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    database_name TEXT NOT NULL,
    schema_name TEXT,
    oracle_connection_mode TEXT,
    username TEXT NOT NULL,
    password_cipher TEXT NOT NULL,
    max_rows INTEGER NOT NULL DEFAULT 1000,
    query_timeout_seconds INTEGER NOT NULL DEFAULT 30,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    config_version INTEGER NOT NULL DEFAULT 1,
    tested_version INTEGER,
    last_test_message TEXT,
    last_test_at TEXT,
    token_hash TEXT,
    token_cipher TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_proxy_token_hash
    ON proxy_config(token_hash) WHERE token_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS proxy_token (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    proxy_id INTEGER NOT NULL,
    remark TEXT NOT NULL DEFAULT '',
    token_hash TEXT NOT NULL UNIQUE,
    token_cipher TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (proxy_id) REFERENCES proxy_config(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_proxy_token_proxy_id ON proxy_token(proxy_id);

-- 无损迁移旧版本中保存在 proxy_config 上的单 Token。
INSERT OR IGNORE INTO proxy_token(proxy_id, remark, token_hash, token_cipher, created_at, updated_at)
SELECT id, '旧版本迁移 Token', token_hash, token_cipher, created_at, updated_at
FROM proxy_config
WHERE token_hash IS NOT NULL AND token_cipher IS NOT NULL;
