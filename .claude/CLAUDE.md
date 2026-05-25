# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build everything (fat JAR + WAR)
./gradlew build

# Build only the fat JAR (standalone)
./gradlew :mockinghal:shadowJar

# Build only the WAR (servlet container deployment)
./gradlew :mockinghal:war

# Run the server standalone (after building)
java -jar mockinghal/build/libs/mockinghal-all.jar

# Fetch the HALDiSh test dependency (required before running bash tests)
./gradlew :mockinghal:setup

# Run the integration tests (server must be running on port 8080)
mockinghal/src/test/bash/mockinghal.sh   # tests MockingHAL's own endpoints
mockinghal/src/test/bash/haldish.sh      # tests config load/replace/reset flow

# Publish to Maven Central (requires signing key + mavenCentralUsername/Password in gradle.properties)
# Uses the new Central Portal API (central.sonatype.com). publicationType = "USER_MANAGED" so the
# deployment appears at central.sonatype.com/publishing/deployments for manual review before release.
./gradlew :mockinghal:publishAllPublicationsToCentralPortal
```

There are no JUnit tests — all testing is done via the bash scripts above, which use the HALDiSh CLI (`build/haldish/`) to drive the live server.

## Architecture

MockingHAL is a Ktor/CIO HTTP server with four Kotlin source files under `mockinghal/src/main/kotlin/com/helpchoice/hal/mockinghal/`:

### `TreeNode.kt`
Defines the two core data classes:
- **`TreeNode`** — a node in the request-matching tree, with filter fields (`method`, `path`, `query`, `body`, `headerIn`) and response fields (`code`, `resource`, `headerOut`). Jackson deserializes YAML/JSON directly into this class.
- **`MatchResult`** — the accumulated response (code, resource, headerOut) built up as the tree is traversed.

### `ResourceRegistry.kt`
A thread-safe singleton (`AtomicReference`) holding the currently loaded `Map<String, TreeNode>`. Exposes:
- `parseConfig(text)` — parses YAML or JSON into the map and eagerly validates all regex patterns (returning `Result<…>` so parse errors surface as HTTP 400 at load time, not at request time).
- `replace(map)` / `append(map)` — atomic updates to the loaded config.

### `RequestMatcher.kt`
Contains the recursive `TreeNode.match(…)` extension function. Matching semantics:
- **method** and **path** are strict AND-filters (no match → 404).
- **query**, **headerIn**, and **body** are optional selectors: a matching child overrides the node's own response; no match leaves the node's accumulated response in effect.
- **path** patterns are regex-prefix-matched (anchored at index 0, segment-boundary-safe). The matched prefix is stripped from `remainingPath` before recursing.
- All filter types at a node are AND-combined; matched children are chained in order: method → path → query → headerIn → body.
- A node with no filter fields is a leaf — reaching it returns the accumulated response.
- Roots are tried in insertion order; first full-match wins.

### `Application.kt`
Ktor module setup:
- On startup, loads all files from the `default/` classpath directory (alphabetically) into `ResourceRegistry` via `loadDefaultResources()`. Works both from an exploded build and from the fat JAR.
- `POST /` replaces all loaded resources; `PATCH /` appends/overrides by top-level key; `DELETE /` resets to defaults. All three accept plain YAML/JSON or multipart bodies.
- Two catch-all routes (`{...}` and `/`) delegate to `handleMatch()`, which calls `RequestMatcher.findMatch()` and writes the `MatchResult` back as an HTTP response.

## Configuration format

A config document is a YAML or JSON object. Top-level keys are arbitrary labels; each maps to a `TreeNode`. Roots are matched in document order.

```yaml
label:
  method:          # strict: key must equal HTTP method
    GET:
      path:        # strict: regex prefix matched against remaining path
        /items:
          query:   # optional: regex containment match on query string
            "userId=1":
              code: 200
              headerOut:
                Content-Type: application/hal+json
              resource:
                filtered: true
            '':    # empty key matches empty query string
              code: 200
              resource:
                all: true
          body:    # optional: regex containment match on request body
            '"type":"admin"':
              code: 403
          headerIn:  # optional: header-name → value-regex → child
            Accept:
              "application/hal\\+json":
                headerOut:
                  Content-Type: application/hal+json
```

Key rules:
- Response fields (`code`, `resource`, `headerOut`) accumulate as the tree is descended; child values override parent values for `code` and `resource`; `headerOut` maps are merged.
- An empty-string key in `path` matches only when no path segments remain; an empty-string key in `query` matches only an empty query string.
- All regex patterns are compiled eagerly at load time — invalid patterns produce a 400 at `POST /`.

## Default config and tests

`mockinghal/src/main/resources/default/` contains two YAML/JSON files that are bundled into the fat JAR and loaded on startup: the MockingHAL self-description API and its config-guide docs.

`mockinghal/src/test/resources/` contains example configs:
- `haldish/hal_demo.yaml` — pedagogical HAL API demo (used by `haldish.sh`)
- `jsonplaceholder/jsonplaceholder.yaml` + `jsonplaceholder_1.yaml` — JSONPlaceholder-style mock with HAL links

## HALDiSh dependency

The bash tests depend on a `haldish` executable resolved via Gradle configuration `haldish` (artifact `com.helpchoice:haldish:2.2.4@run`). Running `./gradlew :mockinghal:setup` downloads it and installs it under `mockinghal/build/haldish/`. The `env.sh` file in that directory is sourced by the test scripts to set up the HALDiSh shell library.
