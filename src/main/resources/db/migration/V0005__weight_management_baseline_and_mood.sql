ALTER TABLE weight_record
    ADD COLUMN mood_code VARCHAR(16) NULL AFTER source_type,
    ADD CONSTRAINT chk_weight_record_mood_code
        CHECK (mood_code IS NULL OR mood_code IN ('HAPPY', 'CALM', 'LOW', 'DISTRESSED'));

CREATE TABLE weight_management_baseline (
    subject_id VARCHAR(32) NOT NULL,
    pre_pregnancy_weight_kg DECIMAL(5,2) NOT NULL,
    height_cm DECIMAL(5,2) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (subject_id),
    CONSTRAINT chk_weight_management_baseline_weight
        CHECK (pre_pregnancy_weight_kg > 0),
    CONSTRAINT chk_weight_management_baseline_height
        CHECK (height_cm BETWEEN 50 AND 250),
    CONSTRAINT fk_weight_management_baseline_subject
        FOREIGN KEY (subject_id) REFERENCES health_subject (subject_id)
);
