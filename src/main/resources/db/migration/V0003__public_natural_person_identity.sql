CREATE TABLE natural_person (
    person_id VARCHAR(32) NOT NULL,
    identity_provider VARCHAR(64) NOT NULL,
    external_person_reference VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    profile_status VARCHAR(16) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (person_id),
    UNIQUE KEY uq_natural_person_provider_reference (identity_provider, external_person_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE public_person_session (
    session_id VARCHAR(32) NOT NULL,
    person_id VARCHAR(32) NOT NULL,
    identity_provider VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT fk_public_person_session_person
        FOREIGN KEY (person_id) REFERENCES natural_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE person_subject_relation (
    relation_id VARCHAR(32) NOT NULL,
    person_id VARCHAR(32) NOT NULL,
    subject_id VARCHAR(32) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    valid_from TIMESTAMP(6) NOT NULL,
    valid_to TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (relation_id),
    UNIQUE KEY uq_person_active_relation (person_id, relation_type, status),
    UNIQUE KEY uq_person_subject_active_relation (subject_id, relation_type, status),
    CONSTRAINT fk_person_relation_person
        FOREIGN KEY (person_id) REFERENCES natural_person (person_id),
    CONSTRAINT fk_person_relation_subject
        FOREIGN KEY (subject_id) REFERENCES health_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO natural_person
(person_id, identity_provider, external_person_reference, status, profile_status, synthetic, created_at, updated_at)
VALUES
('00000000000000000000000000000001', 'R1_SYNTHETIC_FIXTURE', 'r1-default-natural-person',
 'ACTIVE', 'REQUIRED', TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
