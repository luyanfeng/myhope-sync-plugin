# 任务计划：Hoppscotch Sync 插件 → MyHope Sync 插件改造

> ✅ 全部阶段已完成（2026-08-31），代码改造 + 文档同步完成，待真实服务端联调验证。

## 目标

把现有面向 Hoppscotch 的 IntelliJ 插件（`com.hoppscotch.sync`）完整改造为面向 myhope-pr-server 的插件 `com.myhope.sync`（展示名 **MyHope Sync**）。保留 Spring Controller PSI 解析核心价值，其余全部转向 `/pr-api/` REST 接入。

## 已确定决策（2026-08-31）

| 项 | 决策 |
|---|---|
| 包名 / 插件 ID | `com.myhope.sync` |
| 类名前缀 | `MyHope`（HoppscotchClient→MyHopeClient、HoppscotchSyncPanel→MyHopeSyncPanel 等）|
| 展示名 | MyHope Sync |
| Hoppscotch 兼容 | 完全移除，不保留双模式 |
| 同步写入 | **仅增量 push_oplog**（buildOps 生成 create/update op，分批 ≤500/批，409 OCC 用 server_max_seq 更新水位重推≤3）；**禁止全量推送/导入类接口**（分支为空时 buildOps 自然生成全量 create ops 增量落库） |
| 认证 | X-API-Token + X-MyHope-Instance-ID（自动生成持久化）+ bind-myhope 绑定 |
| force 覆盖 | **服务端已取消**（2026-08-31，与 git-like append-only + OCC 原则冲突）。插件不做任何覆盖策略，冲突收敛为：① OCC 409 自动重拉重推 + ② 内容冲突停推引导（去 Web/PR 处理）|

## 服务端契约（来自 myhope-pr-server，已评审闭环）

- 认证：`PUT /pr-api/api/bind-myhope`（X-API-Token + X-MyHope-Instance-ID header + body myhope_user_id/user_name）→ 返回 access_instance_id + user_app_id
- 校验：`GET /pr-api/api/auth/token-info`（未绑定 401）
- 分支：`POST /pr-api/api/branches`（**id 客户端生成必传** + workspace_id 必传 + name + visibility，自动建初始单元）
- 工作空间：`GET /pr-api/api/workspaces`、`POST /pr-api/api/workspaces`（id 客户端生成必传）
- 快照：`GET /pr-api/api/branches/{bid}/snapshot`（只读 diff 基准）；**写入不走快照接口**
- 水位：`GET /pr-api/api/branches/oplog/head`
- 增量：`POST /pr-api/api/branches/oplog/list`（after_seq map）
- 推送：`POST /pr-api/api/branches/oplog`（OCC CAS，空 push 400，带 merge 信号空 push 放行）
- 保护：`GET /pr-api/api/protection-rules/by-branch`（无规则返回 is_protected=false 默认值）
- 三层隔离：access_instance_id / user_app_id / workspace_id（POST body、GET query）
- item 字段白名单：method/endpoint/headers/params/body{contentType,body}/auth/authType/pre_request_script/test_script/description/responses/response_example/request_variables
- 保留字段禁带：prev_entity_id/next_entity_id/parent_id/id/type（服务端维护）
- 409 响应：per-unit map，冲突单元 server_max_seq=当前水位

## 文件重命名映射

| 旧（com.hoppscotch.sync） | 新（com.myhope.sync） |
|---|---|
| `hoppscotch/HoppscotchClient.kt` | `myhope/MyHopeClient.kt` |
| `hoppscotch/HoppscotchDataConverter.kt` | `myhope/MyHopeDataConverter.kt` |
| `hoppscotch/HoppscotchVersionChecker.kt` | `myhope/MyHopeVersionChecker.kt` |
| `hoppscotch/RequestValidator.kt` | 移除（myhope 服务端自带校验，插件不再前置 Zod 校验）|
| `model/HoppscotchModels.kt` | `model/MyHopeModels.kt` |
| `toolwindow/HoppscotchSyncPanel.kt` | `toolwindow/MyHopeSyncPanel.kt` |
| `toolwindow/HoppscotchSyncToolWindowFactory.kt` | `toolwindow/MyHopeSyncToolWindowFactory.kt` |
| `service/TokenRefreshStartupActivity.kt` | `service/MyHopeStartupActivity.kt`（改 token-info 校验）|
| `messages/HoppscotchSyncBundle*.properties` | `messages/MyHopeSyncBundle*.properties` |
| plugin.xml id/name/toolWindow/storage | `com.myhope.sync` / MyHope Sync |
| `hoppscotch-sync-settings.xml` | `myhope-sync-settings.xml` |

保留不动：`psi/SpringControllerParser.kt`（核心）、`model/SpringEndpoint.kt`、`util/I18n.kt`、`util/LogUtil.kt`、`toolwindow/CollectionPickerDialog.kt`（改为分支/工作空间选择）、`action/SyncAction.kt`、`settings/AppSettings.kt`/`AppSettingsConfigurable.kt`（字段改造）、`service/SyncService.kt`（重写）

## 阶段

### Phase 1：全局重命名 + 骨架（先做，阻塞一切）
- [ ] 包名 `com.hoppscotch.sync` → `com.myhope.sync` 全量替换（package/import/plugin.xml/build.gradle/storage）
- [ ] 类名/文件名按映射表重命名
- [ ] 版本号升 2.0.0（build.gradle.kts + plugin.xml 同步）
- [ ] `gradle buildPlugin` 编译通过

### Phase 2：模型层（MyHopeModels）
- [ ] SnapshotItem（id/parent_id/type/title/method/endpoint/... 白名单字段）
- [ ] CollectionInfo → 分支/工作空间模型（BranchInfo、WorkspaceInfo）
- [ ] OpEntry（seq/op_type/entity_id/parent_id/prev_entity_id/payload/author_id/created_at）
- [ ] 同步状态模型（SyncStatus 保留三色）
- [ ] RequestInfo / RequestJson 保留（用于状态检测对比）

### Phase 3：MyHopeClient（REST 客户端，重写）
- [ ] X-API-Token + X-MyHope-Instance-ID header 注入
- [ ] bindMyHope / tokenInfo（启动校验）
- [ ] listWorkspaces / createBranch / listBranches
- [ ] getSnapshot（只读 diff 基准）
- [ ] getOplogHead / listOplog / pushOplog（OCC 409 解析 server_max_seq；**唯一写入通道**）
- [ ] getProtectionByBranch
- [ ] 错误解析（401 未绑定 / 403 protection_denied / 409 occ_conflict / 422 白名单）

### Phase 4：MyHopeDataConverter（SpringEndpoint → items）
- [ ] 确定性 entity_id：`hc_<hash(controllerQualifiedName)>` 集合 / `hr_<hash(method:path)>` 请求
- [ ] Controller 分组 → collection items；端点 → request items
- [ ] 复用现有 toHoppscotchRequest 的参数/body/auth 生成逻辑（保留白名单字段）
- [ ] 过滤保留字段（不生成 prev/next/parent/id/type 进 payload）
- [ ] buildOps（同一 parent 集合内 method+endpoint 匹配；create 时 parent 映射到服务端父 id）

### Phase 5：SyncService（同步编排，重写）
- [ ] 首次接入：bind-myhope → 查工作空间 → 创建/复用分支（id 确定性生成）→ buildOps 生成全量 create ops 经 pushOplog 增量落库（禁全量推送）
- [ ] 日常同步：解析 → 生成 items → getOplogHead 实时水位 → getSnapshot diff → buildOps → 分批 pushOplog
- [ ] 409 OCC → 拉 head + list_oplog 合并 → 重推
- [ ] 内容冲突检测（同 entity 双改异值）→ 停推提示
- [ ] 403 保护分支 → protection by-branch 预检 + UI 标记
- [ ] 同步状态三色：本地 hash vs 服务端快照 hash（保留概念）

### Phase 6：设置层（AppSettings + Configurable）
- [ ] 字段：serverUrl / apiToken / instanceId(自动生成持久化) / accessInstanceId / userAppId / workspaceId / branchId / branchName
- [ ] 移除：refreshToken / serverSchemaVersion / requestValidationEnabled / targetCollectionId(改 branch)
- [ ] 保留：language / hiddenColumnIds / columnWidths / syncStatusData / logLevel / selectedProjectsData / cachedScanJson
- [ ] 设置面板文案更新（双语）

### Phase 7：UI 层
- [ ] MyHopeSyncPanel：分支选择下拉（替代集合选择）、同步按钮改调新 SyncService、保护标记、409 提示
- [ ] CollectionPickerDialog → 分支/工作空间选择器（或移除，改下拉）
- [ ] MyHopeSyncToolWindowFactory / SyncAction 类名与方法更新
- [ ] 状态列显示逻辑适配

### Phase 8：i18n + plugin.xml 描述
- [ ] 两个 bundle 全部文案更新（MyHope 相关）
- [ ] plugin.xml description/changeNotes 双语（build.gradle 覆盖）
- [ ] README_for_ai.md / AGENTS.md 更新

### Phase 9：验证
- [ ] `gradle buildPlugin` 通过
- [ ] `gradle verifyPlugin` 通过
- [ ] 集成测试改造：指向真实 myhope-pr-server（`runScenarioTest` 等，若保留）
- [ ] `gradle runIde --no-configuration-cache` 手动验收（若可用）

## 验证策略
- 每阶段完成即 `gradle buildPlugin` 编译验证（首选手段）
- 端到端需真实 myhope-pr-server（make run + SQLite），联调清单见设计文档 §十

## 不做（本期）
- WS 实时通知（二期）
- auto-publish 自动 PR（二期）
- force 覆盖式写入（服务端未实现/已取消）