# 研究发现：接口搜索增强（拆词/词序/模糊/高亮）

> 创建 2026-09-07。旧的 Hoppscotch→MyHope 研究发现（2026-07/08 请求数据生成优化）已过时，归档于文末。

## 需求结论（2026-09-07 与用户确认）

- 默认模糊子串查询：`order` → `order2`、`abcorder` 都命中（现状保持）
- 多词自动拆词 AND：`api/order` → `[api, order]`，两者都必须出现（`api/order2`、`abcapi/orderc` 也命中）
- 「拆词匹配」「忽略词序」两个勾选项（正交）：
  - 拆词开 + 忽略词序关：词按输入顺序出现（有序贪心扫描）
  - 拆词开 + 忽略词序开：词序无关（各词独立 contains，AND）
  - 拆词关：整串子串（= 现状），忽略词序无意义 → 置灰
- 匹配范围：全字段（方法名/HTTP/URL/类名/描述/参数名），与现状 buildSearchText 一致
- 大小写：沿用「区分大小写」勾选，过滤与高亮联动

## 现状代码要点（EndpointSearchPopup.kt）

- 过滤：`runEndpointSearch` 后台线程 → `groups.flatMap { endpoints.map { EndpointSearchItem } }.filter { buildSearchText(...).contains(query) }`
- `buildSearchText`：方法名+HTTP+fullPath+path+类名+描述+apiTag+参数名，空格分隔（2026-09-07 已去掉内部 lowercase，保留原始大小写供大小写敏感比较）
- 高亮：`highlightHtml(text, query, caseSensitive)` 单词黄底 `#fff59d` + 黑字，渲染器 `highlightTerms` 尚未多词化（当前为 highlightQuery 单词 + caseSensitive 属性）
- 渲染器：`EndpointRowRenderer`，标题行 = 彩色 HTTP 方法 + fullPath + 灰色 tail（类#方法 · in Module），三段分别过 `highlightHtml`
- schedule()：防抖 2000ms，`rawQuery`/`caseSensitive` 已传到渲染器（区分大小写功能 2026-09-07 完成、未提交）
- 分页：SEARCH_PAGE_SIZE=300 + NextPageRow 尾行追加（已提交 `85020e1`）；行高 50 / 封顶 8 行（`0fb6cec`）

## 性能依据

- 拆词、词表在每次真实搜索与渲染器设置时各预算 1 次，不随行数放大
- 每 endpoint 只构建 1 次 searchText；无序 = K 次 contains（K=2~4，O(N·L·K) 毫秒级）；有序 = 单趟贪心扫描
- 结果增多 → 现有分页 300/页兜底，无渲染风暴风险
- 高亮：多词区间收集 + 排序合并 + 分段着色，每行 3 段文本、每段几十~百字符，可忽略不计

## 风险与对策

- HTML 嵌套破坏：重叠命中区间必须合并后再着色（多词命中同一区域时）
- 拆词正则误拆：`{id}` 会被拆成 `{`/`id`/`}` → 词 `id` 可命中 `{id}` 场景；`api/order /xxx/ccc` 会被拆成 4 词全 AND（用户可接受，已在方案中明示）
- 顺序敏感语义边界：词序关时「api 先于 order」指跨整条 searchText 的顺序（不是严格连续子串），符合"按序贪心扫描"预期

## 历史归档（2026-07/08 请求数据生成优化，已过时）

- 原 findings.md 记录了 P0-1 路径变量占位值 / P0-2 MultipartFile / P1-1 multipart 格式 / P1-2 类型感知默认值等 Hoppscotch 阶段研究，全部已完成并随 Hoppscotch→MyHope 改造（2026-08-31）落地，不再适用