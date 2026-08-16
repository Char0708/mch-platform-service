# ENV-2 synthetic engineering environment

The executable ENV-2 slice is available in CI and through the approved local verification entry:

- GitHub-hosted CI runner provisioned per workflow run
- Java 21 and Maven wrapper
- ephemeral `mysql:8.4` Testcontainers instance using InnoDB and `utf8mb4`
- the structural `engineering_schema_probe` migration only
- no external service adapter, resident/staff identity, business configuration or data fixture
- no persistent database, production egress credential, deployment credential or secret value

The CI database is destroyed with its runner. Local Java 21, Maven Wrapper, Docker Desktop WSL 2, and MySQL 8.4 Testcontainers are reproducible through the task-scoped entry documented in [LOCAL-VERIFICATION.md](./LOCAL-VERIFICATION.md); on 2026-08-09 all three service tests passed and Testcontainers left no residual container. The local database is ephemeral and uses only synthetic engineering data. A persistent shared ENV-2 still requires a separately approved resource domain, network policy, secret manager, logging boundary and cleanup owner.
