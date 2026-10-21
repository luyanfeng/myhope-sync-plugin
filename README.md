# MyHope Sync Plugin

将 Spring Boot Controller 的 REST API 端点同步到 [myhope-pr-server](https://github.com/lyf/myhope-pr-server) 分支快照的 IntelliJ IDEA 插件。

> ⚠️ 仅支持 Spring Boot (Java) 项目。
> 此文及项目由 ai 生成，本项目只自用，出于分享目的开源。并不考虑持久维护（能力和精力有限），更多的是希望现阶段可以给那些能力和精力更有限的朋友提供个能用的选择。

## 功能

- **自动扫描** — 扫描 `@RestController` / `@RequestMapping` 等注解，提取所有 REST 端点
- **同步到 MyHope 分支** — 通过 REST `/pr-api/` + X-API-Token 认证，将端点和参数以快照 items 推送到 MyHope 分支
- **自动实例绑定** — 首次使用自动绑定（access_instance_id / user_app_id），绑定状态持久化
- **分支匹配/创建** — 按分支名匹配已有分支，不存在则自动创建
- **OCC 冲突重试** — 并发写冲突（409）自动 sleep 重试最多 3 次；内容冲突停推并引导到 Web 端处理
- **同步状态检测** — 对比本地代码与服务端快照，标记 **SYNCED（绿）** / **MODIFIED（蓝）** / **UNSYNCED（白）**
- **友好 UI** — 专用 Tool Window，支持搜索过滤、排序、列宽自定义、列显隐、选中行同步
- **多模块支持** — 同一窗口中的多模块均可识别，Projects 下拉定向扫描
- **数据缓存** — 项目选择和扫描结果自动持久化，重启 IDE 无需重新扫描
- **中英文界面** — 设置中切换语言，即时生效

## 系统要求

- IntelliJ IDEA 2026.1+ (Build 261+)
- JDK 21
- myhope-pr-server 服务端实例

## 兼容性

| 项目 | 版本 |
|------|------|
| 插件版本 | 2.2.0 |
| since-build | 261 |
| until-build | 262.* |

## 快速开始

### 构建

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk env
gradle buildPlugin
```

产物：`build/distributions/myhope-sync-plugin-2.2.0.zip`

### 安装

**本地安装**：
1. IntelliJ IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk
2. 选择 `build/distributions/myhope-sync-plugin-2.2.0.zip`
3. 重启 IDE

### 配置

1. 在 IDEA 中：Settings → Tools → MyHope Sync
2. 填写 Server URL（默认 `http://localhost:3170`）与 API Token（`api-tok_xxx`，Owner 分发）
3. 填写 MyHope 用户 ID / 用户名（用于绑定）
4. 填写目标工作空间 ID 与分支名（分支名留空则默认用项目名）
5. 点击 **Verify** 校验 Token 并完成绑定（自动回填 access_instance_id / user_app_id）

### 使用

**Tool Window**：View → Tool Windows → MyHope Sync
- Projects 下拉选择项目 → **Refresh** 扫描
- **Sync Selected** 同步选中端点到 MyHope 分支快照
- **检查同步状态** 对比服务端快照更新状态

**菜单动作**：Tools → Sync Spring Boot Endpoints to MyHope

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin 2.3 | 插件开发语言 |
| IntelliJ Platform Gradle Plugin 2.6 | 构建 |
| IntelliJ PSI API | Java 源码解析 |
| Java HttpClient (JDK 21) | HTTP 请求 |
| Gson | JSON 序列化/反序列化 |

## 项目结构

```
src/main/kotlin/com/myhope/sync/
├── model/          # 数据模型 + SyncStatus + 快照 item + 确定性 entity_id
├── psi/            # Spring Controller PSI 解析器
├── myhope/         # MyHopeClient（REST /pr-api/）+ 数据转换（items/hash）+ 版本检测
├── service/        # 同步编排服务（syncToMyHope）
├── settings/       # 持久化设置 + 设置面板
├── toolwindow/     # 工具窗口 UI
├── util/           # 国际化 + 日志工具
└── action/         # Tools 菜单动作
```

## 开发

```bash
gradle runIde --no-configuration-cache   # 启动沙箱 IDEA 测试（必须加该参数）
gradle buildPlugin                       # 构建插件
gradle verifyPlugin                      # 验证兼容性
```

## 许可

Apache 2.0