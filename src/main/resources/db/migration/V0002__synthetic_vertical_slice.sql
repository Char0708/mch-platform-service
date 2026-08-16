CREATE TABLE synthetic_account (
    account_id VARCHAR(32) NOT NULL,
    username_normalized VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    profile_status VARCHAR(16) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (account_id),
    UNIQUE KEY uq_synthetic_account_username (username_normalized)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE synthetic_session (
    session_id VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_synthetic_session_account
        FOREIGN KEY (account_id) REFERENCES synthetic_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE health_subject (
    subject_id VARCHAR(32) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    synthetic_alias VARCHAR(34) NOT NULL,
    validity_status VARCHAR(16) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE account_subject_relation (
    relation_id VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    subject_id VARCHAR(32) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    valid_from TIMESTAMP(6) NOT NULL,
    valid_to TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (relation_id),
    UNIQUE KEY uq_account_active_relation (account_id, relation_type, status),
    UNIQUE KEY uq_subject_active_relation (subject_id, relation_type, status),
    CONSTRAINT fk_account_relation_account
        FOREIGN KEY (account_id) REFERENCES synthetic_account (account_id),
    CONSTRAINT fk_account_relation_subject
        FOREIGN KEY (subject_id) REFERENCES health_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE explicit_stage_profile (
    subject_id VARCHAR(32) NOT NULL,
    stage_code VARCHAR(64) NOT NULL,
    stage_business_time TIMESTAMP(6) NOT NULL,
    stage_source_type VARCHAR(32) NOT NULL,
    validity_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (subject_id),
    CONSTRAINT fk_stage_profile_subject
        FOREIGN KEY (subject_id) REFERENCES health_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weight_record (
    record_id VARCHAR(32) NOT NULL,
    subject_id VARCHAR(32) NOT NULL,
    record_type VARCHAR(16) NOT NULL,
    value_text VARCHAR(8) NOT NULL,
    value_decimal DECIMAL(5,2) NOT NULL,
    unit VARCHAR(8) NOT NULL,
    business_time TIMESTAMP(6) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    validity_status VARCHAR(16) NOT NULL,
    semantic_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (record_id),
    UNIQUE KEY uq_weight_semantic_fingerprint (semantic_fingerprint),
    KEY ix_weight_subject_timeline (subject_id, business_time, created_at, record_id),
    CONSTRAINT fk_weight_subject
        FOREIGN KEY (subject_id) REFERENCES health_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_item (
    content_id VARCHAR(32) NOT NULL,
    current_published_version_id VARCHAR(32) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_version (
    version_id VARCHAR(32) NOT NULL,
    content_id VARCHAR(32) NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(80) NOT NULL,
    body TEXT NOT NULL,
    target_stage_code VARCHAR(64) NOT NULL,
    content_basis VARCHAR(32) NOT NULL,
    synthetic_display_label VARCHAR(64) NOT NULL,
    professional_review_status VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    author_actor_id VARCHAR(32) NOT NULL,
    reviewer_actor_id VARCHAR(32) NULL,
    review_decision_code VARCHAR(64) NULL,
    submitted_at TIMESTAMP(6) NULL,
    reviewed_at TIMESTAMP(6) NULL,
    published_at TIMESTAMP(6) NULL,
    revision INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (version_id),
    UNIQUE KEY uq_content_version_number (content_id, version_number),
    CONSTRAINT fk_content_version_item
        FOREIGN KEY (content_id) REFERENCES content_item (content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_metric_event (
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    content_id VARCHAR(32) NOT NULL,
    version_id VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_metric_event_content
        FOREIGN KEY (content_id) REFERENCES content_item (content_id),
    CONSTRAINT fk_metric_event_version
        FOREIGN KEY (version_id) REFERENCES content_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_metric_aggregate (
    content_id VARCHAR(32) NOT NULL,
    version_id VARCHAR(32) NOT NULL,
    total_exposure_count BIGINT NOT NULL,
    total_click_count BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (content_id, version_id),
    CONSTRAINT fk_metric_aggregate_content
        FOREIGN KEY (content_id) REFERENCES content_item (content_id),
    CONSTRAINT fk_metric_aggregate_version
        FOREIGN KEY (version_id) REFERENCES content_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_record (
    actor_scope VARCHAR(96) NOT NULL,
    endpoint_scope VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (actor_scope, endpoint_scope, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE synthetic_audit_event (
    audit_id VARCHAR(32) NOT NULL,
    event_name VARCHAR(64) NOT NULL,
    actor_id VARCHAR(32) NULL,
    actor_role VARCHAR(32) NULL,
    action_name VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32) NULL,
    resource_id VARCHAR(64) NULL,
    result_status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NULL,
    decision_code VARCHAR(64) NULL,
    metric_event_type VARCHAR(32) NULL,
    trace_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (audit_id),
    KEY ix_audit_event_name (event_name, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
