# MyHope Sync

An IntelliJ IDEA plugin for **MyHope** — sync Java Spring Boot endpoints from your IDE to **MyHope Server**.

一个面向 **MyHope** 的 IntelliJ IDEA 插件，用于从 IDEA 开发工具上向 **MyHope Server** 同步 Java Spring Boot 接口数据。

把项目里的 Spring Boot Controller 扫描成结构化接口清单，增量推送到 MyHope 的分支快照中，让接口文档跟着代码走，无需手工维护。

> ⚠️ **仅支持 Java / Spring Boot 项目**。Go、Node.js、Python 等其他语言/框架不在支持范围内。

---

## 功能特点

### 接口扫描与解析

- 识别 `@RestController` / `@Controller` 类，解析 `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping` 全部端点
- 提取 `@PathVariable`、`@RequestParam`、`@RequestBody`、`@RequestHeader` 参数
- 依据请求体类型生成 JSON 结构骨架预览，直接可读
- 支持 `@Api` / `@ApiOperation` 的描述与标签，展示在接口清单中

### 同步到 MyHope

- **增量写入**：每个 Controller 对应一个集合、每个端点对应一个请求；以 oplog 增量方式更新目标分支快照，内容无变化时不推送
- **自动实例绑定**：首次使用自动完成 MyHope 实例绑定（`access_instance_id` / `user_app_id`），绑定状态持久化，无需手工维护
- **分支匹配与创建**：按分支名匹配已有分支，不存在时自动创建
- **灵活的同步目标**：可同步到分支根级或指定集合目录，并支持按项目/模块名生成子目录
- **并发冲突自动重试**：写入遇到版本冲突时自动对齐服务端水位重推（最多 3 次）；内容冲突则停止推送并提示到 Web 端处理
- **自动发布 PR**：可选，同步成功后自动从当前分支向目标分支创建 PR，已有进行中的同名 PR 时跳过、不重复创建

### 同步状态跟踪

本地代码与 MyHope 快照做双向 hash 对比，逐行标记：

| 状态 | 含义 |
|------|------|
| 🟢 已同步 | 本地与服务端一致 |
| 🔵 已修改 | 接口仍在，但参数/描述等已改动 |
| ⬜ 未同步 | 服务端不存在，或路径/方法已变更 |

### 接口搜索

- `Ctrl+\` 全局呼出搜索弹窗，按路径、方法、Controller、注释检索
- 拆词匹配（AND 语义）、忽略词序、区分大小写、命中片段高亮
- 可勾选「仅限当前模块」缩小范围
- `Alt+A` / `Alt+Shift+A` 在搜索历史间来回切换
- 回车或双击直接跳转到源码，结果分页渲染不卡顿
- 标题栏可拖拽移动窗口

### 编辑器右键菜单

在编辑器内右键 → **MyHope Plugin**：

- **Copy Full URL** —— 复制完整 URL
- **Copy Path Only** —— 仅复制路径
- **Copy as cURL** —— 生成可执行的 cURL 命令（含 `@RequestBody` JSON 与 `@RequestHeader`）
- **Copy to JSON** —— 由 JavaBean / 参数 / 字段 / 返回类型生成 JSON 模板

### 工具窗口

- 多项目/多模块同时识别，Projects 下拉可定向扫描
- 勾选式选择性同步，支持全选 / 反选 / 过滤后自动勾选
- 关键词过滤，`^` 前缀为排除模式
- 「仅已同步」过滤，快速定位未推送的接口
- 列排序、列显隐、列宽持久化
- `Ctrl+C` 复制选中单元格，一键导出 CSV

### 其他

- **按项目隔离的持久化**：工作空间、分支、同步状态、扫描缓存、列设置按项目独立保存
- **扫描缓存**：重启 IDE 无需重新扫描
- **中英文界面**：设置中切换，即时生效
- **高分屏与 Wayland 适配**：原生 Wayland 下尺寸、居中、拖拽均正常

---

## 系统要求

| 项目 | 要求 |
|------|------|
| IDE | IntelliJ IDEA 2026.1 / 2026.2（Build 261 ~ 262.*） |
| 语言 | Java / Spring Boot 项目 |
| 服务端 | 可访问的 MyHope Server 实例 |
| 凭据 | Owner 分发的 API Token（`api-tok_xxx`） |

---

## 安装

从发行包安装：

1. IntelliJ IDEA → **Settings → Plugins → ⚙ → Install Plugin from Disk**
2. 选择 `myhope-sync-plugin-<version>.zip`
3. 重启 IDE

---

## 使用

### 1. 配置连接

**Settings → Tools → MyHope Sync**（或在工具窗口点 **Settings**）：

1. 填写 **Server URL**（默认 `http://localhost:3462`）与 **API Token**（`api-tok_xxx`）
2. 点击 **Verify** 校验连接，自动完成实例绑定
3. 选择或新建 **工作空间** 与 **分支**（分支名留空则默认使用项目名）
4. 按需配置同步目标集合、是否按项目名生成子目录、是否自动发布 PR 及 PR 目标分支

设置支持「项目级覆盖」：账号级字段（服务器地址、Token、语言、日志级别）留空时回落全局默认值，填写后仅对当前项目生效；同步目标与同步状态则始终按项目独立保存。

### 2. 扫描与同步

打开 **View → Tool Windows → MyHope Sync**：

1. Projects 下拉选择要扫描的项目/模块
2. 点击 **Refresh** 扫描接口
3. 勾选要同步的端点（或全选 / 反选 / 先过滤再同步）
4. 点击 **Sync Selected** 推送到 MyHope 分支
5. 点击 **Check Sync** 与服务端快照比对，刷新每行的同步状态

也可以从菜单 **Tools → Sync Spring Boot Endpoints to MyHope** 触发。

### 3. 搜索与跳转

在任意位置按 `Ctrl+\` 呼出接口搜索，输入关键词后回车或双击结果即可跳转到对应源码。

---

## 开发

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env   # 需要 JDK 21

gradle buildPlugin                                    # 打包，产物在 build/distributions/
gradle signPlugin                                     # 用 CERT_DIR 下的密钥签名（发布必需）
gradle verifyPlugin                                   # 二进制兼容性验证（IDEA 2026.1 + 2026.2）
gradle runIde --no-configuration-cache                # 启动沙箱 IDE 调试（必须加该参数）
```

无 `gradlew`，使用系统 `gradle`。

发布到 JetBrains Marketplace：

- 签名密钥放在**仓库外**的 `CERT_DIR`（默认 `~/IdeaProjects/lyf/ai/myhope-sync-plugin-cert/`，可用环境变量 `CERT_DIR` 覆盖），
  含 `certificateChain.pem` 与 `privateKey.pem`，**不要提交到版本库**。
- 凭据走环境变量：`JETBRAINS_TOKEN`（Marketplace Personal Access Token）、
  `PRIVATE_KEY_PASSWORD`（私钥口令，本仓库私钥未加密故留空）。
- 首次发布必须先在 [plugins.jetbrains.com](https://plugins.jetbrains.com) 手动上传一次，之后才能用
  `gradle publishPlugin` 自动发布。

### 项目结构

```
src/main/kotlin/com/myhope/sync/
├── model/          # 数据模型：SpringEndpoint / ControllerGroup / 快照 item / SyncStatus / 确定性 entity_id
├── psi/            # SpringControllerParser：基于 PSI 的 Controller 与注解解析
├── myhope/         # MyHopeClient（REST /pr-api/）、MyHopeDataConverter（items / hash / buildOps）
├── service/        # SyncService：绑定 → 工作空间 → 分支 → 转换 → 增量推送 的编排
├── settings/       # 全局与项目级持久化设置、有效配置合并、设置面板
├── index/          # 接口搜索索引（按 module 缓存）与启动预热
├── toolwindow/     # 工具窗口、接口搜索弹窗、目标选择对话框
├── action/         # Tools 菜单动作、编辑器右键复制动作
└── util/           # 国际化与日志

src/main/resources/
├── META-INF/plugin.xml                    # 插件描述符
└── messages/MyHopeSyncBundle[_zh].properties   # 中英文文案
```

### 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin 2.3 | 插件开发语言 |
| IntelliJ Platform Gradle Plugin 2.6 | 构建与打包 |
| IntelliJ PSI API | Java 源码解析 |
| Java HttpClient (JDK 21) | 调用 MyHope `/pr-api/` |
| Gson | JSON 序列化与反序列化 |

---

## 许可

Apache 2.0
