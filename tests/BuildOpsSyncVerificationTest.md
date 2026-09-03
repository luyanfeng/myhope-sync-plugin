# buildOps 同步判定集成验证 — 测试用例

> 对应测试类：`src/test/java/com/myhope/sync/myhope/BuildOpsSyncVerificationTest.java`

## 目的

验证 `MyHopeDataConverter.buildOps` 的**新增/更新判定逻辑**是否符合最终敲定的同步语义。**只读**验证：仅拉取真实服务端快照做基准 + 运行 `buildOps` 生成 op 并断言，**不向服务端 push 任何 op**（零副作用）。

完整同步链路（`getOplogHead` → `getSnapshot` → `buildOps` → `pushOplog`）依赖 IntelliJ 环境，需在沙箱 IDEA 里手动验收，不在此测试覆盖范围。

## 被测语义（用户最终决定）

1. **判断新增/更新 = method + url（fullPath），不看 id。**
2. 目标目录内已存在同 method+url 的 request → **update**（用服务端真实 id）；不存在 → **create**。
3. **陈旧实体**（同 method+url、不同 id）→ 直接 update，**绝不新建、绝不 delete**。
4. 集合（模块/controller collection）按 **title** 匹配（仅限目标目录子树内）→ 存在 update、不存在 create。
5. **op 中绝不出现 `move_entity` / `delete_entity`**（插件只做新增 + 更新）。
6. 定位目标用 parentId 链，判断用 method+url，两者分离。

## 环境

- 服务端：`http://localhost:3462/pr-api/api`
- 凭据：环境变量注入，**缺失自动跳过（不失败）**
- 分支快照为真实数据（先通过插件同步过一次，含 `POST:/dbConnection/manage/list` 等端点）

## 场景 1：根级重推（不选目录）

| 项 | 内容 |
|---|---|
| targetParentId | `null`（根级，服务端全量参与匹配） |
| 服务端数据 | 真实快照（已有同名模块/控制器/端点） |
| 本地 items | 模块集合`dlyx-b-database-analysis` → 控制器集合`数据库连接管理接口` → request `POST:/dbConnection/manage/list` |
| 预期 | **0 个 create、0 个 move**；对已存在端点发字段级 `update_*` 且 **entity_id = 服务端真实 id** |

**验证点**：重新推送已同步过的数据 = 修改服务端已有实体，**不新建重复实体**，绝不 move。

## 场景 2：选定新目录（目录下无数据）

| 项 | 内容 |
|---|---|
| targetParentId | `hc_NEWDIR_TEST`（构造，空目录） |
| 服务端数据 | 仅空的 `hc_NEWDIR_TEST` 集合 |
| 本地 items | 模块集合 / 控制器集合 / request `POST:/dbConnection/manage/list`（挂在目标目录下） |
| 预期 | **恰好 3 个 create**（create_collection ×2 + create_request ×1）、**0 个 move** |

**验证点**：目标目录下没有对应 method+url → 全部新增（新建实体挂到该目录）。

## 场景 3：陈旧实体（同 method+url、不同 id）

| 项 | 内容 |
|---|---|
| targetParentId | `hc_NEWDIR_TEST` |
| 服务端数据 | 目标目录下已有**完整同标题数据**，但 request 是**旧 id** `hr_OLD...` |
| 本地 items | 同标题模块/控制器 + 同 method+url request（本地 id 与 `hr_OLD` 不同） |
| 预期（无差异） | **0 个 create、0 个 move**（内容相同则不动，不因 id 不同而新建） |
| 预期（有 title 差异） | 仍是 **0 create、0 move**；发 `update_title` 且 **entity_id = 服务端旧 id `hr_OLD...`** |

**验证点**：陈旧实体只 update（落到服务端那个真实 id），**绝不因 id 变化新建**，绝不 move。

## 运行方式

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env && \
MH_TOKEN=... \
MH_INSTANCE_ID=... \
MH_ACCESS_INSTANCE_ID=... \
MH_USER_APP_ID=... \
MH_WORKSPACE_ID=... \
MH_BRANCH_ID=... \
MH_AUTHOR_ID=... \
MH_BASE_SEQ=34 \
gradle test --tests '*BuildOpsSyncVerificationTest'
```

### 环境变量

| 变量 | 含义 | 必填 |
|---|---|---|
| `MH_BASE_URL` | 服务端 API 地址（默认 `http://localhost:3462/pr-api/api`） | 否 |
| `MH_TOKEN` | X-API-Token | 是 |
| `MH_INSTANCE_ID` | X-MyHope-Instance-ID | 是（真实联调时） |
| `MH_ACCESS_INSTANCE_ID` | access_instance_id | 是 |
| `MH_USER_APP_ID` | user_app_id | 是 |
| `MH_WORKSPACE_ID` | workspace_id | 是 |
| `MH_BRANCH_ID` | branch_id | 是 |
| `MH_AUTHOR_ID` | 作者 id（缺省用 access_instance_id） | 否 |
| `MH_BASE_SEQ` | 服务端当前水位（默认 0） | 否 |

> 缺真实凭据时测试自动 **skipped**（不失败），便于无凭据/CI 环境安全执行。

### 验证结果（真实服务端，2026-09-01）

- 有凭据：`tests=3 skipped=0 failures=0 errors=0` ✓（全部通过）
- 无凭据：`tests=3 skipped=3 failures=0 errors=0` ✓（自动跳过）

### 注意事项

- 需 JDK 21：每条 gradle 命令前先 `source sdkman && sdk env`，否则配置阶段会因 JDK8 报错。
- 测试走 IntelliJ Platform `testFramework(Platform)` 沙箱执行，耗时约 10s~1m37s。
- 场景 1 依赖服务端真实存在 `POST:/dbConnection/manage/list`，若断言失败请先通过插件同步一次目标分支。
