# IntelliJ IDEA 插件后台 PSI 扫描最佳实践调研

## 调研背景

本项目（myhope-sync-plugin）在启动后异步预热时，后台线程使用 `runReadAction` 持读锁 20-30 秒遍历全部 Controller 并做深度 PSI 解析，导致 EDT 需要写锁时被阻塞，界面冻结 20+ 秒。

**核心问题**：单个长时间 `runReadAction` 阻塞了 EDT 的写操作，导致 UI 冻结。

## 关键发现

### 1. IntelliJ Platform SDK 处理长耗时后台 PSI 解析的标准模式

**官方推荐：分片（Chunking）+ 非阻塞读操作**

根据 JetBrains 官方文档 [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)：

> "BGT shouldn't hold read locks for a long time. The reason is that if EDT needs a write action (for example, the user types something in the editor), it must be acquired as soon as possible. Otherwise, the UI will freeze until all BGTs have released their read actions."

**标准做法**：
- **拆分读操作**：将大的 `runReadAction` 拆成多个小的 `runReadAction`（每个 file / 每个 module 一个），让 EDT 的小任务能插到间隙获得写锁。
- **使用非阻塞读操作**：`ReadAction.nonBlocking()` 或 Kotlin 协程的 `readAction`（非阻塞读操作）。

**关键区别**：
- `runReadAction`（阻塞读操作）：持有读锁直到完成，写操作必须等待。
- `ReadAction.nonBlocking()`（非阻塞读操作）：当写操作挂起时，读操作会被取消并重启，不会阻塞写操作。

### 2. 如何让长时间 read action 不阻塞 EDT 的写锁

**推荐做法：拆分 + 非阻塞**

根据 [UI Freezes and the Dangers of Non-Cancellable Read Actions](https://blog.jetbrains.com/platform/2026/03/ui-freezes-and-the-dangers-of-non-cancellable-read-actions-in-background-threads/)：

> "Note that the following APIs are not cancellable by default:
> - `ReadAction.compute { … }`
> - `Application.runReadAction { … }`
> - `runReadAction { … }`
>
> When such a read action runs for a long time in a background thread, it can block write actions (PSI changes, workspace model updates, editor changes)."

**具体方案**：
1. **拆分读操作**：将 3500 接口的全量扫描拆分成多个小批次（如每 100 个接口一个读操作）。
2. **使用 `ReadAction.nonBlocking()`**：Java 代码使用 `ReadAction.nonBlocking(Callable).executeSynchronously()`。
3. **使用 Kotlin 协程**：Kotlin 代码使用 `readAction { ... }`（非阻塞读操作）。

**平台公平性**：
- **写操作优先级更高**：当写操作挂起时，新的读操作无法启动，现有的非阻塞读操作会被取消。
- **无公平性保证**：平台不保证读操作和写操作的公平调度，写操作总是优先。

### 3. 插件做索引/扫描的机制

#### 3.1 `AnnotatedElementsSearch`
- **用途**：搜索带有特定注解的元素（如 `@RestController`）。
- **局限性**：
  - 可能会触发 AST 加载（尤其是 Kotlin 文件），导致性能问题。
  - 对于 Kotlin 文件，可能抛出 "Access to tree elements not allowed" 错误。
- **适用场景**：Java 项目，且注解在存根中可见。

#### 3.2 `FileBasedIndex`
- **官方推荐的索引机制**：可以在后台维护接口索引，避免每次遍历 PSI。
- **优点**：
  - 后台自动更新，不需要手动扫描。
  - 支持增量更新。
  - 可以存储自定义键值对。
- **缺点**：
  - 需要实现 `FileBasedIndexExtension`。
  - 首次构建索引需要时间。
- **适用场景**：需要频繁查询的注解/接口信息。

#### 3.3 `StubIndex`
- **基于存根的索引**：适用于基于存根的 PSI 元素。
- **优点**：比 `FileBasedIndex` 更轻量。
- **缺点**：需要实现 `StubIndexExtension`。

#### 3.4 成熟插件的实现方式
- **RequestMapper**：使用 PSI 扫描 `@RequestMapping` 等注解，但可能没有做全量预热。
- **Explyt Spring**：运行轻量级应用实例，通过 javaagent 提取真实的 bean 元数据。
- **Spring Boot 插件**：使用 `AnnotatedElementsSearch` 或自定义索引。

### 4. ProgressIndicator 与可取消

**标准做法**：
- 使用 `ProgressManager` + `Task.Backgroundable` 运行可取消的后台任务。
- 定期调用 `ProgressManager.checkCanceled()` 检查取消状态。
- 避免在 EDT 上显示模态进度条。

**示例代码**：
```kotlin
object : Task.Backgroundable(project, "Scanning Controllers", true) {
    override fun run(indicator: ProgressIndicator) {
        // 分片处理
        for (batch in controllerBatches) {
            indicator.checkCanceled()
            indicator.text = "Processing batch ${batch.index}..."
            
            // 使用非阻塞读操作
            ReadAction.nonBlocking(Callable {
                // 处理单个批次
            }).executeSynchronously()
        }
    }
}.queue()
```

### 5. 启动后预热 vs 懒加载

**官方推荐：懒加载**

根据 [Background Processes](https://plugins.jetbrains.com/docs/intellij/background-processes.html)：

> "Use `ProgressManager.run(Task.Backgroundable)` to execute work that needs to be visible to users. Including work that consumes CPU over a noticeable period."

**对比**：

| 方案 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| **启动后预热** | 首次使用时无需等待 | 启动时可能卡顿，浪费资源 | 必须立即可用的功能 |
| **懒加载** | 启动快，按需加载 | 首次使用时可能需要等待 | 大多数场景 |

**对于 3500 接口的全量扫描**：
- **不推荐启动后预热**：20-30 秒的扫描时间太长，会导致明显的启动卡顿。
- **推荐懒加载**：首次打开 UI 时才在后台扫描 + 进度提示。

### 6. EDT 卡在 acquireWriteIntentPermit 的结论

**典型问题**：长读锁阻塞写锁。

**权威建议**（来自 JetBrains 官方文档）：
1. **避免长时间读锁**：将大的读操作拆分成多个小的读操作。
2. **使用非阻塞读操作**：`ReadAction.nonBlocking()` 或 Kotlin 协程的 `readAction`。
3. **定期检查取消**：在读操作中定期调用 `ProgressManager.checkCanceled()`。

**不需要看 thread dump**：基于这个现象的结论已经很明确——长读锁阻塞了写锁。

## 方案选项清单

### 方案 1：分片 + 非阻塞读操作（最推荐）

**做法**：
- 将 3500 接口的全量扫描拆分成多个小批次（如每 100 个接口一个读操作）。
- 使用 `ReadAction.nonBlocking()` 或 Kotlin 协程的 `readAction` 处理每个批次。
- 使用 `Task.Backgroundable` 运行后台任务，提供进度提示。

**优点**：
- 符合官方推荐的最佳实践。
- 不会阻塞 EDT 的写操作。
- 可以提供进度提示，用户体验好。

**缺点**：
- 需要重构现有代码。
- 实现相对复杂。

**是否适用**：✅ 适用于 3500 接口全量扫描场景。

**落地要点**：
1. 使用 `Task.Backgroundable` 运行后台任务。
2. 将接口列表拆分成批次。
3. 每个批次使用 `ReadAction.nonBlocking(Callable).executeSynchronously()` 处理。
4. 定期调用 `ProgressManager.checkCanceled()` 检查取消状态。

### 方案 2：懒加载 + 按需扫描

**做法**：
- 启动时不扫描，首次打开 UI 时才在后台扫描。
- 使用 `Task.Backgroundable` 运行后台任务，提供进度提示。
- 扫描结果缓存到内存或磁盘。

**优点**：
- 启动快，用户体验好。
- 实现相对简单。

**缺点**：
- 首次打开 UI 时可能需要等待。
- 需要处理缓存失效问题。

**是否适用**：✅ 适用于大多数场景，特别是启动时间敏感的场景。

**落地要点**：
1. 在 UI 打开时触发扫描任务。
2. 使用 `Task.Backgroundable` 运行后台任务。
3. 扫描结果缓存到内存（如 `ConcurrentHashMap`）。
4. 文件变更时清除相关缓存。

### 方案 3：自定义 `FileBasedIndex`

**做法**：
- 实现 `FileBasedIndexExtension`，在后台维护接口索引。
- 启动时利用平台的索引机制自动更新。
- 查询时直接从索引获取数据。

**优点**：
- 利用平台的索引机制，性能最好。
- 支持增量更新。
- 不需要手动扫描。

**缺点**：
- 实现复杂，需要理解 `FileBasedIndex` 的工作原理。
- 首次构建索引需要时间。
- 需要处理索引失效问题。

**是否适用**：✅ 适用于需要频繁查询的场景，但实现成本高。

**落地要点**：
1. 实现 `FileBasedIndexExtension`，定义键值对。
2. 实现 `DataIndexer`，从文件内容提取数据。
3. 注册扩展点。
4. 查询时使用 `FileBasedIndex.getValues()` 或 `FileBasedIndex.processValues()`。

### 方案 4：使用 `AnnotatedElementsSearch` + 缓存

**做法**：
- 使用 `AnnotatedElementsSearch` 搜索 `@RestController` 和 `@RequestMapping` 注解。
- 结果缓存到内存。
- 文件变更时清除相关缓存。

**优点**：
- 实现简单。
- 利用平台的搜索机制。

**缺点**：
- 可能会触发 AST 加载，性能问题。
- 对于 Kotlin 文件可能有问题。

**是否适用**：⚠️ 适用于 Java 项目，但需要测试性能。

**落地要点**：
1. 使用 `AnnotatedElementsSearch.searchPsiClasses()` 搜索 `@RestController`。
2. 使用 `AnnotatedElementsSearch.searchPsiMethods()` 搜索 `@RequestMapping`。
3. 结果缓存到 `ConcurrentHashMap`。
4. 文件变更时清除相关缓存。

## 最终推荐

**对于 3500 接口的全量扫描场景，最推荐的方案是：**

1. **懒加载 + 分片 + 非阻塞读操作**（方案 1 + 方案 2 的组合）：
   - 首次打开 UI 时才在后台扫描。
   - 将扫描拆分成多个小批次。
   - 使用 `ReadAction.nonBlocking()` 处理每个批次。
   - 提供进度提示。

2. **长期方案**：考虑实现自定义 `FileBasedIndex`（方案 3），以获得最佳性能。

## 权威链接

1. [Threading Model - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
2. [UI Freezes and the Dangers of Non-Cancellable Read Actions](https://blog.jetbrains.com/platform/2026/03/ui-freezes-and-the-dangers-of-non-cancellable-read-actions-in-background-threads/)
3. [Background Processes - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/background-processes.html)
4. [Coroutine Read Actions - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html)
5. [File-Based Indexes - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/file-based-indexes.html)
6. [Changes in Threading Model in the IntelliJ Platform 2026.1](https://platform.jetbrains.com/t/changes-in-threading-model-in-the-intellij-platform-2026-1/3800)
7. [AnnotatedElementsSearch - IntelliJ Platform](https://android.googlesource.com/platform/tools/idea/+/0ecdb5090b29e51adc5322347bafda41760653ea/java/java-indexing-api/src/com/intellij/psi/search/searches/AnnotatedElementsSearch.java)
8. [SonarLint PR: Fix IDE UI freezes caused by non-cancellable read actions](https://github.com/SonarSource/sonarlint-intellij/pull/1803)
