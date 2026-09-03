# AGENTS.md

IntelliJ IDEA 插件（Kotlin）：把 Spring Boot (Java) Controller 的 REST 端点同步到 myhope-pr-server 的分支快照（X-API-Token + 实例绑定）。`group=com.myhope.sync`，插件 id `com.myhope.sync`。插件版本号 2.0.0（见 `build.gradle.kts` 的 `version`，与 `plugin.xml` 中版本可能滞后，改版时以 build.gradle.kts 为准）。

## 构建与验证

- 无 `gradlew`，直接用 `gradle`（须为 JDK 21：先 `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env`，见 `.sdkmanrc`；`gradle --version` 的 JVM 显示 1.8 是发行版默认，不代表构建环境）。
- 主要命令：
  - `gradle buildPlugin` — 打包（产物 `build/distributions/myhope-sync-plugin-<ver>[-signed].zip`）。**首选验证手段**。
  - `gradle runIde --no-configuration-cache` — 启动沙箱 IDEA 手动验收。**必须**加 `--no-configuration-cache`，否则会挂。
  - `gradle verifyPlugin` — 插件兼容性验证。
- 测试已全部删除（无单元/集成测试任务），`gradle test` 不适用。
- 有 `build/distributions/` 等构建产物：`.gradle/`、`build/`、`.intellijPlatform/` 均已 gitignore。

## 架构

- 仅支持 **Spring Boot (Java)** 项目，Go/Node/Python 不支持（`src/main/resources/META-INF/plugin.xml` 声明 `com.intellij.java` 依赖、since-build 261 / until-build 262.*）。
- 数据流：`toolwindow/MyHopeSyncPanel`（UI）→ `service/SyncService`（`syncToMyHope` 编排）→ `psi/SpringControllerParser`（PSI 扫描注解）→ `myhope/MyHopeClient`（REST `/pr-api/`，X-API-Token 认证）与 `myhope/MyHopeDataConverter`（请求 JSON / 快照 items 生成 + hash 计算）。
- 同步模型：**分支 + 工作空间 + 快照 items**。每个 Controller 分组 → collection item，每个端点 → request item。**写入唯一走增量 `client.pushOplog(...)`（buildOps 生成 create/update op，分批 ≤500/批，OCC 409 用 server_max_seq 更新水位重推≤3）；禁止调用全量推送/导入类接口**（分支为空时 buildOps 自然生成全量 create ops 增量落库）。
- 同步状态：双向 hash 对比（本地代码 hash vs 持久化 hash），key = `Controller:HTTP_METHOD:path`。路径/方法变更 → key 变化 → 判为 UNSYNCED；仅参数变更 → MODIFIED。
- 分支按名匹配/创建（`generateEntityId("br", branchName)`）；工作空间按 `settings.workspaceId` 匹配，无则取第一个（isDefault 优先）。
- `MyHopeDataConverter.toSnapshotItems(groups)` 生成快照 items；`buildOps(localItems, serverItems, ...)` 生成增量 ops（request 按**同一 parent 集合内** method+endpoint 匹配，create 时 parent 映射到服务端父 id）。

## 关键约束

- 认证需要两个必需 header：`X-API-Token: <api-tok_xxx>`（Owner 分发的接入 token）与 `X-MyHope-Instance-ID: <mh_xxx>`（客户端本地生成并持久化，每实例唯一，`AppSettings.getOrCreateInstanceId()`）。
- 三层隔离：`access_instance_id`（服务端生成 acc_xxx）/ `user_app_id`（服务端生成 app_xxx）/ `workspace_id`（客户端生成 ws_xxx）。POST body 传、GET query 传。
- 快照 items 有字段白名单 + 服务端保留字段**禁带**（`prev_entity_id`/`next_entity_id`/`parent_id`/`id`/`type` 由服务端维护，客户端不生成排序字段；`id`/`parent_id`/`type` 是快照 items 的合法字段，不是 op payload）。
- 确定性 entity_id：集合 `generateEntityId("hc", controllerQualifiedName)`、请求 `generateEntityId("hr", "METHOD:fullPath")`（SHA-256 前 12 hex，跨扫描稳定）。
- pushOplog 增量推送：409 `occ_conflict` → 用 server_max_seq 更新 base_seq 重推，最多 3 次；O8 禁止空 push（无 ops 不推送，直接报"无差异"）。
- 冲突语义：内容冲突 = 停推并引导用户在 Web 端处理（无覆盖策略，force 已取消）；MVP 不做 push_oplog 增量合并。
- body 序列化**不要**用 Gson 排除 null 字段——服务端 body.contentType / auth 允许 null（沿用旧插件 1.2.4 教训）。

## 约定与坑

- UI 文案走 `messages/MyHopeSyncBundle[_zh].properties` + `util/I18n.kt`（按 `AppSettings.language` 切换）；新增文案两个语言文件都要加。plugin.xml 的 description 是双语文案，打包时由 build.gradle.kts 覆盖，改版本/说明时两处都要同步。
- 改版本号时：`build.gradle.kts` 的 `version` 与 `pluginConfiguration.version`、`changeNotes`、`plugin.xml` 的 `<version>`/`<change-notes>` 一并改。
- myhope 服务端联调见 myhope-pr-server 仓库的 `docs/PR服务客户端接入指南.md`。
- 项目根还有 `plan.md` / `task_plan.md` / `findings.md` / `progress.md`（planning-with-files 遗留，旧 Hoppscotch 阶段内容已标记过时），做改动前先看它们确认阶段状态。