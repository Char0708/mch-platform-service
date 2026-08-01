# ENV-2 synthetic engineering environment

The currently executable ENV-2 slice is limited to CI:

- GitHub-hosted CI runner provisioned per workflow run
- Java 21 and Maven wrapper
- ephemeral `mysql:8.4` Testcontainers instance using InnoDB and `utf8mb4`
- the structural `engineering_schema_probe` migration only
- no external service adapter, resident/staff identity, business configuration or data fixture
- no persistent database, production egress credential, deployment credential or secret value

The database is destroyed with the CI runner. Local ENV-2 remains `NOT RUN` because Docker and Java are not currently available on the workstation. A persistent shared ENV-2 still requires a separately approved resource domain, network policy, secret manager, logging boundary and cleanup owner.
