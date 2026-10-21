# AGENTS.md

IntelliJ IDEA 插件（Kotlin，包 `com.myhope.sync`，插件 id `com.myhope.sync`，版本 2.2.0）：把 Spring Boot (Java) Controller 的 REST 端点同步到 myhope-pr-server 的分支快照（`/pr-api/` + X-API-Token + 实例绑定）。

## 构建与验证

无 `gradlew`、无 CI。所有 gradle 命令前必须先切 JDK 21，否则配置阶段报 JDK8 错误：

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env   # .sdkmanrc: java=21.0.11-amzn
gradle buildPlugin                                    # 首选验证：build/distributions/myhope-sync-plugin-2.2.0.zip
gradle runIde --no-configuration-cache                # 沙箱手动验收；缺该参数会挂
gradle test --tests '*BuildOpsSyncVerificationTest'   # 唯一测试，约 10s~1m40s
gradle verifyPlugin                                   # 二进制兼容性（IDE 固定 2026.1）
```

- 唯一的测试是 `src/test/java/.../BuildOpsSyncVerificationTest.java`：连真实服务端的**只读**集成测试（拉快照 + 跑 `buildOps` 断言，不 push）。凭据走 `MH_TOKEN` / `MH_INSTANCE_ID` / `MH_ACCESS_INSTANCE_ID` / `MH_USER_APP_ID` / `MH_WORKSPACE_ID` / `MH_BRANCH_ID` 等环境变量，缺失时 `Assume` 自动跳过（skipped 视为通过）。变量清单见 `tests/BuildOpsSyncVerificationTest.md`。
- 无其它单测；不要为 UI/PSI 逻辑补需要 IDE 环境的测试，那类改动只能 `runIde` 手动验收。
- 构建产物 `.gradle/`、`build/`、`.intellijPlatform/`、`.kotlin/`、`.idea/`、`.planning/` 均已 gitignore。

## 架构

数据流：`toolwindow/MyHopeSyncPanel`（Tool Window UI）→ `service/SyncService.syncToMyHope`（编排）→ `psi/SpringControllerParser`（PSI 扫描注解）→ `myhope/MyHopeDataConverter`（请求 JSON / 快照 items / hash / buildOps）→ `myhope/MyHopeClient`（REST）。

- **接口搜索链路**独立于同步：`action/SearchEndpointAction`（Ctrl+\）→ `toolwindow/EndpointSearchPopup` → `index/EndpointSearchIndex`（按 Module 缓存 ControllerGroup）→ `SpringControllerParser`。`index/EndpointSearchStartupActivity` 是 `ProjectManagerListener`（注册在 plugin.xml 的 `<projectListeners>`），项目打开后异步预热索引。
- 右键 `Copy Full URL / Path / cURL / to JSON` 由 `action/` 下的 `EndpointUrlAction` 实现，走 `SpringControllerParser` 的同一套解析（不重复实现路径拼接）。
- 同步模型：分支 + 工作空间 + 快照 items。每个 Controller 分组 → collection item，每个端点 → request item。
- 同步状态：本地 hash vs 持久化 hash，key = `Controller:HTTP_METHOD:path`；路径/方法变更 → key 变 → UNSYNCED，仅参数变更 → MODIFIED。
- 分支按名匹配/创建（`generateEntityId("br", branchName)`）；工作空间按 `workspaceId` 匹配，无则取第一个（isDefault 优先）。

## 同步协议约束（改动核心，别猜）

- 写入**只能**走增量 `client.pushOplog(...)`：`buildOps` 生成 op，≤500/批，409 `occ_conflict` 用 `server_max_seq` 更新水位重推 ≤3 次，无 ops 不推送。**禁止调用全量推送/导入类接口**（分支为空时 buildOps 自然生成全量 create）。
- 判定语义：request 按 **method + fullPath** 匹配（不看 id，且只在同一 parent 集合内匹配）；collection 按 **title**（限目标目录子树内）。陈旧实体（同 method+url、不同 id）→ update 服务端 id，**绝不新建、绝不 delete、绝不 move**（op 中不允许出现 `move_entity`）。
- 水位 seq 不本地持久化，每次同步实时 `getOplogHead`（多实例一致）。
- 认证两个 header：`X-API-Token: api-tok_xxx` + `X-MyHope-Instance-ID: mh_xxx`（`AppSettings.getOrCreateInstanceId()`，每实例唯一）。
- 三层隔离：`access_instance_id`(acc_) / `user_app_id`(app_) / `workspace_id`(ws_)；POST 走 body，GET 走 query。
- 快照 items 有字段白名单，服务端保留字段禁带（`prev_entity_id`/`next_entity_id`/`parent_id` 等由服务端维护）；`id`/`parent_id`/`type` 是 items 合法字段但**不是 op payload 字段**。
- 确定性 entity_id：集合 `generateEntityId("hc", 全限定类名)`、请求 `generateEntityId("hr", "METHOD:fullPath")`（SHA-256 前 12 hex）。
- 序列化：**不要**用 Gson 排除 null 字段（服务端允许 `body.contentType`/`auth` 为 null，旧版 1.2.4 踩过）。反序列化 `SnapshotItem` 必须注册 `ItemTypeJsonAdapter`，否则 `type` 解析失败。

## 配置分层（易错）

- `settings/AppSettings`（applicationService，全局 `myhope-sync-settings.xml`）与 `settings/ProjectAppSettings`（projectService，项目 `.idea/myhope-sync-project.xml`）并存。
- 业务读取一律经 `settings/EffectiveSettings.kt` 的 `getEffectiveSettings(project)`：账号级字段**项目级非空才覆盖**，空则回落全局；同步目标（workspace/branch/target collection/autoPublishPr 等）只存项目级。
- 绑定回写走 `writeBackBinding`：项目级显式覆盖了 serverUrl/apiToken 才写项目级，否则写全局。不要绕过它直接写 `AppSettings`。
- 不要给 `ProjectAppSettings` 加 `getOrCreate*`；`instanceId` 全局单例生成。

## PSI / 线程坑（真实踩过）

- **长读锁会冻 EDT**：后台全量 PSI 解析必须用 `SpringControllerParser.parseModuleChunked`（分片、批间让出、可取消），不要写单个大 `runReadAction`。背景与依据见 `docs/research/psi-scan-best-practices.md`。
- **本项目 `AnnotatedElementsSearch` 索引返回 0**，Controller 只能靠降级全文件遍历找到，故预热必须 `allowFallback=true`。
- `EndpointSearchIndex` 只缓存"允许降级且解析成功"的结果；把空结果/降级解析结果写缓存会永久污染后续搜索（搜不到接口且不自愈）。
- 跳转源码（`EndpointSearchPopup` 的导航）必须在 `WriteIntentReadAction.run { }` 里执行，用 `ReadAction` 会 Read access 异常 / 死锁卡死 IDE。
- 搜索结果用 2s 防抖（`SEARCH_DEBOUNCE_MS`）+ 每批 300 行追加渲染，避免一次性渲染大量 HTML 行卡住 EDT。
- **UI 尺寸必须走 `JBUI.scale(...)` / `JBUI.size(...)`，不要写裸像素**。X11 是 IDE-managed HiDPI（userScale=2，AWT transform=1），Wayland 是 JRE-managed（userScale=1，AWT transform=2）；裸尺寸在 Wayland 下会被 AWT 再放大一倍，表现为弹窗超宽、列表行变高。回填尺寸时也不能用已缩放过的 `component.preferredSize` 再缩放，会翻倍。
- **`setMovable(true)` 对自绘标题栏的弹窗无效**：`AbstractPopup.setIsMovable` 只在 `myCaption != null`（即调过 `setTitle`）时才装 `WindowMoveListener`。无标题弹窗要自己 `WindowMoveListener().installTo(titleBar)`（且要装到实际接收鼠标事件的最深组件上）；不要自己监听 drag 调 `setLocation`——Wayland 不允许客户端定位顶层窗口，必须走平台的监听器（内部用 JBR `WindowMoveService`）。

## 约定

- UI 文案全部走 `resources/messages/MyHopeSyncBundle.properties` + `MyHopeSyncBundle_zh.properties`，经 `util/I18n.message(project, key)` 取（语言取自有效配置）。**新增文案必须两个文件都加**，不要在 Kotlin 里硬编码用户可见字符串。
- 改版本号：`build.gradle.kts` 的 `version` 与 `pluginConfiguration.version`、`changeNotes`、`plugin.xml` 的 `<version>`/`<change-notes>` 一起改。注意打包时 `patchPluginXml` 会用 build.gradle.kts 的 description/changeNotes **覆盖** plugin.xml，所以 `changeNotes` 的权威副本在 `build.gradle.kts`（`build/tmp/patchPluginXml/plugin.xml` 可核对合并结果）。
- 新增 Action / service / listener 必须同步注册到 `src/main/resources/META-INF/plugin.xml`，否则不会加载。
- `ItemType` 等新枚举参与 JSON 时，确认是否需要在 `MyHopeDataConverter` 里补 JsonAdapter。

## 文档与历史包袱

- `README.md`、`README_for_ai.md`、根目录 `plan.md` / `task_plan.md` / `findings.md` / `progress.md` 含**过时内容**：仍有 Hoppscotch 阶段描述、已删除的 `MyHopeVersionChecker`/`RequestValidator`/`AppSettingsConfigurable`、README 里默认端口写成 `3170`（代码实际 `AppSettings.serverUrl` 默认 `http://localhost:3462`）。以代码为准，别照抄。
- `cleanup.sh` 是 Hoppscotch 时代的遗留脚本（GraphQL 删集合），与当前 myhope 流程无关，不要运行。
- 联调协议细节见 myhope-pr-server 仓库的 `docs/PR服务客户端接入指南.md`。
