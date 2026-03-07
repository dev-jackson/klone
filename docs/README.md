# Klone — Android Package Manager

A Gradle Settings Plugin that brings **SPM-style git-based dependency management** to Android.
Declare Android library dependencies by git URL — Klone clones, caches, and wires them up via Gradle Composite Builds automatically.

---

## Quick Start

### 1. Apply the settings plugin

```kotlin
// settings.gradle.kts
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

### 2. Apply the project plugin and declare dependencies

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.github.dev-jackson.klone.project")
}

dependencies {
    // Full repo — all submodules auto-detected
    gitImplementation("https://github.com/square/retrofit", from = "2.9.0")

    // Single specific submodule
    gitImplementation("https://github.com/square/retrofit", module = "converter-gson", from = "2.9.0")

    // Multiple specific submodules
    gitImplementation("https://github.com/square/retrofit",
        modules = listOf("converter-gson", "adapter-rxjava2"),
        from = "2.9.0")

    // Branch tracking
    gitImplementation("https://github.com/user/mylib", branch = "main")

    // Pinned commit
    gitImplementation("https://github.com/user/pinned", commit = "abc123def456")

    // Regular Maven deps still work alongside git deps
    implementation("androidx.core:core-ktx:1.12.0")
}
```

---

## How It Works

```
settings phase
  └─ Klone scans all build.gradle.kts files for gitImplementation() calls
  └─ Clones / fetches each repo into ~/.klone/packages/<host>/<user>/<repo>/
  └─ Reads the repo's settings.gradle.kts to discover submodules (include(":x"))
  └─ Registers one includeBuild per repo URL with N dependency substitution rules
  └─ Writes klone.resolved lock file

project evaluation phase
  └─ gitImplementation() resolves coordinates from KloneRegistry
  └─ Gradle substitutes the Maven dependency with the local project source
```

Klone always does **one `includeBuild` per URL**, regardless of how many submodules are requested.

---

## Ref Types

| Syntax | Resolves to |
|--------|------------|
| `from = "2.9.0"` | Git tag `2.9.0` |
| `branch = "main"` | Latest commit on `origin/main` |
| `commit = "abc123"` | Exact commit hash |

---

## Multi-Module Support

When a git repo contains multiple Gradle subprojects (like Retrofit), Klone reads the repo's
`settings.gradle.kts` `include(...)` declarations to auto-discover all submodules.

```kotlin
// No module arg → includes ALL submodules found in the repo's settings
gitImplementation("https://github.com/square/retrofit", from = "2.9.0")

// One specific submodule
gitImplementation("https://github.com/square/retrofit", module = "converter-gson", from = "2.9.0")

// Several specific submodules (one includeBuild, multiple substitutions)
gitImplementation("https://github.com/square/retrofit",
    modules = listOf("converter-gson", "adapter-rxjava2"),
    from = "2.9.0")
```

For single-module repos (no `include(...)` in their settings), Klone falls back to `project(":")`.

---

## Lock File

Klone writes `klone.resolved` to your project root after every resolution. **Commit this file.**

```json
{
  "version": 1,
  "dependencies": [
    {
      "url": "https://github.com/square/retrofit",
      "refType": "tag",
      "refValue": "2.9.0",
      "resolvedCommit": "a8266f0f",
      "resolvedModuleCoordinates": "com.squareup.retrofit2:retrofit",
      "resolvedAt": "2026-03-05T10:00:00Z"
    }
  ]
}
```

---

## Cache

Repos are cached at `~/.klone/packages/<host>/<user>/<repo>/`.
Override with the `KLONE_CACHE_DIR` environment variable.

---

## Requirements

- Gradle 7.0+
- Android Gradle Plugin 7.0+
- Java 17+
- The library must have its own Gradle build file (`build.gradle.kts` or `build.gradle`)

---

## Publishing

### Option A — Gradle Plugin Portal (recommended, public)

1. Create an account at [plugins.gradle.org](https://plugins.gradle.org)
2. Generate an API key in your account settings
3. Add credentials to `~/.gradle/gradle.properties`:
   ```properties
   gradle.publish.key=YOUR_KEY
   gradle.publish.secret=YOUR_SECRET
   ```
4. Bump the version in `plugin/build.gradle.kts` (remove `-SNAPSHOT`)
5. Publish:
   ```bash
   ./gradlew :plugin:publishPlugins
   ```
6. Users install it with no extra repository config:
   ```kotlin
   plugins { id("dev.klone") version "1.0.0" }
   ```

### Option B — Maven Central (JVM ecosystem standard)

1. Create a Sonatype account at [central.sonatype.com](https://central.sonatype.com)
2. Claim the `dev.klone` namespace (requires DNS TXT record or GitHub verification)
3. Configure signing in `~/.gradle/gradle.properties`:
   ```properties
   signing.keyId=LAST8CHARS
   signing.password=YOUR_PASSPHRASE
   signing.secretKeyRingFile=/Users/you/.gnupg/secring.gpg
   ossrhUsername=YOUR_SONATYPE_USER
   ossrhPassword=YOUR_SONATYPE_PASS
   ```
4. Add the `maven-publish` + `signing` tasks to `plugin/build.gradle.kts` (already has `maven-publish`)
5. Publish:
   ```bash
   ./gradlew :plugin:publishToSonatype closeAndReleaseSonatypeStagingRepository
   ```

### Option C — GitHub Packages (team/org use)

```kotlin
// plugin/build.gradle.kts — add inside publishing {}
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/YOUR_ORG/YOUR_REPO")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}
```
```bash
./gradlew :plugin:publish
```
Users add your GitHub Packages repository to their `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/YOUR_ORG/YOUR_REPO")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        gradlePluginPortal()
    }
}
```

### Option D — Maven Local (local development only)

```bash
./gradlew :plugin:publishToMavenLocal
```
Published to `~/.m2/repository/dev/klone/`. Only works on the same machine.

---

## Building & Testing

```bash
# Build
./gradlew :plugin:build

# Tests only
./gradlew :plugin:test

# Lint
./gradlew :plugin:check

# Publish locally for testing
./gradlew :plugin:publishToMavenLocal
```

---

## Plugin IDs

| Plugin ID | Applied in | Purpose |
|-----------|-----------|---------|
| `io.github.dev-jackson.klone` | `settings.gradle.kts` | Clones repos, sets up composite builds |
| `io.github.dev-jackson.klone.project` | `app/build.gradle.kts` | Provides `gitImplementation()` |
