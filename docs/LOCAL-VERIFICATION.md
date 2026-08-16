# Local reproducible verification

The local verification entry downloads and verifies a task-scoped Eclipse Temurin 21 archive when absent, extracts it beneath the authorized tool root, and uses a task-scoped Maven Wrapper cache. It does not install system software or modify user or machine environment variables.

## Fixed tool inputs

- tool root: `F:\CodexSystemTools\maternal-child-health-platform\env-001\`
- Java: Eclipse Temurin `21.0.12+8`, Windows x64 HotSpot JDK archive
- Java archive SHA-256: `9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0`
- Maven: existing Wrapper pinned to Apache Maven `3.9.16`
- Maven archive SHA-256 enforced by the Wrapper: `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`
- Maven Wrapper state: `H:\DevEnvironment\Maven\user-home`
- Maven local repository: `H:\DevEnvironment\Maven\repository`
- task temporary directory: `H:\DevEnvironment\Temp\ENV-001`
- Docker Desktop: `4.85.0`, installed at `H:\DevEnvironment\Docker\Desktop`
- Docker WSL data root: `H:\DevEnvironment\Docker\Data`
- container image required by the tests: `mysql:8.4`

`JAVA_HOME`, `PATH`, `MAVEN_USER_HOME`, `TEMP`, and `TMP` are set only inside the verification process and restored before it exits. The process PATH includes both the portable JDK and the task-scoped Docker CLI directory so Docker's signed credential helper can be resolved without modifying the user or system PATH. Maven artifacts and task temporary files stay on `H:` so the verification does not create a large repository or build cache on `C:`. The existing portable JDK remains on `F:`; it is not duplicated.

## Run

From the repository root:

```powershell
pwsh -NoLogo -NoProfile -File scripts/verify-local-environment.ps1
```

The script performs these gates in order:

1. downloads the fixed Temurin archive when absent, then verifies its checksum and Java 21 runtime;
2. verifies Maven Wrapper 3.9.16 and its pinned distribution checksum;
3. runs `scripts/verify-engineering-boundaries.ps1`;
4. requires both a Docker-compatible client and a reachable daemon;
5. pulls and inspects `mysql:8.4`;
6. runs `.\mvnw.cmd -B --no-transfer-progress verify`;
7. requires the Spring context, Flyway probe, and Spring Modulith structure tests to be the three passing Surefire tests;
8. fails if the run leaves a new MySQL Testcontainers container running.

Every unmet gate causes a non-zero exit. A client command without a reachable daemon is not accepted, and no alternative database can satisfy this entry.

## Current workstation boundary

Java 21 and Maven Wrapper are reproducible through the task-scoped tool root. Docker Desktop uses the WSL 2 Linux-container backend; its program and WSL virtual disks are rooted on `H:` under the paths above. The verification entry resolves the task-scoped Docker CLI explicitly when it is not yet visible on the inherited process `PATH`, then requires a reachable daemon before it pulls or runs `mysql:8.4`.

The complete entry passed on 2026-08-09 with Docker daemon `29.6.2`, MySQL 8.4 image digest `sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb`, Flyway migration `V0001`, and exactly three passing tests. No Testcontainers container remained after the run.

## Cleanup

After no process is using the tools, task-specific cleanup is limited to:

```text
F:\CodexSystemTools\maternal-child-health-platform\env-001\
H:\DevEnvironment\Maven\user-home\
H:\DevEnvironment\Maven\repository\
H:\DevEnvironment\Temp\ENV-001\
```

Do not remove the shared parent `H:\DevEnvironment\` or any pre-existing non-project data. Repository cleanup is limited to the ENV-001 service changes identified by Git; do not reset or alter either frontend repository.
