CREATE TABLE tx_log (
  tx_id VARCHAR(64) PRIMARY KEY,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  status VARCHAR(20),
  payload CLOB,
  last_update TIMESTAMP
);
