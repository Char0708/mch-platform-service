CREATE TABLE engineering_schema_probe (
    probe_id BIGINT NOT NULL,
    environment_tag VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (probe_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
