# Incremental Build Support Coverage Plan

## User Requests

- Analyze the latest `incremental build support` commit and find issues.
- Add reliable unit-level coverage, preferably without a full wrapper-heavy test.
- Introduce a small test framework where each test clearly shows:
  - which POM/fixture is used;
  - which result is expected;
  - that dependencies come from a local repository and tests do not need the internet.
- Add a Makefile target for running tests, parameterized by module and test class/method.
- Cover these incremental inputs explicitly, with positive and negative cases:
  - changed SNAPSHOT dependencies;
  - changed RELEASE dependency versions;
  - added transitive dependencies;
  - filesystem changes;
  - module state changes.
- For module state, first stage can avoid source-directory lifecycle details and check `target` content.
- Add a setting to manually exclude files/directories from the incremental snapshot.
- Do not calculate all snapshots before deciding whether to rebuild.
- Stop incremental checking at the first mismatch, log the reason, and run the normal build.
- Include concrete source information in the miss log so the user can see what changed.
- Order incremental checks from cheap to expensive:
  - config stamp and saved output metadata;
  - full dependency tree identity;
  - direct and transitive dependency changes;
  - SNAPSHOT/release/reactor dependency state;
  - filesystem and module target snapshots.
- Analyze the full dependency tree explicitly; do not rely only on an already-flattened artifact set.
- Keep this plan in a file.

## Decisions

- Keep existing wrapper-style tests for now; do not migrate them in this stage.
- Add a simple fixture for incremental-state tests:
  - module target content;
  - dependency inputs;
  - arbitrary filesystem inputs;
  - output state.
- Avoid modeling `src/main`, `src/main/resources`, and `src/main/webapp` in the first pass.
- Treat `target/classes` or another selected target directory as the module-state input for now.
- Make file-tree snapshots metadata-aware:
  - include relative file paths;
  - include file size;
  - include last modified timestamp.
- Do not read full file contents for hashing:
  - dependency and filesystem snapshots must stay within size/mtime/path checks;
  - same-size/same-mtime content changes are an accepted blind spot for speed.
- Add manual snapshot exclusions through:
  - `teamcity.assemble.incremental.excludes`
- Include the exclusion setting in the incremental config stamp so changing the policy invalidates old state.
- RELEASE dependencies remain immutable by GAVTC:
  - changing the release version changes the input identity;
  - changing a local file behind the same release coordinate should not matter.
- SNAPSHOT dependencies must include artifact file snapshot details:
  - path/existence;
  - timestamp/size.
- Incremental verification must be staged and early-stop:
  - do cheap config/output checks before filesystem snapshots;
  - compare full dependency graph identity before file-tree snapshots;
  - log the first mismatch and skip later verification stages;
  - if verification misses, run the build normally and collect a full state afterward for the next run.
- Incremental miss diagnostics should include the input identity and changed metadata fields:
  - path/existence;
  - last modified timestamp;
  - file count;
  - total size;
  - file-tree details when available.

## Coverage Matrix

| Area | Positive Case | Negative Case | Status |
| --- | --- | --- | --- |
| SNAPSHOT dependency | Same coordinate, artifact size/mtime changes -> miss | Same coordinate, same artifact size/mtime -> up-to-date | In progress |
| RELEASE dependency | Release version changes, for example `1.0` -> `1.1` -> miss | Same release coordinate, local file changes -> up-to-date | In progress |
| Transitive dependency | Add a new transitive dependency input -> miss | Same dependency graph -> up-to-date | In progress |
| Module target content | `target/classes` file size/mtime/path changes -> miss | No target content size/mtime/path change -> up-to-date | In progress |
| Filesystem input | Tracked filesystem size/mtime/path changes -> miss | Excluded filesystem path changes -> up-to-date | In progress |
| Output state | Output timestamp changes -> miss with useful diagnostics | Output exists and timestamp matches -> up-to-date | Implemented |
| Snapshot details | Rename same-size/same-mtime file -> miss | Ignored/generated files change -> up-to-date | Implemented |
| Early stop | First cheap mismatch stops later snapshot checks and logs reason | Matching cheap checks continue into later stages | Planned |
| Dependency tree | Added/removed/changed direct or transitive node -> miss | Same tree identity -> continue/up-to-date | Planned |

## Implementation Notes

- `FileSnapshotter` should support global ignored path patterns in addition to existing explicit ignored file names.
- Exclusion matching should work for:
  - exact relative paths;
  - directory names;
  - path prefixes;
  - simple `*` glob patterns.
- `ReactorInputStateResolver#createExternalSnapshotArtifactState` should not overwrite snapshot details with only GAVTC.
- `MavenIncrementalInputsCollector` should accept the incremental excludes setting and pass it into the config stamp.
- The simple fixture should stay test-only and not depend on Maven session setup.
- The production check should not use the old all-inputs-at-once flow for incremental decisions.
- The production check should expose stages explicitly enough to test that later expensive stages are not touched after an earlier mismatch.
- The production log should use the same early-check miss reason that unit tests assert.

## Verification Commands

```bash
make test MODULE=tests TEST=IncrementalAssemblySourcesFixtureTest
make test MODULE=tests TEST=FileSnapshotterTest,IncrementalAssembleCoreTest
make test MODULE=tests TEST=TeamCityMojoScenarioConceptTest,IncrementalAssembleCoreTest,IncrementalStateStoreTest,FileSnapshotterTest,IncrementalConfigStampBuilderTest,ReactorInputStateResolverTest,MavenIncrementalInputsCollectorTest
make test MODULE=tests
```

## Open Follow-Ups

- Decide whether old `BasePluginTestCase` should also enforce offline local repository behavior.
- Decide whether source directories should become first-class tracked inputs:
  - `src/main/java`;
  - `src/main/resources`;
  - `src/main/webapp`;
  - generated sources/resources.
- Decide whether a future opt-in deep content hash mode is needed for rare same-size/same-mtime changes.
