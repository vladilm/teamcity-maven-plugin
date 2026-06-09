# Remove Version From TeamCity Plugin Jar Names Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `removeVersionFromJar` parameters to both `<agent>` and `<server>` configurations of `teamcity-maven-plugin` so all jar files placed by each workflow can omit Maven version suffixes.

**Architecture:** Extend `Agent` and `Server` parameter models with a new boolean flag, then route jar naming through one shared normalization layer in `WorkflowUtil`/`ResolvedArtifact`. Keep default behavior unchanged and make stripping opt-in per workflow.

**Tech Stack:** Maven plugin Java code, JUnit tests, Maven plugin testing harness.

---

### Task 1: Add failing tests for agent and server workflows

**Files:**
- Modify: `tests/src/test/java/org/jetbrains/teamcity/AssemblePluginMojoTestCase.java`
- Modify: `tests/src/test/java/org/jetbrains/teamcity/ModuleWarTestCase.java`

**Step 1: Write a failing agent test**

Set `mojo.getAgent().setRemoveVersionFromJar(true)` and assert copied jars in the exploded agent layout are versionless.

**Step 2: Run the targeted test to verify it fails**

Run: `mvn -pl tests -Dtest=AssemblePluginMojoTestCase#testMakeAgentArtifactWithoutVersionInJarNames test`

Expected: FAIL because current workflow still writes versioned jar names.

**Step 3: Write a failing server test**

Set `mojo.getServer().setRemoveVersionFromJar(true)` and assert the exploded server layout uses versionless jar names while the rest of the layout stays intact.

**Step 4: Run the targeted test to verify it fails**

Run: `mvn -pl tests -Dtest=ModuleWarTestCase#testServerArtifactWithoutVersionInJarNames test`

Expected: FAIL because current workflow still writes versioned jar names.

### Task 2: Implement shared jar-name normalization

**Files:**
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/Agent.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/Server.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/data/ResolvedArtifact.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/agent/WorkflowUtil.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/agent/AgentPluginWorkflow.java`
- Modify: `plugin/src/main/java/org/jetbrains/teamcity/ServerPluginWorkflow.java`

**Step 1: Add new configuration parameters**

Add `removeVersionFromJar` booleans to `Agent` and `Server`.

**Step 2: Route jar naming through one shared helper**

Teach `ResolvedArtifact`/`WorkflowUtil` to derive the destination file name with optional version stripping for jar artifacts.

**Step 3: Apply the helper from both workflows**

Pass the corresponding `<agent>` or `<server>` flag into dependency-copy and jar-path creation code so all jars laid out by that workflow respect the option.

### Task 3: Verify behavior

**Files:**
- Verify: `tests/src/test/java/org/jetbrains/teamcity/AssemblePluginMojoTestCase.java`
- Verify: `tests/src/test/java/org/jetbrains/teamcity/ModuleWarTestCase.java`

**Step 1: Run the targeted tests**

Run: `mvn -pl tests -Dtest=AssemblePluginMojoTestCase#testMakeAgentArtifactWithoutVersionInJarNames,ModuleWarTestCase#testServerArtifactWithoutVersionInJarNames test`

Expected: PASS.

**Step 2: Run the broader plugin test suite**

Run: `mvn -pl tests test`

Expected: PASS, proving default behavior did not regress.
