ALTER TABLE natural_person
    ADD COLUMN display_name VARCHAR(64) NULL AFTER external_person_reference,
    ADD COLUMN date_of_birth DATE NULL AFTER display_name;

UPDATE natural_person
SET display_name = '林女士',
    date_of_birth = DATE '1994-06-15'
WHERE identity_provider = 'R1_SYNTHETIC_FIXTURE'
  AND external_person_reference = 'r1-default-natural-person'
  AND synthetic = TRUE;

ALTER TABLE explicit_stage_profile
    ADD COLUMN service_mode VARCHAR(32) NULL AFTER subject_id,
    ADD COLUMN last_menstrual_period_date DATE NULL AFTER stage_source_type,
    ADD COLUMN average_menstrual_duration_days TINYINT NULL AFTER last_menstrual_period_date,
    ADD COLUMN clinician_estimated_due_date DATE NULL AFTER average_menstrual_duration_days,
    ADD COLUMN estimated_due_date DATE NULL AFTER clinician_estimated_due_date,
    ADD COLUMN estimated_due_date_source VARCHAR(48) NULL AFTER estimated_due_date,
    ADD COLUMN gestational_age_as_of_date DATE NULL AFTER estimated_due_date_source,
    ADD COLUMN gestational_age_weeks SMALLINT NULL AFTER gestational_age_as_of_date,
    ADD COLUMN gestational_age_days TINYINT NULL AFTER gestational_age_weeks;
