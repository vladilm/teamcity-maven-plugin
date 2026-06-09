# Incremental Assemble Support Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Split `IncrementalAssembleSupport` into smaller layers so incremental logic becomes easier to read and most behavior can be covered by ordinary unit tests without a full Maven cycle.

**Architecture:** Keep `IncrementalAssembleSupport` as a thin facade and move comparison logic, file snapshotting, state persistence, and Maven-specific input collection into dedicated classes. Preserve the current incremental behavior and use the existing Maven-harness tests as regression coverage while adding new unit tests for the extracted layers.

**Tech Stack:** Java, Maven plugin code, JUnit/TestNG-style unit tests in the existing plugin test modules, Maven plugin testing harness.

---

### Task 1: Extract incremental core model and decision logic

**Files:**
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/IncrementalAssembleCore.java`
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/IncrementalState.java`
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/InputState.java`
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/OutputState.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java`
- Test: `tests/src/test/java/org/jetbrains/teamcity/incremental/IncrementalAssembleCoreTest.java`

**Step 1: Write a failing core unit test**

Add tests that build `IncrementalState` objects in memory and assert:
- identical states are up to date;
- missing previous state is a miss;
- changed input produces the expected difference string.

**Step 2: Run the targeted core test to verify it fails**

Run: `mvn -pl tests -am -Dtest=IncrementalAssembleCoreTest test`

Expected: FAIL because the extracted core classes do not exist yet.

**Step 3: Extract the value objects**

Move `IncrementalState`, `InputState`, and `OutputState` out of `IncrementalAssembleSupport` into dedicated classes without changing their fields or equality semantics.

**Step 4: Extract comparison logic into `IncrementalAssembleCore`**

Move `isUpToDate`, `describeDifference`, `sameInputs`, input fingerprint calculation, output timestamp collection, and related helper logic into the new core class. Keep the algorithm identical to the current implementation.

**Step 5: Rewire `IncrementalAssembleSupport` to use the core**

Replace in-class decision logic with delegation to `IncrementalAssembleCore`.

**Step 6: Run the targeted core test to verify it passes**

Run: `mvn -pl tests -am -Dtest=IncrementalAssembleCoreTest test`

Expected: PASS.

### Task 2: Extract state persistence

**Files:**
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/IncrementalStateStore.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java`
- Test: `tests/src/test/java/org/jetbrains/teamcity/incremental/IncrementalStateStoreTest.java`

**Step 1: Write a failing persistence unit test**

Create a temp state file, save an `IncrementalState`, load it back, and assert that inputs, outputs, and `configStamp` round-trip unchanged.

**Step 2: Run the targeted store test to verify it fails**

Run: `mvn -pl tests -am -Dtest=IncrementalStateStoreTest test`

Expected: FAIL because `IncrementalStateStore` does not exist yet.

**Step 3: Move properties serialization/deserialization into the store**

Extract state loading and saving logic from `IncrementalAssembleSupport` into `IncrementalStateStore`, preserving the current `.assemble-state.properties` format.

**Step 4: Delegate from the facade**

Update `IncrementalAssembleSupport` to use `IncrementalStateStore` for `loadState` and `saveState`.

**Step 5: Run the targeted store test to verify it passes**

Run: `mvn -pl tests -am -Dtest=IncrementalStateStoreTest test`

Expected: PASS.

### Task 3: Extract file snapshotting

**Files:**
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/FileSnapshotter.java`
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/FileSnapshot.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java`
- Test: `tests/src/test/java/org/jetbrains/teamcity/incremental/FileSnapshotterTest.java`

**Step 1: Write a failing snapshotter unit test**

Create temp directories and assert snapshot values for:
- a single file;
- a directory tree;
- ignored metadata/checksum files;
- a tool input directory.

**Step 2: Run the targeted snapshotter test to verify it fails**

Run: `mvn -pl tests -am -Dtest=FileSnapshotterTest test`

Expected: FAIL because `FileSnapshotter` does not exist yet.

**Step 3: Move filesystem snapshotting code**

Extract `snapshotPath`, tree walking, ignored-file filtering, and tool-specific snapshot logic into `FileSnapshotter` and keep `IncrementalAssembleSupport` delegating to it.

**Step 4: Run the targeted snapshotter test to verify it passes**

Run: `mvn -pl tests -am -Dtest=FileSnapshotterTest test`

Expected: PASS.

### Task 4: Extract Maven-specific input collection

**Files:**
- Create: `plugin/src/main/java/org/jetbrains/teamcity/incremental/MavenIncrementalInputsCollector.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java`
- Verify: `plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java`
- Test: `tests/src/test/java/org/jetbrains/teamcity/AssemblePluginMojoTestCase.java`
- Test: `tests/src/test/java/org/jetbrains/teamcity/ModuleWarTestCase.java`

**Step 1: Move `collectCurrentState` and related Maven helpers**

Extract reactor resolution, dependency state construction, config-stamp building, extras handling, and current-project artifact rules into `MavenIncrementalInputsCollector`.

**Step 2: Keep `IncrementalAssembleSupport` as a facade**

Update the facade so it owns only constructor wiring and delegates to:
- collector for current state;
- store for persistence;
- core for decisions.

**Step 3: Run the existing incremental regression tests**

Run: `mvn -pl tests -am -Dtest=AssemblePluginMojoTestCase#testIncrementalAssembleSkipsRepackingAndReattachesArtifacts,AssemblePluginMojoTestCase#testIncrementalAssembleSkipsWhenExtraSourceBelongsToReactorProject,AssemblePluginMojoTestCase#testIncrementalAssembleSkipsWhenExtraSourceBelongsToCurrentProject,ModuleWarTestCase#testIncrementalAssembleSkipsWhenWarArtifactTimestampChanges test`

Expected: PASS.

### Task 5: Verify the refactor end-to-end

**Files:**
- Verify: `plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java`
- Verify: `plugin/src/main/java/org/jetbrains/teamcity/incremental/IncrementalAssembleCore.java`
- Verify: `plugin/src/main/java/org/jetbrains/teamcity/incremental/IncrementalStateStore.java`
- Verify: `plugin/src/main/java/org/jetbrains/teamcity/incremental/FileSnapshotter.java`
- Verify: `plugin/src/main/java/org/jetbrains/teamcity/incremental/MavenIncrementalInputsCollector.java`
- Verify: `tests/src/test/java/org/jetbrains/teamcity/incremental/IncrementalAssembleCoreTest.java`
- Verify: `tests/src/test/java/org/jetbrains/teamcity/incremental/IncrementalStateStoreTest.java`
- Verify: `tests/src/test/java/org/jetbrains/teamcity/incremental/FileSnapshotterTest.java`
- Verify: `tests/src/test/java/org/jetbrains/teamcity/AssemblePluginMojoTestCase.java`
- Verify: `tests/src/test/java/org/jetbrains/teamcity/ModuleWarTestCase.java`

**Step 1: Run the new unit-test layer**

Run: `mvn -pl tests -am -Dtest=IncrementalAssembleCoreTest,IncrementalStateStoreTest,FileSnapshotterTest test`

Expected: PASS.

**Step 2: Run the incremental regression harness tests**

Run: `mvn -pl tests -am -Dtest=AssemblePluginMojoTestCase#testIncrementalAssembleSkipsRepackingAndReattachesArtifacts,AssemblePluginMojoTestCase#testIncrementalAssembleSkipsWhenExtraSourceBelongsToReactorProject,AssemblePluginMojoTestCase#testIncrementalAssembleSkipsWhenExtraSourceBelongsToCurrentProject,ModuleWarTestCase#testIncrementalAssembleSkipsWhenWarArtifactTimestampChanges test`

Expected: PASS.

**Step 3: Run the broader test scope if the targeted tests pass**

Run: `mvn -pl tests -am test`

Expected: PASS, or at minimum no failures in the incremental-related test classes.

**Step 4: Commit**

```bash
git add plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java \
  plugin/src/main/java/org/jetbrains/teamcity/incremental \
  tests/src/test/java/org/jetbrains/teamcity/incremental \
  tests/src/test/java/org/jetbrains/teamcity/AssemblePluginMojoTestCase.java \
  tests/src/test/java/org/jetbrains/teamcity/ModuleWarTestCase.java \
  docs/plans/2026-04-15-incremental-assemble-support-refactor-design.md \
  docs/plans/2026-04-15-incremental-assemble-support-refactor.md
git commit -m "refactor: split incremental assemble support layers"
```
