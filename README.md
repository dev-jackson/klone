# Klone — Git Dependency Manager for Android

**Add Android libraries by GitHub URL** — no binary publishing required.

```kotlin
// app/build.gradle.kts
dependencies {
    gitImplementation("https://github.com/square/retrofit", from = "2.9.0")      // 2.9.0 up to next major
    gitImplementation("https://github.com/your-org/internal-sdk", branch = "main")
    gitImplementation("https://github.com/your-org/utils", commit = "a1b2c3d4")
}
```

Or keep your version catalog and only say where each library's source lives — see [Version Catalogs](#version-catalogs-libsversionstoml), the recommended setup.

Klone is a Gradle Settings Plugin that brings **Swift Package Manager-style** dependency management to Android. Declare a git URL, pick a tag/branch/commit, and Klone handles everything: cloning, module detection, and [Composite Build](https://docs.gradle.org/current/userguide/composite_builds.html) wiring — automatically.

---

## Why Klone?

| Without Klone | With Klone |
|---|---|
| Fork a library → publish to Maven → add to build | Add one line with the git URL |
| Update version → republish → re-sync | Change tag/branch → done |
| Private libs need Nexus/Artifactory setup | Works with any git repo you can clone |
| Source-level debugging impossible | Full source available, breakpoints work |

---

## Setup (5 minutes)

### Step 1 — `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.github.dev-jackson.klone") version "2.0.0-beta1"
}

rootProject.name = "my-app"
include(":app")
```

### Step 2 — `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("kotlin-android")
    id("io.github.dev-jackson.klone.project")   // <-- add this
}

android { ... }

dependencies {
    // Pin to a release tag
    gitImplementation("https://github.com/square/retrofit", from = "2.9.0")

    // Always track latest on a branch
    gitImplementation("https://github.com/your-org/design-system", branch = "main")

    // Pin to an exact commit (most reproducible)
    gitImplementation("https://github.com/your-org/analytics", commit = "a1b2c3d4e5f6")

    // Regular Maven deps still work alongside
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("com.google.android.material:material:1.12.0")
}
```

Run `./gradlew assembleDebug` — Klone clones and wires the dependencies on first build.

---

## Plugin IDs

| Where | Plugin ID |
|---|---|
| `settings.gradle.kts` | `io.github.dev-jackson.klone` |
| `app/build.gradle.kts` (or any module) | `io.github.dev-jackson.klone.project` |

---

## Common Use Cases

### Internal SDK / design system

Keep your company's shared library as source — no Nexus or Artifactory needed:

```kotlin
gitImplementation("https://github.com/your-org/android-design-system", branch = "main")
gitImplementation("https://github.com/your-org/analytics-sdk", from = "3.1.0")
```

### Multi-module library (e.g., Retrofit)

Klone auto-reads the cloned repo's `settings.gradle.kts` to discover all submodules:

```kotlin
// All submodules at once
gitImplementation("https://github.com/square/retrofit", from = "2.9.0")

// Specific submodule only
gitImplementation(
    "https://github.com/square/retrofit",
    module = "converter-gson",
    from = "2.9.0"
)

// Several specific submodules
gitImplementation(
    "https://github.com/square/retrofit",
    modules = listOf("retrofit", "converter-gson", "adapter-rxjava3"),
    from = "2.9.0"
)
```

### Patch a library without forking

Clone it, fix the bug locally, point to your branch:

```kotlin
gitImplementation("https://github.com/your-org/patched-okhttp", branch = "fix/crash-on-timeout")
```

### Library under active development

Stay on `main` during development, then pin to a release tag when stable:

```kotlin
// During development
gitImplementation("https://github.com/your-org/new-feature-lib", branch = "main")

// When shipping to production — pin the exact commit from klone.resolved
gitImplementation("https://github.com/your-org/new-feature-lib", commit = "d4e5f6a7b8c9")
```

---

## Version Catalogs (`libs.versions.toml`)

Keep using your normal catalog and `libs.*` accessors. Add the git (or local) source of each library under `[metadata]` — Gradle ignores extra keys there, so the file stays a valid catalog and no new file is needed:

```toml
# gradle/libs.versions.toml
[versions]
retrofit = "2.9.0"

[libraries]
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
gson     = { module = "com.squareup.retrofit2:converter-gson", version.ref = "retrofit" }
design   = { module = "com.your-org:design" }
utils    = { module = "com.your-org:utils" }

[metadata]
klone.retrofit = "https://github.com/square/retrofit"          # exact library version as tag (also tries "v2.9.0")
klone.okhttp   = { git = "https://github.com/square/okhttp", from = "4.12.0", tagPattern = "parent-{version}" }
klone.gson     = { git = "https://github.com/square/retrofit", module = "converter-gson" }
klone.design   = { git = "git@github.com:your-org/design.git", branch = "main" }
klone.utils    = { path = "../utils" }                         # local checkout
```

```kotlin
// app/build.gradle.kts — plain catalog accessors
dependencies {
    implementation(libs.retrofit)
    implementation(libs.design)
}
```

`klone.<alias>` must match a `[libraries]` alias. Keys:

| Key | Meaning |
|---|---|
| `git` / `path` | Repo URL, or a local checkout (one of them) |
| `from`, `exact`, `upToNextMinor`, `version`, `tag`, `branch`, `commit` | Requirement — see [Versions](#versions-spm-style). Default: the library's catalog version (exact, or a range such as `1.+` / `[1.0,2.0)`), else the repo's default branch |
| `module` | Project inside a multi-module repo (defaults to the artifact name) |
| `tagPattern` | How the repo names release tags, e.g. `"sdk-{version}"` |
| `dir` | Gradle build inside a monorepo, e.g. `"android/sdk"` |

Works the same in Groovy (`implementation libs.retrofit`). This is the recommended setup: real Maven coordinates, IDE completion, no script parsing.

`gitImplementation()` also reads catalog versions:

```kotlin
gitImplementation("https://github.com/square/retrofit", from = libs.versions.retrofit.get())
```

---

## Versions (SPM-style)

| Parameter | Example | Resolves to |
|---|---|---|
| `from` | `from = "2.9.0"` | Highest tag `>=2.9.0 <3.0.0` (SPM `from:` / up to next major) |
| `upToNextMinor` | `upToNextMinor = "2.9.0"` | Highest tag `>=2.9.0 <2.10.0` |
| `exact` | `exact = "2.9.0"` | That version only (tag `2.9.0`, or `v2.9.0`) |
| `version` | `version = "^2.9"`, `"~2.9.1"`, `">=2.0 <3"`, `"2.+"`, `"[2.0,3.0)"`, `"latest.release"` | Highest tag in the range (npm, Gradle and Maven syntaxes) |
| `tag` | `tag = "release-42"` | That literal tag |
| `branch` | `branch = "main"` | Latest commit on the branch |
| `commit` | `commit = "a1b2c3d4"` | Exact commit (full or short hash) |
| *(none)* | | The repo's default branch |

- Pre-releases (`2.0.0-rc1`) are only picked when the range names one.
- `tagPattern = "sdk-{version}"` maps versions to tags in repos that prefix them.
- Ranges and branches are pinned by [`klone.resolved`](#lock-file): a new matching tag is only used after `-Pklone.update`.
- `dir = "android/sdk"` builds a subdirectory of a monorepo; git submodules are checked out automatically.

> **Migrating from 1.x:** `from` used to mean the exact tag; now it means "up to next major". Your existing `klone.resolved` keeps every `from` dependency on its locked commit (Klone migrates the entry), so nothing changes until you run `-Pklone.update`. Use `exact = "..."` if you want the old meaning permanently.

---

## Private Repositories

Klone uses the system `git` binary — if your machine can `git clone` the URL, Klone can too.

**SSH (recommended for private repos):**
```kotlin
gitImplementation("git@github.com:your-org/private-sdk.git", branch = "main")
```

**HTTPS with token:**
Configure `~/.netrc` or use `git credential store` — Klone inherits it automatically.

Klone also propagates your host project's `gradle.properties` and private Maven repositories into each cloned build, so transitive dependencies from private Maven repos resolve correctly.

Declare private repos with **property-based credentials** so secrets are never written to disk — Klone passes them to the cloned build by reference:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            name = "internal"                       // reads internalUsername / internalPassword
            url = uri("https://nexus.your-org.com/repository/android/")
            credentials(PasswordCredentials::class)
        }
    }
}
```

### What is copied into cloned builds

Cloned builds need your Android flags (`android.useAndroidX`, ...), so Klone copies your root `gradle.properties` into each checkout — **except keys that look like secrets** (`*password*`, `*token*`, `*secret*`, `*apiKey*`, `*signing*`, ...), and never `local.properties`, `keystore.properties` or other files. Credentials of repos declared with `credentials(PasswordCredentials::class)` are the exception: the clone needs them to resolve dependencies.

To copy something else on purpose (it ends up in plain text in the cache):

```kotlin
// settings.gradle.kts
klone { propagateProperties("artifactory*", "analytics.apiKey") }   // also read from other root *.properties files
```

Set `internalUsername` / `internalPassword` in `~/.gradle/gradle.properties` or as `ORG_GRADLE_PROJECT_internalUsername` env vars on CI. Literal `username = "..."` credentials still work, but Klone has to copy them into the cached settings file and warns about it.

### AGP / Kotlin versions

A composite build loads build plugins once. When a cloned library declares another Android Gradle Plugin or Kotlin line (major.minor) than your app, Klone warns before Gradle fails with *"plugin is already on the classpath with an unknown version"*. Align the versions or point to a tag/branch of the library that uses yours.

### Publishing

If your project is itself a library you publish, declare Klone dependencies through the version catalog: the published POM then lists the library's real coordinates and version.

---

## Lock File

Klone writes `klone.resolved` to your project root. **Commit this file** — it pins exact commit hashes for reproducible builds across machines and CI.

- When the lock has an entry for the same URL and ref, Klone checks out that exact commit — `branch = "main"` does **not** move on its own.
- Update everything with `-Pklone.update=true`, or only some repos: `-Pklone.update=design-system,analytics` (repo name or URL fragment).
- **CI:** run with `-Pklone.frozen=true` — the build fails if `klone.resolved` is missing or out of date, and never rewrites it (like `npm ci`).
- Changing the declared ref (e.g. a new tag) ignores the old entry automatically.
- The file is only rewritten when something changes.

```json
{
  "version": 1,
  "dependencies": [
    {
      "url": "https://github.com/square/retrofit",
      "refType": "tag",
      "refValue": "2.9.0",
      "resolvedCommit": "a8266f0f47b2d02a797c35a0b2b7e03abe7d6280",
      "resolvedModuleCoordinates": "com.squareup.retrofit2:retrofit",
      "resolvedAt": "2026-03-06T19:00:00Z"
    }
  ]
}
```

---

## Reliability

- **Offline:** `--offline` builds use the cache; a missing repo fails with a clear message.
- **No hangs:** git never prompts for credentials (`GIT_TERMINAL_PROMPT=0`) and every git call has a timeout.
- **Self-healing cache:** interrupted clones and corrupt checkouts are re-cloned; local edits in the cache never block switching refs.
- **Parallel builds:** a file lock per repo makes concurrent builds and CI agents safe on a shared cache.
- **Configuration cache:** supported; git runs inside a Gradle `ValueSource`.
- **Same repo, different spellings** (`https://…`, `….git`, `git@…`) share one checkout. Declaring it with two different refs is an explicit error.
- Commented-out declarations are ignored; multi-line calls are supported.

---

## How It Works

Klone runs during the Gradle **settings phase** — before any project is configured:

```
./gradlew assembleDebug
  │
  └─ settings phase
       ├─ Klone scans build.gradle.kts files for gitImplementation() calls
       ├─ Clones / fetches repos → ~/.gradle/caches/klone/<host>/<owner>/<repo>/
       ├─ Reads each repo's settings.gradle.kts → discovers submodules
       ├─ Calls includeBuild() with substitution rules (one per URL)
       └─ Writes klone.resolved
  │
  └─ project evaluation phase
       └─ gitImplementation("url") → resolves coords → Gradle substitutes
          Maven artifact with local source project
```

One `includeBuild` per URL, regardless of how many modules you request from it.

---

## Cache

Repos are cached in Gradle's own cache directory: `$GRADLE_USER_HOME/caches/klone/<host>/<owner>/<repo>/` (usually `~/.gradle/caches/klone/`).

- **Cleared together with Gradle's caches.** Wiping `~/.gradle/caches` or using *Invalidate Caches* is safe: Klone re-clones and checks out the same commits from `klone.resolved`.
- **`--refresh-dependencies`** re-fetches cached tags too, and warns if a tag was moved upstream.
- **Offline** (`--offline`) builds use the cache only.
- **Local edits** made inside the cache (e.g. debugging a library) are saved with `git stash` before Klone switches commits, and the build prints how to recover them. To work on a library, prefer a `path` dependency.

Override the location (first match wins): `KLONE_CACHE_DIR` env var, `klone { cacheDir = file("...") }` in settings, or `-Pklone.cacheDir=...`.

### Maintenance tasks

```bash
./gradlew kloneList                      # each git dependency and the commit/tag it resolved to
./gradlew kloneOutdated                  # newer tags / moved branches available upstream
./gradlew kloneClean                     # delete this build's checkouts
./gradlew kloneClean --unused-days=30    # delete checkouts no build used in 30 days
./gradlew kloneClean --all               # delete the whole Klone cache
```

On Windows, Gradle's file watching keeps the current build's checkouts open: add `--no-watch-fs` (or run `./gradlew --stop` first) when cleaning them.

---

## CI/CD

`gradle/actions/setup-gradle` caches `~/.gradle/caches`, which includes Klone's checkouts. Run CI with the frozen lock:

```yaml
- uses: gradle/actions/setup-gradle@v4
- run: ./gradlew build -Pklone.frozen=true
```

---

## Requirements

- Gradle 7.6+ (CI runs the scenarios on 7.6, 8.0, 8.10, 8.14 and 9.8)
- Java 17+
- git on `PATH`
- Android Gradle Plugin: whatever your Gradle supports; the end-to-end Android scenario uses AGP 8.7. A cloned library should use the same AGP/Kotlin line as your app (Klone warns otherwise)
- Linux, macOS and Windows (all three in CI)
- The dependency library must have its own `build.gradle.kts` (or `build.gradle`) at root or in submodules

---

## Testing Klone

```bash
./gradlew :plugin:test             # unit tests
./gradlew :plugin:functionalTest   # end-to-end scenarios against emulated git repos and a private Maven repo
./gradlew :plugin:functionalTest -PtestGradleVersion=7.6.6           # same scenarios on another Gradle
KLONE_REAL_REMOTE=true ./gradlew :plugin:functionalTest --tests '*RealRemote*'   # against github.com
./e2e/run.sh                       # generate and run a hands-on playground
```

See [`e2e/README.md`](e2e/README.md).

---

## Troubleshooting

**`gitImplementation` not found**
Check that `id("io.github.dev-jackson.klone.project")` is in the module's `plugins {}` block. No import is needed (Klone 1.2.0+).

**`… was not resolved in the settings phase`**
Klone reads declarations before projects are configured, so arguments must be string literals, `listOf(...)`, or `libs.versions.*`. For anything dynamic, declare the source in `libs.versions.toml` `[metadata]` or in `settings.gradle.kts`: `klone { git("url") { tag("1.0.0") } }`.

**`No modules detected` warning**
The cloned repo needs a Gradle build file. Pure Maven (`pom.xml`-only) or header-only repos are not supported.

**Authentication failure on clone**
Test with `git clone <url>` directly. Klone uses the same git credentials.

**Branch dependency does not update**
It is pinned by `klone.resolved`. Run with `-Pklone.update=true`. To reset a cache entry:
```bash
./gradlew kloneClean
./gradlew build -Pklone.update=<repo-name>
```

---

## Source & License

[github.com/dev-jackson/klone](https://github.com/dev-jackson/klone) · Apache 2.0

<!-- keywords: android gradle plugin git dependency manager composite build package manager SPM swift package manager gitImplementation private repository -->
