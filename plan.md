# 同步状态持久化 ServerId + 标题支持 @ApiOperation

> ⚠️ 已过时（2026-08-31，Hoppscotch→MyHope 改造已完成），内容仅供历史参考。

## 目标

1. **同步时将服务端返回的请求 id 持久化到本地**，后续增量同步和状态检测直接用 id 匹配，不再依赖 title
2. **支持 `@ApiOperation` 作为请求标题**，无 `@ApiOperation` 时回退为当前方式（`fullPath`）
3. **旧数据兼容**：已同步的数据无损，能自动回填 serverId

---

## 背景

当前同步判断流程：

```
同步请求 → 服务端返回 { id, title, request }
         → 本地只存了 "localHash,srvHash"
         → 下次增量匹配: 查服务端请求列表 → associateBy { it.title } → 对比本地 title
         → 状态检测: 查 title 是否在服务端列表中
```

问题：匹配依赖 title，改了 title（比如改为 @ApiOperation）就断。

**方案**：同步时把服务端返回的 `id` 和 hash 一起存下来：

```
持久化格式: endpointKey → "serverId,localHash,srvHash"
匹配方式:   直接用 serverId 查，不依赖标题
```

---

## 风险与影响评估

| 影响 | 说明 | 缓解措施 |
|------|------|----------|
| 旧数据没有 serverId | 已有 `"localHash,srvHash"` 格式无 id | 自动回填：状态检测时按 method+endpoint 匹配后补存 id |
| 持久化格式变更 | 序列化/反序列化新增字段 | 向后兼容：解析时兼容新旧格式 |
| @ApiOperation 不存在 | 不是所有 Controller 方法都标了 | 回退为 fullPath |
| 标题唯一性 | 两个端点可能有相同的 @ApiOperation | 匹配不依赖标题，无影响 |

---

## 实现计划

### Phase 1: 持久化 ServerId + 回填（~2h）

**改动文件：**

| 文件 | 改动内容 |
|------|----------|
| `model/HoppscotchModels.kt` | 新增 `computeSyncKey`（endpointKey→serverId），构建同步值函数扩展为 3 参数 |
| `settings/AppSettings.kt` | `deserializeSyncMap` 兼容无 serverId 的旧格式 |
| `service/SyncService.kt` | 同步成功后存 `"serverId,localHash,srvHash"`；增量判断改由 serverId 匹配 |
| `toolwindow/HoppscotchSyncPanel.kt` | 状态检测改由 serverId 直接取请求 JSON；同步后存 id |
| `hoppscotch/HoppscotchDataConverter.kt` | 新增 parse/build 辅助函数（serverId + hash） |

**具体步骤：**

1. **HoppscotchDataConverter** — 新增：
   - `buildSyncValue(serverId: String?, localHash: Int, srvHash: Int): String`
   - `parseServerId(value: String): String?`
   - `parseLocalHash(value: String): Int`（已有，无改动）
   - `parseSrvReqHash(value: String): Int`（已有，无改动）

2. **HoppscotchModels** — 新增：
   - `SyncPersistData` 数据类（serverId, localHash, srvHash，带 parse/buid 方法）
   - 或仅在已有工具函数中扩展，保持简洁

3. **AppSettings.deserializeSyncMap** — 兼容性：
   - 当前格式：`Map<String, Int>`（旧） 或 `Map<String, String>`（新格式 `"localHash,srvHash"`）
   - 新格式变体：`Map<String, String>` 但值为 `"serverId,localHash,srvHash"`
   - 检测逗号数量区分：1个逗号=旧, 2个逗号=新

4. **SyncService.syncGroups**：
   - 创建请求成功时：`syncMap[endpointKey] = buildSyncValue(result.id, localHash, srvHash)`
   - 增量判断：
     ```kotlin
     val allServerIds = client.listRequests(collectionId).map { it.id }.toSet()
     val savedServerId = parseServerId(persistedMap[endpointKey])
     if (savedServerId != null && savedServerId in allServerIds) → 跳过
     ```

5. **HoppscotchSyncPanel.onCheckSyncStatus**：
   - 查到的请求组织为 `Map<id, RequestInfo>` 而非 `Map<title, RequestInfo>`
   - 用 serverId 直接从 map 取请求 JSON
   - 回填逻辑：旧数据无 serverId → 用 method+endpoint 匹配 → 补存 id
   - 同步后 `updateStatusAfterSync` 中存 id

6. **HoppscotchSyncPanel.syncSelected**（同步后存 id 和 hash）：
   - 当前 `syncMap[key] = buildSyncValue(localHash, srvHash)` → 改为带 serverId

### Phase 2: 解析 @ApiOperation（~1h）

**改动文件：**

| 文件 | 改动内容 |
|------|----------|
| `model/SpringEndpoint.kt` | 新增 `description: String? = null` 字段 |
| `psi/SpringControllerParser.kt` | 解析 `@ApiOperation(value)` 或 `@Operation(summary)` |

**具体步骤：**

1. **SpringEndpoint** — 加字段：
   ```kotlin
   data class SpringEndpoint(
       ...
       val description: String? = null
   )
   ```

2. **SpringControllerParser** — 解析注解：
   - 支持 `io.swagger.annotations.ApiOperation`（Swagger 1/2）
   - 支持 `io.swagger.v3.oas.annotations.Operation`（OpenAPI 3 / SpringDoc）
   - 在 `parseMethodEndpoints` 中提取：
     ```kotlin
     val description = method.annotations
         .firstOrNull { 
             it.qualifiedName == "io.swagger.annotations.ApiOperation" ||
             it.qualifiedName == "io.swagger.v3.oas.annotations.Operation"
         }?.let { ann ->
             ann.findAttributeValue("value")?.let { extractStringValue(it) }
                 ?: ann.findAttributeValue("summary")?.let { extractStringValue(it) }
         }
     ```

### Phase 3: 标题改为 @ApiOperation（~0.5h）

**改动文件：**

| 文件 | 改动内容 |
|------|----------|
| `service/SyncService.kt` | `buildRequestTitle` 用 description |
| `model/HoppscotchModels.kt` | `requestTitleOnServer` 用 description |

**具体步骤：**

```kotlin
fun buildRequestTitle(endpoint: SpringEndpoint): String =
    endpoint.description?.takeIf { it.isNotBlank() } ?: endpoint.fullPath

fun requestTitleOnServer(endpoint: SpringEndpoint): String =
    endpoint.description?.takeIf { it.isNotBlank() } ?: endpoint.fullPath
```

Hoppscotch 中请求的 `name` 字段建议改为 `"${httpMethod.name} ${description ?: fullPath}"`，方便列表展示。

---

## 验证清单

- [ ] Phase 1 编译通过，单元测试通过
- [ ] 新同步的请求在 syncStatusData 中有 serverId
- [ ] 旧数据在"检查同步状态"后自动回填了 serverId
- [ ] 增量同步跳过判断正确（不会对已有请求重复创建）
- [ ] @ApiOperation 解析正确（swagger + springdoc 两种注解）
- [ ] 无 @ApiOperation 时仍用 fullPath 作为标题
- [ ] 标题改为 @ApiOperation 后匹配依然正确
- [ ] 单元测试：hash 辅助函数测试更新

---

## 不纳入本次范围

- 用 PLUGIN_FIRST 策略更新旧请求的 title（用户在需要时可手动触发）
- @ApiOperation 值在代码变更后的 title 自动更新
- Hoppscotch 侧旧请求的孤儿清理
