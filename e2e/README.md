# Klone end-to-end testing

## Automated scenarios

```bash
./gradlew :plugin:functionalTest   # real Gradle builds against emulated projects
./gradlew check                    # unit + functional
```

Scenarios live in `plugin/src/functionalTest/kotlin/dev/klone/scenarios/`. Each one builds
throwaway projects in a temp dir — no network, no Android SDK:

| Fixture | Emulates |
|---|---|
| `GitRepoFixture` | A git remote (GitHub/GitLab) served over `file://`, with tags/branches/commits |
| `Projects.javaLibrary` / `multiModuleLibrary` | Single and multi-module libraries (incl. a `:sample` app that must be ignored) |
| `Projects.host` | The consumer app: `settings.gradle.kts`, `app/`, `gradle/libs.versions.toml` |
| `PrivateMavenServer` | An internal Nexus/Artifactory with Basic auth on localhost |
| `KloneRunner` | TestKit runner with an isolated `KLONE_CACHE_DIR` |

`AndroidScenario` builds a real Android library + app with AGP. It needs an Android SDK
(platform 35) and access to Google's Maven repo; without them it is reported as **skipped**.

## Coverage

Every feature has an end-to-end scenario (`plugin/src/functionalTest/.../scenarios/`) and the
parsing/logic underneath has unit tests (`plugin/src/test/`).

| Feature | Scenario |
|---|---|
| SPM `from` / `exact` / `upToNextMinor` / `version` ranges / `tag` | `SpmVersionScenario` (11 requirement cases) |
| Tag patterns (`sdk-{version}`), `v`-prefixed tags | `SpmVersionScenario` |
| Range pinned by lock, update stays below next major | `SpmVersionScenario` |
| Unsatisfiable / conflicting requirement errors | `SpmVersionScenario` |
| Switching versions on a warm cache | `GitTagScenario` |
| Branch pinned by lock, `-Pklone.update`, stable `resolvedAt` | `BranchLockScenario` |
| Changing the declared ref ignores the old lock entry | `BranchLockScenario` |
| `-Pklone.frozen=true` (CI), frozen + update rejected | `LockModesScenario` |
| `-Pklone.update=<name>` updates only that repo | `LockModesScenario` |
| Full / short commits, commits missing from a shallow cache | `CommitScenario` |
| `module`, `modules`, all modules, sample apps ignored, unknown module error | `MultiModuleScenario` |
| Monorepo `dir`, git submodules, repo without settings, default branch not `main` | `RepoLayoutScenario` |
| Git library depending on another git library (internal deps) | `TransitiveAndLocalScenario` |
| `localImplementation`, settings DSL `local(...)` | `TransitiveAndLocalScenario` |
| Two repos with the same directory name | `TransitiveAndLocalScenario` |
| `libs.versions.toml` `[metadata] klone.*` (git, branch, module, path) | `VersionCatalogScenario` |
| `libs.versions.x.get()` in `gitImplementation` | `VersionCatalogScenario` |
| Invalid catalog metadata error | `VersionCatalogScenario` |
| Groovy host, settings DSL builders, `configuration = "api"` | `IntegrationScenario` |
| Missing settings plugin error | `IntegrationScenario` |
| Published POM keeps real coordinates | `IntegrationScenario` |
| Private Maven repo with Basic auth (literal credentials, header credentials) | `PrivateRepoScenario` |
| Credentials by reference (never written to disk) | `IntegrationScenario` |
| AGP / Kotlin version mismatch warning | `IntegrationScenario` |
| Cache in `$GRADLE_USER_HOME/caches/klone`, survives wiping caches / new machine | `CacheScenario` |
| `--refresh-dependencies` re-fetches and reports moved tags | `CacheScenario` |
| `klone.cacheDir` property | `CacheScenario` |
| Local edits in the cache are stashed, never lost | `CacheScenario` |
| `kloneList`, `kloneOutdated`, `kloneClean` (`--unused-days`, `--all`) | `MaintenanceTasksScenario` |
| Corrupt cache, `--offline`, missing repo, conflicting refs, commented calls | `ResilienceScenario` |
| Parallel builds on one cache, configuration cache reuse | `ResilienceScenario` |
| Real Android library + app (AGP) | `AndroidScenario` |
| Upgrading from 1.x keeps `from` dependencies on their locked commit | `LockMigrationScenario` |
| Host secrets never copied to the cache; referenced credentials and opt-ins are | `IntegrationScenario` |
| github.com over HTTPS (exact, SPM `from`, default branch, fetch by sha, real host build) | `RealRemoteScenario` (opt-in) |
| Private repo over SSH | `RealRemoteScenario` (opt-in, `KLONE_TEST_SSH_URL`) |

### Compatibility matrix

```bash
./gradlew :plugin:functionalTest -PtestGradleVersion=7.6.6    # any Gradle version; needs a JDK it supports (7.6/8.0: JDK 17)
KLONE_REAL_REMOTE=true ./gradlew :plugin:functionalTest --tests '*RealRemote*'
KLONE_REAL_REMOTE=true KLONE_TEST_SSH_URL=git@github.com:org/private.git ./gradlew :plugin:functionalTest --tests '*RealRemote*'
```

CI (`.github/workflows/ci.yml`) runs `check` on Linux, macOS and Windows (JDK 17, plus JDK 21 on Linux),
the scenarios on Gradle 7.6.6 / 8.0.2 / 8.14.6 / 9.8.2, and the github.com scenarios (SSH when the
`KLONE_TEST_SSH_KEY` / `KLONE_TEST_SSH_URL` secrets exist).

To add a scenario: extend `Scenario`, create remotes with `remote(...)`, a host with
`host(files = Projects.host(...))`, then assert on `runApp()` output or `buildAndFail()` messages.

## Manual playground

```bash
./e2e/run.sh
```

Generates `plugin/build/playground/` (local git remotes, a local library and a host app using
`libs.versions.toml` + `gitImplementation`) and runs it. Edit the host and re-run to experiment.
