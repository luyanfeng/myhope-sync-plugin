# MyHope Sync Plugin (For AI)

IntelliJ IDEA 插件（Kotlin）：把 Spring Boot (Java) Controller 的 REST 端点同步到 myhope-pr-server 分支快照。`group=com.myhope.sync`，插件 id `com.myhope.sync`，版本 2.0.0。

> ⚠️ **仅支持 Spring Boot (Java) 项目**。Go、Node.js、Python 等其他语言/框架的项目不在支持范围内。

## 功能

- **自动扫描**：扫描项目中的 `@RestController` / `@Controller` 类，解析 `@RequestMapping`/`@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`/`@PatchMapping` 注解
- **参数解析**：提取路径参数（`@PathVariable`）、查询参数（`@RequestParam`）、请求体（`@RequestBody`）、请求头（`@RequestHeader`）
- **一键同步**：通过 REST `/pr-api/` + X-API-Token 认证，**增量 push_oplog 推送**（buildOps 生成 create/update op，分批 ≤500/批）到 MyHope 分支；**禁止全量推送/导入类接口**
- **按 Controller 分组**：每个 Controller → collection item，每个端点 → request item
- **自动实例绑定**：首次使用自动绑定（回填 access_instance_id / user_app_id），绑定状态持久化
- **分支匹配/创建**：按 `branchName` 匹配已有分支，不存在则 `createBranch`（确定性 id）
- **OCC 冲突重试**：push_oplog 并发写冲突（409 `occ_conflict`）→ 用 server_max_seq 更新 base_seq 重推，最多 3 次
- **同步状态跟踪**：三色标识（🟢 已同步 / 🔵 已修改 / ⬜ 未同步），状态在 IDE 重启后保留
- **UI**：专用 Tool Window，支持搜索过滤、排序、列显隐、列宽持久化、单元格复制、多项目、选择性同步、数据缓存、中英文切换

## 系统要求

- IntelliJ IDEA 2026.1+ (Build 261+)
- JDK 21（开发环境 `21.0.11-amzn`）
- Gradle（无 gradlew，用系统 gradle；须 JDK 21：`source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env`）

## 构建与验证

```bash
# 构建插件（首选验证手段）
gradle buildPlugin            # 产物 build/distributions/myhope-sync-plugin-2.0.0.zip

# 沙箱 IDEA 手动验收（必须加该参数，否则会挂）
gradle runIde --no-configuration-cache

# 插件兼容性验证
gradle verifyPlugin
```

- 测试已全部删除，`gradle test` 不适用。
- 构建产物 `.gradle/`、`build/`、`.intellijPlatform/` 均已 gitignore。

## 架构

数据流：`toolwindow/MyHopeSyncPanel`（UI）→ `service/SyncService`（`syncToMyHope` 编排）→ `psi/SpringControllerParser`（PSI 扫描注解）→ `myhope/MyHopeClient`（REST `/pr-api/`）与 `myhope/MyHopeDataConverter`（请求 JSON / 快照 items 生成 + hash 计算）。

### 同步流程（SyncService.syncToMyHope）

1. **绑定校验**：读 `AppSettings`（serverUrl/apiToken/instanceId/accessInstanceId/userAppId/myhopeUserId/myhopeUserName）；apiToken 空 → errors；accessInstanceId/userAppId 空 → `tokenInfo()` 恢复或 `bindMyHope()`；myhopeUserId 空 → errors。
2. **工作空间**：`listWorkspaces` → 匹配 `settings.workspaceId`，无则取第一个（isDefault 优先），写回 settings。
3. **分支**：`branchName` 空则默认 `project.name`；`listBranches` 按名匹配，未匹配则 `createBranch`（`generateEntityId("br", branchName)`、visibility="personal"）。
4. **items 转换**：`converter.toSnapshotItems(groups)`。
5. **实时水位**：`getOplogHead` 拿服务端 currentSeq 作 baseSeq（不本地缓存）。
6. **增量 diff**：`getSnapshot` 拉服务端 items 作基准 → `converter.buildOps(localItems, serverItems, ...)`（request 按同一 parent 集合内 method+endpoint 匹配；create 时 parent 映射到服务端父 id）。
7. **push_oplog**：`client.pushOplog(...)` 分批（≤500/批）推送；409 OCC 用 server_max_seq 更新水位重推≤3；无 ops 不推送（报"无差异"）。
8. 返回 `SyncResult`（total/success/failed/collectionsCreated/requestsCreated/errors）。

### 同步状态检查（MyHopeSyncPanel.performServerCheck）

- `client.getSnapshot(accessInstanceId, userAppId, workspaceId, branchId)` 拉分支快照，按 `entity_id` 建映射。
- 本地端点 `generateEntityId("hr", "METHOD:fullPath")` 匹配：不在服务端 → **UNSYNCED**；在服务端 → 对比持久化 hash，本地改 → **MODIFIED**，未改 → **SYNCED**。
- `branchId`/`workspaceId` 为空（未同步过）→ 全部 UNSYNCED，跳过服务端查询。

## 关键约束

- **认证**：两个必需 header——`X-API-Token: <api-tok_xxx>` 与 `X-MyHope-Instance-ID: <mh_xxx>`（每实例唯一，`AppSettings.getOrCreateInstanceId()`）。
- **三层隔离**：`access_instance_id`（acc_xxx）/ `user_app_id`（app_xxx）/ `workspace_id`（ws_xxx）。POST body 传、GET query 传。
- **快照 items 白名单**：服务端保留字段禁带（`prev_entity_id`/`next_entity_id`/`parent_id`/`id`/`type` 由服务端维护，客户端不生成排序字段；`id`/`parent_id`/`type` 是 items 合法字段，非 op payload）。
- **确定性 entity_id**：集合 `hc_` + SHA-256(controllerQualifiedName) 前 12 hex；请求 `hr_` + SHA-256("METHOD:fullPath") 前 12 hex。跨扫描稳定。
- **push_oplog 增量**：409 `occ_conflict` → 用 server_max_seq 更新 base_seq 重推≤3；O8 禁止空 push（无 ops 不推送）。写入唯一走增量 push_oplog，**禁止调用全量推送/导入类接口**（分支为空时 buildOps 自然生成全量 create ops 增量落库）。
- **body 序列化**：不要用 Gson 排除 null 字段（服务端 body.contentType / auth 允许 null）。

## 项目结构

```
src/main/kotlin/com/myhope/sync/
├── model/            # SpringEndpoint/ControllerGroup + MyHopeModels（MyHopeRequest/MyHopeParam/...）
│                     #   + SnapshotItem/ItemType + SyncStatus/SyncResult + generateEntityId
├── psi/              # SpringControllerParser（PSI 解析器）
├── myhope/           # MyHopeClient（REST /pr-api/）、MyHopeDataConverter（items/hash）、
│                     #   MyHopeVersionChecker、RequestValidator
├── service/          # SyncService（syncToMyHope）、MyHopeStartupActivity
├── settings/         # AppSettings（持久化）、AppSettingsConfigurable（设置面板）
├── toolwindow/       # MyHopeSyncPanel（工具窗口主面板）、MyHopeSyncToolWindowFactory
├── util/             # I18n、LogUtil
└── action/           # SyncAction（Tools 菜单动作）

src/main/resources/
├── META-INF/plugin.xml               # 插件描述符（description/change-notes 打包时由 build.gradle.kts 覆盖）
└── messages/
    ├── MyHopeSyncBundle.properties      # 英文资源
    └── MyHopeSyncBundle_zh.properties   # 中文资源
```

## 设置

Settings → Tools → MyHope Sync：
- **Server URL**（默认 `http://localhost:3170`）、**API Token**（`api-tok_xxx`）
- **Instance ID**（自动生成，只读）
- **MyHope User ID / User Name**（绑定用）
- **Workspace ID / Branch Name**（目标；Branch Name 空则默认项目名）、**Branch ID**（回填，只读）
- **Verify** 校验 Token 并完成绑定
- **Display Language**、**Debug Logging**（LOG 级别）

## 开发约定

- UI 文案走 `messages/MyHopeSyncBundle[_zh].properties` + `util/I18n.kt`；新增文案两个语言文件都要加；plugin.xml description 是双语，打包时由 build.gradle.kts 覆盖，两处同步。
- 改版本号：`build.gradle.kts` 的 `version` 与 `pluginConfiguration.version`、`changeNotes` + `plugin.xml` 的 `<version>`/`<change-notes>` 一并改。
- myhope 服务端联调见 myhope-pr-server 仓库 `docs/PR服务客户端接入指南.md`。

## 许可

Apache 2.0