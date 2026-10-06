# Setup verification — 2026-10-05 (Asia/Bangkok)

> Historical setup snapshot. Statements below describe that initial execution, not current GitHub authentication or CI status. See the dated follow-up at the end for newer evidence.

Scope: foundation only. LOCAL PASS for build/infrastructure/application startup. CI NOT YET VERIFIED. GitHub authentication remains incomplete; no commit, remote creation or push has occurred.

## Environment

FlowDock directory did not exist (`Test-Path -LiteralPath` returned False); workspace root was not a Git repo. Initialized new local repo with `git init -b main`. No prior source/history was overwritten.

Default Java/Javac: Oracle 17.0.12; separate installed JDK: Microsoft OpenJDK 21.0.12+8. Build processes set JAVA_HOME/PATH only in their own process. A fresh shell still reported `java version "17.0.12"` after verification. No global env or Git config changed. Existing Git identity was found but is not used for the initial public commit: user chose hoanglv1106 + GitHub noreply after authentication.

Git 2.54.0.windows.1; Docker Desktop 4.76.0, CLI/Engine 29.5.2, Compose 5.1.4; WSL docker-desktop distro version 2. Node 24.18.0, npm 11.16.0. Maven global absent; project Wrapper works. IntelliJ IDEA 2026.2.1 and VS Code already installed; neither reinstalled. GitHub CLI 2.102.0 portable downloaded under ignored `.local/tools/gh/bin/gh.exe`, official SHA-256 matched `ae64e556ecc240b200f7eba60d550e4bb60d78e860e69dd88c449405b86067f4`.

Requested using-agent-skills, planning-and-task-breakdown, incremental-implementation, source-driven-development, git-workflow-and-versioning, ci-cd-and-automation, security-and-hardening, documentation-and-adrs and debugging-and-error-recovery were not present in the available catalog or searched local skill directories. Equivalent inspect → source-check → implement → verify → review workflow used; no claim that those skills loaded.

## Commands and results

Commands below ran from repository root, with JDK 21 selected for Maven/Java application processes. To keep all caches/logs local, Maven calls set `MAVEN_USER_HOME` to the ignored `.local/maven-user` folder and passed `-Dmaven.repo.local=.local/m2`. Raw logs are ignored local diagnostics, not public artifacts.

| Check | Exact command / operation | Result |
|---|---|---|
| Initial tools | `java -version`, `javac -version`, `git --version`, `docker version`, `docker compose version`, `wsl --status`, `wsl --list --verbose`, `node --version`, `npm --version` | Versions above; WSL query retried with runtime approval |
| Docker startup | `docker desktop start` | Success; desktop-linux daemon ready |
| Port conflicts | `Get-NetTCPConnection -State Listen` filtered for 5432/9092/8080/8081 then 15432/19092/18080/18081; `docker ps --format '{{.Names}} {{.Ports}}'` | Existing apps occupied 5432/8080. Selected FlowDock ports were free; no other container stopped/restarted by our commands |
| Wrapper generation | `org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.11 -Dtype=only-script -DincludeDebugScript=false` using temporary portable Maven | BUILD SUCCESS; generated both scripts/properties. Maven ZIP verified against official SHA-512; SHA-256 pin staged |
| JDK used | `java -version`, `javac -version` using JDK 21 process PATH; `.\mvnw.cmd -v` | Java/Javac 21.0.12; Wrapper Maven 3.9.11, Microsoft JVM; compiler output `release 21` |
| Focused build | `.\mvnw.cmd -B -ntp -Dmaven.repo.local=.local/m2 clean verify` | BUILD SUCCESS; all 4 reactor projects; 1 focused health test, 0 failures/errors/skips |
| Compose config | `docker compose -f infra/compose.yaml config --quiet` | Exit 0 |
| Services | `docker compose -f infra/compose.yaml up -d --wait --wait-timeout 180 postgres kafka` | Kafka/Postgres healthy after scoped volume ownership correction; PostgreSQL final version 17.11 |
| Topic init | `docker compose -f infra/compose.yaml run --rm kafka-init` (repeated) | Exit 0; both topics 3 partitions/RF=1; same TopicIds on repeat |
| DB connection | `docker compose -f infra/compose.yaml exec -T postgres psql -U flowdock_lab -d flowdock -c 'SELECT version(), current_database();'` | PostgreSQL 17.11; database flowdock |
| Real integration | `.\mvnw.cmd -B -ntp -Dmaven.repo.local=.local/m2 -Pintegration verify` | Final BUILD SUCCESS; 2 infrastructure + 1 focused tests, no skips |
| Fresh migration | `InfrastructureIT.migrationWorksOnANewDedicatedDatabaseAndIsRepeatable` in preceding command | New `flowdock_verify_b41171f5f0a148c790ab28789e6410e5`; migrate=1, validate success, second migrate=0; namespace comment checked; only this test-created DB removed |
| Host Kafka | `InfrastructureIT.hostCanPublishAndConsumeAnActualRecordAtItsAcknowledgedCoordinate` in preceding command | Actual produce ACK + exact-offset consume; coordinates below |
| Actual JAR startup | `java -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --debug=false` and equivalent worker JAR | Two distinct host processes started hidden for verification; only own process handles stopped afterward |
| Actual host health | `Invoke-RestMethod http://127.0.0.1:18080/actuator/health` and port 18081, condition polling with deadline | Both returned `{"status":"UP"}`; applications used actual database |
| Application migration history | `docker compose -f infra/compose.yaml exec -T postgres psql -U flowdock_lab -d flowdock -c 'SELECT version, description, success FROM flyway_schema_history;'` | V1 / foundation schema / true |
| Internal listener | `docker compose -f infra/compose.yaml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --describe` | Both topics accessible, shape correct |
| Internal actual consume | `docker compose -f infra/compose.yaml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic flowdock.payment.events --partition 0 --offset 0 --max-messages 1 --timeout-ms 15000 --property print.partition=true --property print.offset=true` | Read same host-produced fixture, partition 0 / offset 0, 1 message processed; exit 0 |
| Dependency pins resolved | `.\mvnw.cmd -B -ntp -Dmaven.repo.local=.local/m2 dependency:tree -Dincludes=org.apache.kafka:kafka-clients,org.flywaydb:flyway-core,org.postgresql:postgresql,org.springframework:spring-core` | BUILD SUCCESS; client 3.9.2, Flyway 11.7.2, JDBC 42.7.11, Framework 6.2.19 |
| Ignore policy | `git check-ignore -v --no-index infra/.env core/target/example.class backend/target/example.jar worker/target/example.jar .local/tools/gh.zip` | All ignored |
| Public example | `git check-ignore --no-index infra/.env.example` | Exit 1 as expected: not ignored |
| Wrapper line endings | `git check-attr eol -- mvnw mvnw.cmd` | LF / CRLF; executable bit for mvnw staged |

Kafka evidence: bootstrap `127.0.0.1:19092`, topic `flowdock.payment.events`, partition `0`, offset `0`, fixtureId `foundation-54c59f25-6735-443b-859e-10adaeee5d4c`. Internal consume returned the same marker/payload. No mocks, no consumer group offsets committed, no payment effects claimed.

Observed image digests (`docker image inspect apache/kafka:4.1.2 postgres:17.11-bookworm --format '{{.RepoTags}} {{.RepoDigests}}'`):

- Kafka: `sha256:5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e`
- PostgreSQL: `sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652`

## Failures encountered and corrections

- WSL in sandbox: `Wsl/EnumerateDistros/Service/E_ACCESSDENIED`; retry via runtime approval succeeded. No machine ACL/security modifications.
- Docker initially: missing `dockerDesktopLinuxEngine` named pipe; started Desktop through approved runtime command.
- Sandbox HTTPS: `The underlying connection was closed: An unexpected error occurred on a receive.` Retried via runtime approval with TLS 1.2; official downloads succeeded.
- Initial GH checksum comparison: manifest response was not read correctly. Downloaded manifest as a file, read the checksum entry and verified exact SHA-256 before extracting/running CLI.
- Kafka first startup: `java.nio.file.AccessDeniedException: /tmp/kraft-combined-logs/bootstrap.checkpoint.tmp`. Added one-shot storage init for FlowDock Kafka volume; broker remains UID 1000.
- First real Kafka test: Admin `default.api.timeout.ms` smaller than default `request.timeout.ms`. Set request timeout 10000 ms, API timeout 20000 ms. Full integration run then passed.
- Initial host health polling did not recognize response body, despite app logs HTTP 200; reran via Invoke-RestMethod with explicit body evidence. Both UP, own app processes stopped in finally. Did not count the failed polling attempt as PASS.
- GitHub device authentication expired (`expired_token`) before user completed authentication. No token requested in chat; no publication attempted.

## Public repository review and publication

Staged 22 intended files; reviewed staged source/config/docs and compared both Wrapper scripts with the official 3.3.4 distribution (normalized line endings). `git diff --cached --check` exited 0. Pattern review of staged blobs found no GitHub token patterns, private-key headers, AWS access-key patterns, absolute Windows user/JDK paths or corporate email. Only public lab credentials are present. `.local`, logs, binaries built under target and personal configs are excluded. No dedicated secret scanner was installed; this review is limited and does not prove absence of all secrets. Re-review immediately before any future commit/push.

GitHub unauthenticated repository query returned 404. This is not sufficient evidence that `hoanglv1106/flowdock` does not exist. CLI `auth status` confirmed no login; active account and remote contents remain unverified. Remote creation/push require successful authentication and a content/history check first.

To resume safely from this root, run `.\.local\tools\gh\bin\gh.exe auth login --hostname github.com --git-protocol https --web`, complete hoanglv1106 login in browser, choose **No** if asked to configure Git authentication globally, then notify the coding agent. Never paste a token. The agent must verify account, retrieve confirmed GitHub noreply identity, review any existing remote, configure repo-local identity/credential helper, review staged diff, commit and normal push. Public creation/push is already authorized by the original request; no release tag authorized.

CI workflow exists but has not run remotely. Branch: main (unborn); commit hash and GitHub URL not yet created/verified.

Kafka/Postgres remain running and healthy for development; Java verification processes have stopped. Volumes retained. External `tasks/plan.md` and `tasks/todo.md` were read only and no phase checkboxes changed. Frontend/full app Compose/business/reliability scenarios remain outside setup scope.

## Follow-up — 2026-10-06 (Asia/Bangkok)

Publication scope remains P0 foundation only. Contracts/ADR describe accepted future design, not implemented payment or reliability behavior.

### Previous host re-verification (2026-10-05)

Executed by the Captain after the documentation reviews, independently of the original setup report:

- Process-local Microsoft JDK 21.0.12; `mvnw.cmd -B -ntp -Dmaven.repo.local=.local/m2 clean verify` followed by `-Pintegration verify`: combined exit 0. Fresh reports recorded one focused health test and two infrastructure tests, with zero failures, errors or skips.
- PostgreSQL fresh database `flowdock_verify_1b4af6313b2143bd8b3e861cc12f82b4`: one migration, second migration count zero. The test removed only its UUID database.
- Host Kafka fixture `foundation-8b6fa635-1a42-41e0-8dbb-b559684db8f0`: topic `flowdock.payment.events`, partition 0, offset 1. Internal listener consumer read that same fixture and coordinate.
- Backend and worker JARs: health `UP` at 18080/18081, followed by a successful recheck of both endpoints. Only the two owned Java processes were stopped; containers and volumes were retained.
- An earlier health helper exited 1 after reporting backend `UP`, with `Owned application exited before health gate`. Both application logs showed startup success; the cause of the helper failure was not established. That attempt is not PASS. The later endpoint-based gate completed with exit 0.
- `git diff --check` and `git diff --cached --check`: exit 0. These checks covered the index/worktree at that time, not future staging changes.

### Current authentication and publication checkpoint

- Portable project `gh.exe auth status`: authenticated active account `hoanglv1106`, HTTPS, keyring. `gh api user` independently confirmed login `hoanglv1106` and numeric ID `250913612`; public author identity will use `250913612+hoanglv1106@users.noreply.github.com`. No token is recorded here.
- At the start of this follow-up: local `main` has no commit, no configured remote, and the authenticated repository lookup cannot resolve `hoanglv1106/flowdock`.
- Commit/push, remote CI and clean-checkout rehearsal were still pending at this checkpoint. Authentication success and historical local tests do not close P0 by themselves.

### Publication and first remote CI

- Initial commit: `a884d47777860cbab6505e64f98d64e2ec2ba178`, 25 reviewed files. Both unstaged/staged diff checks passed after removing a newly introduced blank EOF line. Wrapper `mvnw` is tracked as executable (`100755`); `.env`, `.local` and `target` exclusions were checked. Pattern scan of staged diff found no common GitHub/AWS/private-key/Slack credential patterns; public lab defaults are intentional. This is not a dedicated scanner or a proof of absence of every secret.
- Repo-local Git identity uses the confirmed GitHub noreply address. Repo-local GitHub credential helper delegates to the portable CLI/keyring; no global identity/helper or token file was changed.
- Created [public repo hoanglv1106/flowdock](https://github.com/hoanglv1106/flowdock), checked its visibility and empty remote refs, then normal-pushed `main` (no force). A fresh public clone independently confirmed the exact initial commit and wrapper executable bit.
- [First Actions run 37399761194](https://github.com/hoanglv1106/flowdock/actions/runs/37399761194): **FAILED** in both jobs at setup-java, before any build, Compose startup or tests. Error: no matching SemVer `21.0.12+8`. The failed/skipped gates are not PASS.
- Official [setup-java version syntax](https://github.com/actions/setup-java/blob/de7274f081f381c8f8158605e0321c36c376e2e6/README.md#supported-version-syntax) and [Adoptium GA catalog](https://api.adoptium.net/v3/assets/feature_releases/21/ga?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=linux&page_size=5&project=jdk&vendor=eclipse) confirm Linux x64 JDK release `jdk-21.0.12+8`, SemVer `21.0.12+8.0.LTS`. Corrected both workflow selectors to that full SemVer; Java major/patch/build, action SHA pins and all gates remain unchanged.
- CI after this correction and full fresh-checkout infrastructure rehearsal are still pending here. No P0 completion claimed at this checkpoint.
