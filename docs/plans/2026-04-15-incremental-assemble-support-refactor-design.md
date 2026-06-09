# Incremental Assemble Support Refactor Design

**Problem**

`plugin/src/main/java/org/jetbrains/teamcity/IncrementalAssembleSupport.java` сейчас совмещает четыре разные ответственности:
- сравнение incremental state и объяснение miss;
- snapshotting файлов и директорий;
- чтение/запись `.assemble-state.properties`;
- сбор Maven-specific входов из `MavenProject`, `MavenSession`, reactor dependencies и `teamcity-maven-plugin` configuration.

Из-за этого код тяжело читать и менять, а большая часть поведения покрывается только integration-style тестами через Maven harness.

**Goal**

Сделать incremental logic проще для чтения и локальных изменений, не меняя поведение. После рефакторинга большая часть логики должна тестироваться обычными unit-test без полного Maven цикла.

## Constraints

- Алгоритм incremental hit/miss должен остаться прежним.
- Формат `.assemble-state.properties` должен остаться совместимым.
- Существующие Maven-harness regression tests должны продолжать проходить.
- Maven-specific код должен остаться в одном адаптерном слое, а не быть размазан по нескольким utility-классам.

## Approaches Considered

### 1. Extract pure core only

Вынести только `isUpToDate()` и `describeDifference()` в отдельный core-класс, остальное оставить в `IncrementalAssembleSupport`.

Плюсы:
- самый маленький patch;
- низкий риск.

Минусы:
- файловый snapshotting и Maven wiring всё равно останутся смешанными;
- unit-test появятся только для части логики;
- читаемость улучшится ограниченно.

### 2. Split by responsibility and keep a thin facade

Разделить текущий класс на:
- `IncrementalAssembleCore`
- `FileSnapshotter`
- `IncrementalStateStore`
- `MavenIncrementalInputsCollector`
- тонкий `IncrementalAssembleSupport`

Плюсы:
- естественные границы ответственности;
- простые unit-test для core/store/snapshotter;
- существующий внешний API можно сохранить почти без изменений.

Минусы:
- больше файлов;
- нужно аккуратно зафиксировать ownership методов, чтобы не получить новую путаницу.

### 3. Replace the facade entirely

Сразу убрать `IncrementalAssembleSupport` и переключить `AssemblePluginMojo` на несколько новых классов напрямую.

Плюсы:
- самый “чистый” финальный вид.

Минусы:
- больше изменений за один раз;
- выше риск случайно поменять wiring;
- сложнее сравнивать поведение до и после.

**Recommendation:** вариант 2. Он даёт хороший прирост читаемости и unit-test coverage без лишнего риска.

## Target Structure

### `IncrementalAssembleCore`

Pure logic layer без `MavenProject`, `MavenSession` и `Files.walk(...)`.

Ответственность:
- сравнение previous/current state;
- расчёт fingerprint входов;
- проверка существования outputs;
- построение объяснения для `incremental miss`.

Сюда должны перейти:
- `isUpToDate(...)`
- `describeDifference(...)`
- `sameInputs(...)`
- `describeInputDifference(...)`
- `collectOutputTimestamps(...)`
- `buildInputFingerprint(...)`
- `calculateLatestInputTs(...)`
- value objects `IncrementalState`, `InputState`, `OutputState`

Этот слой должен принимать уже готовые snapshot data и не знать, как именно они были собраны.

### `FileSnapshotter`

Filesystem-only слой, который работает с `Path` и строит snapshot для одного файла или дерева файлов.

Ответственность:
- обход дерева файлов;
- расчёт `maxTs`, `count`, `totalSize`;
- special handling для tool inputs;
- фильтрация служебных файлов, которые не должны влиять на incremental state.

Сюда должны перейти:
- `snapshotPath(...)`
- `populateFileState(...)`
- `populateToolInputState(...)`
- `shouldIgnore(...)`
- небольшие file helper-методы, которые нужны только snapshotting logic

Этот слой не должен знать ничего про Maven dependencies, reactor modules или `extras`.

### `IncrementalStateStore`

Persistence layer для `.assemble-state.properties`.

Ответственность:
- загрузка previous state;
- сохранение current state;
- сериализация/десериализация inputs и outputs;
- совместимость формата.

Сюда должны перейти:
- `loadState()`
- `saveState()`
- `loadProperties()`
- `loadInputs()`
- `loadOutputs()`
- `storeInputs()`
- `storeOutputs()`
- `getStateFile()`

Этот слой не должен содержать decision logic.

### `MavenIncrementalInputsCollector`

Adapter layer между Maven model и incremental core.

Ответственность:
- строить `IncrementalState` для текущего `MavenProject`;
- собирать `project.artifact`, `extras`, descriptor paths и output directories;
- разрешать reactor dependencies;
- собирать `configStamp`.

Сюда должны перейти:
- `collectCurrentState()`
- `buildConfigStamp()`
- `appendAgentConfig(...)`
- `appendServerConfig(...)`
- `appendDescriptor(...)`
- `appendExtras(...)`
- `appendMap(...)`
- `appendList(...)`
- `appendConfigValue(...)`
- `addDependencyInputs(...)`
- `createExternalSnapshotArtifactState(...)`
- `createReactorArtifactState(...)`
- `findReactorProject(...)`
- `findReactorProjectForPath(...)`
- `buildReactorPathKey(...)`
- `addStringPathInputs(...)`
- `addExtrasInputs(...)`
- `addExtraInput(...)`
- `addFileTreeInput(...)`
- `addToolUnpackedInput(...)`
- `getProjectArtifactPath()`
- `shouldTrackProjectArtifact()`
- helpers around GAV/path conversion

Этот слой должен зависеть от `FileSnapshotter` и core value objects, но не содержать comparison logic.

### `IncrementalAssembleSupport`

Фасад совместимости для текущего вызова из `AssemblePluginMojo`.

Ответственность:
- собрать current state через collector;
- загрузить previous state через store;
- вызвать `IncrementalAssembleCore`;
- отдать наружу `loadState`, `saveState`, `isUpToDate`, `describeDifference`.

После рефакторинга этот класс должен стать коротким orchestration layer, а не местом, где живёт весь incremental algorithm.

## Testing Strategy

### New unit tests

#### `IncrementalAssembleCoreTest`

Проверить без Maven harness:
- hit при полном совпадении state;
- miss при отсутствии previous state;
- miss при изменении одного input;
- miss при отсутствии output файла;
- точное сообщение `describeDifference(...)` для основных сценариев.

#### `FileSnapshotterTest`

Проверить через temp directories:
- snapshot одного файла;
- snapshot directory tree;
- ignored files/directories;
- tool-specific snapshotting;
- равенство snapshot при одинаковом `mtime/count/size`.

#### `IncrementalStateStoreTest`

Проверить:
- round-trip `save -> load`;
- сохранение всех kinds input’ов;
- пустые outputs;
- стабильную загрузку старого формата state file.

### Existing integration tests to keep

Оставить как regression layer:
- `AssemblePluginMojoTestCase#testIncrementalAssembleSkipsRepackingAndReattachesArtifacts`
- `AssemblePluginMojoTestCase#testIncrementalAssembleSkipsWhenExtraSourceBelongsToReactorProject`
- `AssemblePluginMojoTestCase#testIncrementalAssembleSkipsWhenExtraSourceBelongsToCurrentProject`
- `ModuleWarTestCase#testIncrementalAssembleSkipsWhenWarArtifactTimestampChanges`

Идея в том, что unit tests должны покрыть большую часть поведения, а Maven harness останется только для wiring и end-to-end regression.

## Refactoring Order

1. Вынести model objects и `IncrementalAssembleCore` без изменения алгоритма.
2. Вынести `IncrementalStateStore` и перевести фасад на него.
3. Вынести `FileSnapshotter`.
4. Вынести `MavenIncrementalInputsCollector`.
5. Сжать `IncrementalAssembleSupport` до thin facade.
6. Добавить unit-test на новые слои.
7. Прогнать существующие integration tests, чтобы подтвердить сохранение поведения.

Такой порядок минимизирует риск, потому что сначала выносятся pure layers, а Maven wiring меняется последним.

## Success Criteria

- `IncrementalAssembleSupport` заметно короче и читабельно оркестрирует зависимости между слоями.
- Основные правила incremental comparison проверяются обычными unit-test.
- Snapshotting можно тестировать без `MojoRule` и без полного Maven цикла.
- Existing integration tests продолжают проходить без изменения ожидаемого поведения.
