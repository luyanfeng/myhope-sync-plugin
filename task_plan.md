# 任务计划：接口搜索增强（拆词匹配 + 词序 + 模糊查询）

> 创建 2026-09-07。本计划覆盖 2026-09 接口搜索（Ctrl+\）体验增强系列。
> 旧的 Hoppscotch→MyHope 改造计划（2026-08-31 已完成）已归档至文末「历史归档」，仅作参考。

## 目标

接口搜索弹窗支持**多词拆词 AND 匹配、可忽略词序**，默认**模糊子串查询**；命中词**黄底高亮**。
叠加已完成的：① 结果分页追加加载（fix-6，防 EDT 卡死，已提交 `85020e1`）② 行高 50/封顶 8 行（`0fb6cec`）③ 区分大小写勾选（已完成 BUILD 通过，**本次一并提交**）。

## 已确定决策（2026-09-07）

| 项 | 决策 |
|---|---|
| 默认匹配方式 | 模糊子串 contains（现状保持，不引入"精确模式"） |
| 「拆词匹配」勾选 | **新增**，默认**开**；开 = 非字母数字拆词 AND；关 = 整串 contains（= 现状） |
| 「忽略词序」勾选 | **新增**，默认**开**；开 = 词序无关；关 = 词按输入顺序出现（有序贪心扫描） |
| 拆词规则 | 预编译正则 `[^\p{L}\p{N}]+`，词去空；拆词与词表在触发搜索时预算 1 次 |
| 匹配范围 | **全字段 A**（方法名/HTTP/URL/类名/描述/参数名），与现状搜索范围一致 |
| 语义 | **AND**：所有词都必须命中；单词查询行为不变 |
| 大小写 | 沿用「区分大小写」勾选：默认忽略、开启精确；**过滤与高亮联动** |
| 高亮 | 多词各自命中区间收集 → 排序**合并重叠** → 分段黄底黑字，避免嵌套 span 破坏 HTML |
| 性能 | 每 endpoint 仍只构建 1 次可检索文本；有序扫描单趟贪心、无序 K 次 contains，毫秒级 |
| UI | 标题栏右对齐 4 勾选：仅限当前模块 / 区分大小写 / 拆词匹配 / 忽略词序；「忽略词序」在「拆词匹配」关闭时置灰 |

## 阶段

### Phase 0：收尾「区分大小写」改动（前置）
- [ ] `EndpointSearchPopup.kt` + 双语文案（search.endpoint.caseSensitive）当前未提交；开发完成后与本次改动一起提交打包
- [ ] 状态：BUILD SUCCESSFUL 已验证（zip 08:43）

### Phase 1：拆词工具 + 过滤逻辑（核心）
- [ ] 新增顶层私有函数 `splitSearchTerms(query)` + 预编译正则常量
- [ ] `runEndpointSearch` 入参 query → 词列表：`terms.all { text.contains(it, ...) }`，AND 语义
- [ ] 词序敏感模式（忽略词序关闭时）：有序贪心扫描（从上一个命中位置之后继续找下一个词）
- [ ] caseSensitive 联动：精确 contains vs ignoreCase=true

### Phase 2：UI 两个勾选
- [ ] `caseCheck` 旁新增 `wordMatchCheck`（拆词匹配）+ `ignoreOrderCheck`（忽略词序）
- [ ] 联动：`ignoreOrderCheck.isEnabled = wordMatchCheck.isSelected`；任一变化且有输入 → `schedule()` 重新搜索
- [ ] 双语文案：`search.endpoint.wordMatch=Match by words` / `search.endpoint.ignoreOrder=Ignore word order`（zh：拆词匹配 / 忽略词序）

### Phase 3：高亮多词化
- [ ] `highlightHtml(text, terms, caseSensitive, ordered)`：collect 区间 → 排序合并 → 分段渲染
- [ ] 渲染器预算 `highlightTerms`（接收原始词列表，空列表不高亮）；ordered 模式只在有序命中处高亮
- [ ] `schedule()` 传词列表给渲染器（与过滤同一份拆分结果）

### Phase 4：验证
- [ ] `gradle buildPlugin` 通过
- [ ] 场景矩阵验证（手动 runIde 或先静态走查）：
  - `order` → order/order2/abcorder ✅；`api/order`（拆词开+忽略词序关）→ api/order、api/a/orderb ✅，order/a/api ❌
  - 拆词开+忽略词序开 → order/a/api ✅；拆词关 → 整串 api/order 才命中
  - 区分大小写开：get 不匹配 getOrderList
- [ ] 走查：无新引入问题，提交 + 打包 zip

## 验证策略
- 每阶段 `gradle buildPlugin` 编译验证（首选）
- 未作 UI 自动化测试；行为验证依赖场景矩阵 + 用户 runIde 验收

## 不做（本期）
- 精确整段匹配模式（引号语法）——用户明确"默认模糊查就行"，预留不做
- OR 语义（任一词命中）——需求为 AND，暂不做
- 仅 URL 字段匹配（方案 B）——本期按全字段 A；如后续要求可加字段开关

## 历史归档（2026-08-31 Hoppscotch→MyHope 改造，已完成）
- 目标：`com.hoppscotch.sync` → `com.myhope.sync`（2.0.0），MyHopeClient REST 接入 /pr-api/
- 关键决策：仅增量 pushOplog ≤500/批、OCC 409 server_max_seq 重推≤3、禁全量推送；三层隔离 access_instance_id/user_app_id/workspace_id；快照字段白名单+禁带保留字段；force 覆盖已取消
- 全部 9 阶段已完成；详细内容见该时点版本的 git 历史（`git log` 2026-08-31 前后提交）