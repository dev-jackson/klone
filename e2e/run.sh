#!/usr/bin/env bash
# Generates the Klone playground and runs its host app end to end.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/gradlew" -p "$ROOT" :plugin:generatePlayground -q
cd "$ROOT/plugin/build/playground/host"
KLONE_CACHE_DIR="$ROOT/plugin/build/playground/cache" "$ROOT/gradlew" :app:run -q
