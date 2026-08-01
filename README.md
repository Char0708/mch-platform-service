# mch-platform-service

Minimal non-business engineering sample for the service platform of the maternal and child full-cycle health product.

## Included

- Java 21 and Spring Boot 4.1.0
- Spring Modulith structure verification
- Actuator health/info infrastructure endpoints
- Flyway and MySQL 8.4/InnoDB structural migration probe
- ephemeral Testcontainers verification in CI
- boundary scan, Maven build/test, CI and account-level CODEOWNERS

## Explicitly excluded

There are no business APIs, login or permission flows, health records, medical fields or rules, configuration publication implementation, real endpoints, production credentials, hospital/message adapters, uploads, analytics, or resident/staff data. `engineering_schema_probe` exists only to prove migration and MySQL compatibility; it is not a business table.

## Verification

```bash
pwsh -NoLogo -NoProfile -File scripts/verify-engineering-boundaries.ps1
bash ./mvnw -B --no-transfer-progress verify
```

The Maven verification requires Docker because the test context uses an ephemeral `mysql:8.4` container. The current workstation lacks Java and Docker; GitHub Actions has run the sample successfully with one Flyway structural migration and three passing tests.

The current technical owner is `@Char0708`; backup and independent review remain unresolved admission items. See [ownership](./docs/OWNERSHIP.md), the [ENV-2 boundary](./docs/ENV-2.md), and the [main branch target gate](./docs/BRANCH-PROTECTION.md).
