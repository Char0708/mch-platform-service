# mch-platform-service

Local pure-synthetic service slice for the maternal and child full-cycle health product. It is an implementation and test environment only, not production identity, medical content, or production admission.

## Included

- Java 21 and Spring Boot 4.1.0
- Spring Modulith structure verification
- Actuator health/info infrastructure endpoints
- Flyway and MySQL 8.4/InnoDB structural migration probe
- ephemeral Testcontainers verification in CI
- boundary scan, Maven build/test, CI and account-level CODEOWNERS
- a pre-seeded pure-synthetic natural person and runtime-signed public person sessions established only through a trusted loopback identity handoff
- unchanged operator username/password accounts, roles, sessions, and permissions for editor/reviewer workflows
- atomic pregnancy self profile (`PREGNANT_WOMAN` + `SELF` + explicit second-trimester stage) with the admitted `20周+0天`–`20周+6天` dating slice
- published stage content, weight history/trend, and separated editor/reviewer workflow
- stable error envelopes, idempotency, optimistic content revision checks, append-only minimum audit, and MySQL-backed contract tests

## Explicitly excluded

There are no real endpoints, production credentials, production identity, hospital/message adapters, uploads, resident/staff data, children/delegation, reminders, payment, AI, diagnosis, risk grading, or medical advice. The admitted records are strictly synthetic and local. `engineering_schema_probe` still exists only to prove migration and MySQL compatibility; it is not reused as a business table.

## SERVICE-001 synthetic API boundary

All business routes use the internal-only `/api/v1` prefix. Mutating business routes require `Content-Type: application/json` and `Idempotency-Key`; content draft updates also require `If-Match`. Protected routes accept only server-issued `Authorization: Bearer <synthetic-session>` tokens. The token signing key is generated for the running process, so sessions intentionally do not survive a restart and must not be represented as production authentication.

The public product exposes no username/password registration or login API. `V0003__public_natural_person_identity.sql` seeds one synthetic `natural_person` without a pregnant-woman health profile; the incremental `V0004__r1_pregnancy_dating_slice.sql` adds only its synthetic display name and date of birth. `POST /api/v1/public-identity/sessions` accepts only an empty JSON object and resolves the person from a loopback-only trusted identity handoff; the feature is disabled by default and requires an out-of-repository shared assertion at runtime. The response returns the display name and an age calculated server-side for the `Asia/Hong_Kong` business date, never the date of birth. These attributes are not authentication credentials and never enter audit events.

Editor and reviewer accounts remain mutually exclusive runtime test fixtures using the existing operator login endpoint. The repository contains no shared default operator password and exposes no operator registration API. The admitted profile request accepts only `serviceMode=PREGNANCY`, keeps average menstrual duration as a non-calculation field, prefers a user-reported clinician due date, and otherwise estimates a due date as the first day of the last menstrual period plus 280 days. The resulting `X周+Y天` value is a local navigation constraint, not a diagnosis or professional confirmation. The remaining public flow stays limited to explicit `PREGNANCY_SECOND_TRIMESTER`, self weight trends, and `SYNTHETIC_DEMO` content with the fixed label “纯合成演示内容，不构成医疗建议”.

Flyway keeps `V0001__engineering_schema_probe.sql` unchanged, adds the SERVICE-001 schema in `V0002__synthetic_vertical_slice.sql`, adds the separated public natural-person identity realm in `V0003__public_natural_person_identity.sql`, and applies the DEC-049 fields only through `V0004__r1_pregnancy_dating_slice.sql`. Contract API tests, migration replay, module verification, and the original engineering probes all run against ephemeral `mysql:8.4` Testcontainers.

## Verification

```powershell
pwsh -NoLogo -NoProfile -File scripts/verify-engineering-boundaries.ps1
pwsh -NoLogo -NoProfile -File scripts/verify-local-environment.ps1
```

The reproducible local entry bootstraps task-scoped Java 21 and Maven Wrapper state, then requires a reachable Docker-compatible daemon because the test context uses an ephemeral `mysql:8.4` container. On 2026-08-09 the complete entry passed locally with Docker Desktop's WSL 2 Linux backend, Flyway migration `V0001`, and all three tests. Large Docker, Maven, and temporary data are rooted on `H:`. See [local verification](./docs/LOCAL-VERIFICATION.md) for the fixed versions, checksums, paths, failure behavior, and cleanup boundary.

The current technical owner is `@Char0708`; backup and independent review remain unresolved admission items. See [ownership](./docs/OWNERSHIP.md), the [ENV-2 boundary](./docs/ENV-2.md), and the [main branch target gate](./docs/BRANCH-PROTECTION.md).
