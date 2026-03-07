# Klone — Git Dependency Manager for Android

**Add Android libraries by GitHub URL** — no binary publishing required.

```kotlin
// app/build.gradle.kts
dependencies {
    gitImplementation("https://github.com/square/retrofit", from = "2.9.0")
    gitImplementation("https://github.com/your-org/internal-sdk", branch = "main")
    gitImplementation("https://github.com/your-org/utils", commit = "a1b2c3d4")
}
```

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
    id("io.github.dev-jackson.klone") version "1.1.6"
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

## Ref Types

| Parameter | Example | Resolves to |
|---|---|---|
| `from` | `from = "2.9.0"` | Git tag `2.9.0` |
| `branch` | `branch = "main"` | Latest commit on `origin/main` |
| `commit` | `commit = "a1b2c3d4"` | Exact commit (full or short hash) |

---

## Private Repositories

Klone uses the system `git` binary — if your machine can `git clone` the URL, Klone can too.

**SSH (recommended for private repos):**
```kotlin
gitImplementation("git@github.com:your-org/private-sdk.git", branch = "main")
```

**HTTPS with token:**
Configure `~/.netrc` or use `git credential store` — Klone inherits it automatically.

Klone also propagates your host project's `gradle.properties` and private Maven repository credentials into each cloned build, so transitive dependencies from private Maven repos resolve correctly.

---

## Lock File

After every resolution, Klone writes `klone.resolved` to your project root. **Commit this file** — it pins exact commit hashes for reproducible builds across machines and CI.

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

## How It Works

Klone runs during the Gradle **settings phase** — before any project is configured:

```
./gradlew assembleDebug
  │
  └─ settings phase
       ├─ Klone scans build.gradle.kts files for gitImplementation() calls
       ├─ Clones / fetches repos → ~/.klone/packages/<host>/<owner>/<repo>/
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

Repos are cached at `~/.klone/packages/<host>/<owner>/<repo>/`. Subsequent builds reuse the cache — only `branch` dependencies fetch updates on each build.

Override the cache directory:
```bash
export KLONE_CACHE_DIR=/path/to/cache
```

Clear a specific repo's cache:
```bash
rm -rf ~/.klone/packages/github.com/square/retrofit
```

---

## CI/CD

On CI, the cache is cold on each run by default. Cache `~/.klone/packages/` between runs to speed things up.

**GitHub Actions example:**
```yaml
- uses: actions/cache@v4
  with:
    path: ~/.klone/packages
    key: klone-${{ hashFiles('klone.resolved') }}
    restore-keys: klone-
```

---

## Requirements

- Gradle 7.0+
- Android Gradle Plugin 7.0+
- Java 17+
- The dependency library must have its own `build.gradle.kts` (or `build.gradle`) at root or in submodules

---

## Troubleshooting

**`gitImplementation` not found**
Check that `id("io.github.dev-jackson.klone.project")` is in the module's `plugins {}` block.

**`No modules detected` warning**
The cloned repo needs a Gradle build file. Pure Maven (`pom.xml`-only) or header-only repos are not supported.

**Authentication failure on clone**
Test with `git clone <url>` directly. Klone uses the same git credentials.

**Build fails after updating a branch dep**
The cached repo might be stale. Delete the cache entry:
```bash
rm -rf ~/.klone/packages/github.com/<owner>/<repo>
./gradlew --rerun-tasks assembleDebug
```

---

## Source & License

[github.com/dev-jackson/klone](https://github.com/dev-jackson/klone) · Apache 2.0

<!-- keywords: android gradle plugin git dependency manager composite build package manager SPM swift package manager gitImplementation private repository -->
