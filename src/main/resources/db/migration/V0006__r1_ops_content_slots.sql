ALTER TABLE content_version
    ADD COLUMN summary_text VARCHAR(240) NULL AFTER body,
    ADD COLUMN source_reference VARCHAR(240) NULL AFTER content_basis,
    ADD COLUMN source_review_date DATE NULL AFTER source_reference,
    ADD COLUMN submitted_by_actor_id VARCHAR(32) NULL AFTER author_actor_id;

CREATE TABLE r1_content_slot (
    slot_code VARCHAR(32) NOT NULL,
    pregnancy_week INT NOT NULL,
    day_offset INT NOT NULL,
    content_type_code VARCHAR(24) NOT NULL,
    content_id VARCHAR(32) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (slot_code),
    UNIQUE KEY uq_r1_slot_coordinate (pregnancy_week, day_offset, content_type_code),
    UNIQUE KEY uq_r1_slot_content (content_id),
    CONSTRAINT fk_r1_slot_content
        FOREIGN KEY (content_id) REFERENCES content_item (content_id) ON DELETE SET NULL,
    CONSTRAINT ck_r1_slot_week CHECK (pregnancy_week = 20),
    CONSTRAINT ck_r1_slot_day CHECK (day_offset BETWEEN 0 AND 6),
    CONSTRAINT ck_r1_slot_type CHECK (content_type_code IN ('BABY_CHANGE', 'MOM_CHANGE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO r1_content_slot (slot_code, pregnancy_week, day_offset, content_type_code) VALUES
    ('P20D0_BABY_CHANGE', 20, 0, 'BABY_CHANGE'),
    ('P20D0_MOM_CHANGE', 20, 0, 'MOM_CHANGE'),
    ('P20D1_BABY_CHANGE', 20, 1, 'BABY_CHANGE'),
    ('P20D1_MOM_CHANGE', 20, 1, 'MOM_CHANGE'),
    ('P20D2_BABY_CHANGE', 20, 2, 'BABY_CHANGE'),
    ('P20D2_MOM_CHANGE', 20, 2, 'MOM_CHANGE'),
    ('P20D3_BABY_CHANGE', 20, 3, 'BABY_CHANGE'),
    ('P20D3_MOM_CHANGE', 20, 3, 'MOM_CHANGE'),
    ('P20D4_BABY_CHANGE', 20, 4, 'BABY_CHANGE'),
    ('P20D4_MOM_CHANGE', 20, 4, 'MOM_CHANGE'),
    ('P20D5_BABY_CHANGE', 20, 5, 'BABY_CHANGE'),
    ('P20D5_MOM_CHANGE', 20, 5, 'MOM_CHANGE'),
    ('P20D6_BABY_CHANGE', 20, 6, 'BABY_CHANGE'),
    ('P20D6_MOM_CHANGE', 20, 6, 'MOM_CHANGE');
