package com.myhope.sync.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.Alarm
import com.intellij.util.ui.JBEmptyBorder
import com.myhope.sync.index.EndpointSearchIndex
import com.myhope.sync.model.ControllerGroup
import com.myhope.sync.model.HttpMethod
import com.myhope.sync.model.SpringEndpoint
import com.myhope.sync.psi.SpringControllerParser
import com.myhope.sync.settings.ProjectAppSettings
import com.myhope.sync.util.I18n
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.AbstractListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 端点搜索结果行。
 *
 * [group] 为该端点所属的 Controller 分组，[endpoint] 为端点本身，
 * 用于后续跳转到源码 [PsiMethod]。
 */
data class EndpointSearchItem(
    val group: ControllerGroup,
    val endpoint: SpringEndpoint
)

/**
 * 占位行（不可跳转）：用于展示 "Type to search…" / "Searching…" / "No match" 等文案。
 */
private class PlaceholderRow(val text: String)

/**
 * 「加载更多」尾行（不可跳转）：单击或回车后把下一批结果追加到列表。
 * [label] 为已格式化的显示文案（在 showEndpointSearch 内用 I18n 生成）。
 */
private class NextPageRow(val label: String)

/**
 * 批量广播的列表模型：整批元素只触发一次 intervalAdded / intervalRemoved。
 *
 * 不能用 [DefaultListModel]：它逐条 addElement 会逐次 fireIntervalAdded，
 * 触发 BasicListUI/WideSelectionListUI 对 model 全量重算 preferredSize
 * （每行跑一遍 cellRenderer+BasicHTML 布局），N 条 = O(N²) 次 HTML 布局，
 * 300 条一页就会让 EDT 冻结数十秒（详见 freeze dump：BasicHTML$Renderer._init_）。
 * 本模型以 [appendAll]/[setAll] 一次性注入整页，布局只重算 1 次。
 */
private class BulkListModel : AbstractListModel<Any>() {
    private val items = ArrayList<Any>()

    override fun getSize(): Int = items.size

    override fun getElementAt(index: Int): Any = items[index]

    fun get(index: Int): Any = items[index]

    fun isEmpty(): Boolean = items.isEmpty()

    fun lastElement(): Any? = items.lastOrNull()

    /** 清空后一次性注入 [newItems]（旧元素只广播一次 remove、新元素只广播一次 add）。 */
    fun setAll(newItems: List<Any>) {
        val oldSize = items.size
        items.clear()
        if (oldSize > 0) fireIntervalRemoved(this, 0, oldSize - 1)
        items.addAll(newItems)
        if (newItems.isNotEmpty()) fireIntervalAdded(this, 0, items.size - 1)
    }

    /** 末尾追加一批元素：整批只广播一次 intervalAdded，避免逐条 addElement 触发 O(N²) 布局重算。 */
    fun appendAll(newItems: List<Any>) {
        if (newItems.isEmpty()) return
        val start = items.size
        items.addAll(newItems)
        fireIntervalAdded(this, start, items.size - 1)
    }

    /** 移除末尾单个元素（「下一页」尾行移除场景）。 */
    fun removeLast() {
        if (items.isEmpty()) return
        val idx = items.size - 1
        items.removeAt(idx)
        fireIntervalRemoved(this, idx, idx)
    }
}

/** 防抖时长（毫秒）：停止输入这么久后才真正执行解析 + 过滤。 */
private const val SEARCH_DEBOUNCE_MS = 2000L

/** 搜索结果分页大小：每次渲染/追加的结果条数上限，避免 EDT 一次性渲染过多 HTML 行。 */
private const val SEARCH_PAGE_SIZE = 300

/**
 * 拆词分隔符：把查询串按非字母数字切分为多个词（`api/order` → `["api", "order"]`），
 * 多词时按 AND 语义过滤（所有词都必须命中）。
 */
private val WORD_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")

/** 按非字母数字拆词；拆不出词（纯符号输入）时返回空列表，由调用方降级为整串匹配。 */
private fun splitSearchTerms(query: String): List<String> =
    query.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }

/**
 * 弹出式全局端点搜索（类似 ApiPost Helper 的 Ctrl+\ 接口搜索）。
 *
 * 打开时不预扫、不预列任何端点，仅展示 "Type to search…" 提示；
 * 输入触发 2 秒防抖，用户停止输入后才在后台实时解析当前项目的
 * Controller（[SpringControllerParser.parseAllControllers]）并按关键字过滤。
 */
fun showEndpointSearch(project: Project) {
    val model = BulkListModel()
    val debounce = Alarm(Alarm.ThreadToUse.SWING_THREAD)
    // 单调递增的搜索序号：仅最新一次触发的结果允许回填，避免旧请求覆盖新结果。
    var searchCounter = 0

    // 搜索结果分页状态：pendingResults 为最近一次搜索的完整过滤结果（后台线程产出，EDT 消费）
    var pendingResults: List<EndpointSearchItem> = emptyList()
    // 已渲染进 model 的结果条数（不含 NextPageRow 尾行）
    var shownCount = 0

    // 搜索历史（内存副本，持久化于 ProjectAppSettings），historyIndex<0 表示不在历史回溯态
    val history: MutableList<String> = ProjectAppSettings.getInstance(project).endpointSearchHistory.toMutableList()
    var historyIndex = -1

    // 弹窗引用（在 schedule 闭包中用于判断弹窗是否已关闭，需先于闭包声明）
    var popup: JBPopup? = null

    val searchField = JBTextField()
    searchField.emptyText.text = I18n.message(project, "search.endpoint.typeToSearch")

    // 当前打开文件所属模块（用于 "Limit to this module"）；无打开文件时为 null
    val currentModule = computeCurrentModule(project)
    val limitCheck = JBCheckBox(I18n.message(project, "search.endpoint.limitModule")).apply {
        isEnabled = currentModule != null
        toolTipText = if (currentModule == null) {
            I18n.message(project, "search.endpoint.noModule")
        } else {
            I18n.message(project, "search.endpoint.limitModule") + ": $currentModule"
        }
    }

    // 区分大小写：默认关闭（忽略大小写过滤与高亮），勾选后按输入精确匹配
    val caseCheck = JBCheckBox(I18n.message(project, "search.endpoint.caseSensitive"))

    // 拆词匹配：默认开启（`api/order` 拆成 api+order，AND 语义）；关闭时整串子串匹配（默认开启时即模糊）
    val wordMatchCheck = JBCheckBox(I18n.message(project, "search.endpoint.wordMatch")).apply {
        isSelected = true
    }

    // 忽略词序：默认开启（各词独立命中即可）；关闭时词按输入顺序出现（仅拆词匹配开启时有意义，关闭拆词时置灰）
    val ignoreOrderCheck = JBCheckBox(I18n.message(project, "search.endpoint.ignoreOrder")).apply {
        isSelected = true
        isEnabled = wordMatchCheck.isSelected
    }

    val list = JBList(model)
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.isFocusable = false // 保持焦点在搜索框，方向键由搜索框统一驱动
    list.fixedCellHeight = 50
    val renderer = EndpointRowRenderer()
    list.cellRenderer = renderer

    val scroll = JBScrollPane(list)
    // 默认宽度 1040 * 1.5，用户可拖拽右下角改尺寸
    scroll.preferredSize = Dimension(1560, 360)

    /** 按可见行数动态收缩列表高度：占位/空结果时收得很矮，结果多时最多到 8 行高。 */
    fun fitListHeight(rowCount: Int) {
        // 占位或空结果：显示 1 行的高矮；否则最多显示 8 行，并从 preferredSize 同步
        list.visibleRowCount = rowCount.coerceIn(1, 8)
        scroll.preferredSize = Dimension(
            scroll.preferredSize.width,
            (rowCount.coerceIn(1, 8) * list.fixedCellHeight + 12).coerceAtMost(412)
        )
        // pack(relayoutContent, reposition)：重排内容并重新定位，以匹配收缩后的尺寸
        popup?.pack(false, true)
    }

    fun showPlaceholder(message: String) {
        model.setAll(listOf(PlaceholderRow(message)))
        fitListHeight(1)
    }

    /**
     * 从 [pendingResults] 追加一页：结果数据续接在列表尾部（非 clear+重建），
     * 若仍有剩余则在末尾补「下一页」尾行，直至全部显示完。
     */
    fun appendNextPage() {
        if (shownCount >= pendingResults.size) return
        // 若 model 末尾当前是"下一页"尾行，先移除，再追加新数据（数据接续，非替换）
        if (model.lastElement() is NextPageRow) {
            model.removeLast()
        }
        val take = minOf(SEARCH_PAGE_SIZE, pendingResults.size - shownCount)
        val slice = pendingResults.subList(shownCount, shownCount + take)
        shownCount += take
        // 本页数据 + 可选的"下一页"尾行合成一批，一次性广播（避免逐条/多次广播触发全量布局重算）
        val batch = ArrayList<Any>(take + 1)
        batch.addAll(slice)
        if (shownCount < pendingResults.size) {
            batch.add(NextPageRow(I18n.message(project, "search.endpoint.loadMore", shownCount, pendingResults.size)))
        }
        model.appendAll(batch)
        fitListHeight(model.size)
        // 定位到本次追加的首条结果：翻页/首屏后选中并滚动到该行，让用户直接看到新数据
        if (model.isEmpty()) return
        val firstNewRow = model.size - 1 - (if (model.lastElement() is NextPageRow) 1 else 0) - (take - 1)
        val newRow = if (firstNewRow >= 0) firstNewRow else 0
        list.selectedIndex = newRow
        list.ensureIndexIsVisible(newRow)
    }

    /** 只渲染第一页：重置分页状态后由 [appendNextPage] 渲染首批结果（或空态占位）。 */
    fun showResults(results: List<EndpointSearchItem>) {
        if (results.isEmpty()) {
            model.setAll(listOf(PlaceholderRow(I18n.message(project, "search.endpoint.noMatch"))))
            fitListHeight(1)
            return
        }
        pendingResults = results
        shownCount = 0
        appendNextPage()
    }

    /** 记录一次成功查询到搜索历史（去重、置顶、上限 20）并持久化。 */
    fun recordHistory(q: String) {
        if (q.isBlank()) return
        history.remove(q)
        history.add(0, q)
        while (history.size > 20) history.removeAt(history.lastIndex)
        ProjectAppSettings.getInstance(project).endpointSearchHistory = history.toList()
    }

    /** 历史回溯：forward=true 翻向更近的历史，false 翻向更早的历史；回填搜索框（触发重搜）。 */
    fun navigateHistory(forward: Boolean) {
        if (history.isEmpty()) return
        historyIndex = when {
            historyIndex < 0 -> if (forward) 0 else history.lastIndex
            forward -> (historyIndex + 1).coerceAtMost(history.lastIndex)
            else -> (historyIndex - 1).coerceAtLeast(0)
        }
        searchField.text = history[historyIndex]
    }

    /** 每次输入变化：取消旧请求并重新调度；空查询时直接回到初始提示态。 */
    fun schedule() {
        debounce.cancelAllRequests()
        // 用户手动输入与当前历史项不一致时，退出历史回溯态
        if (historyIndex >= 0 && searchField.text != history.getOrNull(historyIndex)) {
            historyIndex = -1
        }
        val rawQuery = searchField.text.trim()
        val caseSensitive = caseCheck.isSelected
        // 拆词：开启时按非字母数字拆分（AND 语义），拆不出词则降级整串；关闭时整串子串匹配（现状行为）
        val wordMatch = wordMatchCheck.isSelected
        val terms = if (wordMatch) splitSearchTerms(rawQuery).ifEmpty { listOf(rawQuery) } else listOf(rawQuery)
        val ignoreOrder = ignoreOrderCheck.isSelected
        // 同步匹配词到渲染器（保留原始大小写用于展示），供命中片段高亮
        renderer.highlightTerms = terms
        renderer.caseSensitive = caseSensitive
        renderer.ignoreOrder = ignoreOrder
        if (rawQuery.isEmpty()) {
            showPlaceholder(I18n.message(project, "search.endpoint.typeToSearch"))
            return
        }
        showPlaceholder(I18n.message(project, "search.endpoint.searching"))
        val requestId = ++searchCounter
        debounce.addRequest({
            recordHistory(rawQuery)
            val moduleNames = if (limitCheck.isSelected) setOfNotNull(currentModule) else null
            runEndpointSearch(project, requestId, moduleNames, caseSensitive, terms, ignoreOrder) { results ->
                // 弹窗已关闭或期间产生了更新的输入时，丢弃过期结果。
                if (popup?.isDisposed != true && searchCounter == requestId) {
                    showResults(results)
                }
            }
        }, SEARCH_DEBOUNCE_MS)
    }

    fun openSelected() {
        val value = list.selectedValue ?: return
        when (value) {
            is NextPageRow -> { appendNextPage(); return } // 翻页不关弹窗
            is PlaceholderRow -> return
            is EndpointSearchItem -> { popup?.closeOk(null); navigateToSource(project, value) }
        }
    }

    fun moveSelection(delta: Int) {
        if (model.size == 0) return
        var next = list.selectedIndex + delta
        if (next < 0) next = 0
        if (next >= model.size) next = model.size - 1
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    searchField.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = schedule()
        override fun removeUpdate(e: DocumentEvent) = schedule()
        override fun changedUpdate(e: DocumentEvent) = schedule()
    })

    searchField.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            // Alt+A / Alt+Shift+A：在搜索历史间回溯（对齐参考图 Alt+a 语义，仅弹窗内局部生效）
            if (e.isAltDown && e.keyCode == KeyEvent.VK_A) {
                e.consume()
                navigateHistory(!e.isShiftDown)
                return
            }
            when (e.keyCode) {
                KeyEvent.VK_ENTER -> {
                    e.consume()
                    openSelected()
                }
                KeyEvent.VK_DOWN -> {
                    e.consume()
                    moveSelection(1)
                }
                KeyEvent.VK_UP -> {
                    e.consume()
                    moveSelection(-1)
                }
            }
        }
    })

    list.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e)) return
            val row = list.locationToIndex(e.point)
            if (row >= 0 && model.get(row) is NextPageRow) { appendNextPage(); return }
            if (e.clickCount == 2) { openSelected() }
        }
    })

    val topPanel = JPanel(BorderLayout(6, 0))
    topPanel.add(searchField, BorderLayout.CENTER)

    val content = JPanel(BorderLayout())
    content.border = JBEmptyBorder(2, 2, 2, 2)
    // 标题栏：标题文本左对齐，「仅限当前模块」「区分大小写」勾选右对齐
    val titleLabel = JBLabel("<html><b>" + I18n.message(project, "search.endpoint.title") + "</b></html>")
    val titleBar = JPanel(BorderLayout()).apply {
        border = JBEmptyBorder(0, 0, 2, 0)
        add(titleLabel, BorderLayout.CENTER)
        val rightChecks = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        rightChecks.add(limitCheck)
        rightChecks.add(caseCheck)
        rightChecks.add(wordMatchCheck)
        rightChecks.add(ignoreOrderCheck)
        add(rightChecks, BorderLayout.EAST)
    }
    // 顶部固定高度的标题栏+搜索框垂直堆叠，结果区（scroll）放 CENTER 弹性收缩，
    // 避免搜索框被弹性布局拉得过高。
    val header = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(titleBar)
        add(topPanel)
    }
    content.add(header, BorderLayout.NORTH)
    content.add(scroll, BorderLayout.CENTER)

    // 输入框有内容时切换「仅限当前模块」→ 立即重新过滤（复用 schedule 的防抖与搜索链路）
    limitCheck.addActionListener {
        if (!searchField.text.trim().isEmpty()) schedule()
    }

    // 切换「区分大小写」→ 立即按新的匹配模式重新过滤与高亮
    caseCheck.addActionListener {
        if (!searchField.text.trim().isEmpty()) schedule()
    }

    // 切换「拆词匹配」→ 重新过滤；关闭拆词时「忽略词序」失去意义，置灰禁用
    wordMatchCheck.addActionListener {
        ignoreOrderCheck.isEnabled = wordMatchCheck.isSelected
        if (!searchField.text.trim().isEmpty()) schedule()
    }

    // 切换「忽略词序」→ 重新按词序敏感/无关模式过滤与高亮
    ignoreOrderCheck.addActionListener {
        if (!searchField.text.trim().isEmpty()) schedule()
    }

    showPlaceholder(I18n.message(project, "search.endpoint.typeToSearch"))
    val created = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(content, searchField)
        .setRequestFocus(true)
        .setResizable(true)
        .setMovable(true)
        .setMinSize(Dimension(420, 240))
        .createPopup()
    popup = created
    created.addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) {
            debounce.cancelAllRequests()
            debounce.dispose()
        }
    })
    created.showCenteredInCurrentWindow(project)
}

/**
 * 在后台线程实时解析当前项目的全部 Controller，按 [terms] 过滤：所有词 AND 命中
 * （[ignoreOrder] 为 true 时各词独立查找；为 false 时词按列表顺序贪心出现）。
 * [caseSensitive] 为 true 时精确匹配，否则忽略大小写。结果通过 [onResult] 回传（回调切回 EDT）。
 * 解析前校验弹窗仍然存活。
 */
private fun runEndpointSearch(
    project: Project,
    requestId: Int,
    moduleNames: Set<String>? = null,
    caseSensitive: Boolean,
    terms: List<String>,
    ignoreOrder: Boolean,
    onResult: (List<EndpointSearchItem>) -> Unit
) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(
        project,
        I18n.message(project, "search.endpoint.title"),
        false
    ) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            // 读按 module 粒度的缓存解析结果（命中缓存返回；未命中现场解析该 module）
            val groups = EndpointSearchIndex.resolveGroups(project, moduleNames)
            val results = groups.flatMap { group ->
                group.endpoints.map { EndpointSearchItem(group, it) }
            }.filter { item ->
                matchSearchText(buildSearchText(item.group, item.endpoint), terms, caseSensitive, ignoreOrder)
            }
            SwingUtilities.invokeLater {
                onResult(results)
            }
        }
    })
}

/**
 * 判断 [text] 是否命中查询词列表 [terms]：AND 语义（所有词都出现）。
 * [ignoreOrder] 为 true 时各词独立查找、顺序无关；为 false 时词须按列表顺序贪心出现
 * （第 k+1 个词只从第 k 个词命中位置之后查找）。
 * [caseSensitive] 为 true 时精确匹配，否则忽略大小写。空词表视为不命中。
 */
private fun matchSearchText(text: String, terms: List<String>, caseSensitive: Boolean, ignoreOrder: Boolean): Boolean {
    if (terms.isEmpty() || text.isEmpty()) return false
    val hay = if (caseSensitive) text else text.lowercase(Locale.ROOT)
    val needles = if (caseSensitive) terms else terms.map { it.lowercase(Locale.ROOT) }
    if (ignoreOrder) {
        return needles.all { hay.contains(it) }
    }
    // 顺序敏感：贪心顺序扫描，前一个词命中之后才找下一个词
    var from = 0
    for (needle in needles) {
        val idx = hay.indexOf(needle, from)
        if (idx < 0) return false
        from = idx + needle.length
    }
    return true
}

/**
 * 依据 [item] 定位源码 [PsiMethod] 并跳转；找不到时提示用户。
 */
private fun navigateToSource(project: Project, item: EndpointSearchItem) {
    val methodName = item.endpoint.methodName
    val qualifiedName = item.endpoint.controllerClassQualifiedName
    val target: PsiMethod? = ApplicationManager.getApplication().runReadAction(Computable {
        PsiShortNamesCache.getInstance(project)
            .getMethodsByName(methodName, GlobalSearchScope.projectScope(project))
            .firstOrNull { it.containingClass?.qualifiedName == qualifiedName }
    })
    if (target != null) {
        target.navigate(true)
    } else {
        Messages.showInfoMessage(
            project,
            I18n.message(project, "search.endpoint.navigateFailed", item.endpoint.fullPath),
            I18n.message(project, "search.endpoint.title")
        )
    }
}

/**
 * 构建某一条目的可检索文本（保留原始大小写，供大小写敏感过滤比较）：
 * 方法名、HTTP 方法、完整路径、相对路径、Controller 类名、描述、apiTag 以及所有参数名。
 */
private fun buildSearchText(group: ControllerGroup, endpoint: SpringEndpoint): String = buildString {
    append(endpoint.methodName)
    append(' ')
    append(endpoint.httpMethod.name)
    append(' ')
    append(endpoint.fullPath)
    append(' ')
    append(endpoint.path)
    append(' ')
    append(group.controllerClassName)
    append(' ')
    append(endpoint.description ?: "")
    append(' ')
    append(group.apiTag ?: "")
    for (parameter in endpoint.parameters) {
        append(' ')
        append(parameter.name)
    }
}

/**
 * 每行的 HTTP 方法展示色。
 */
private fun httpMethodColor(method: HttpMethod): String = when (method) {
    HttpMethod.GET -> "#2e7d32"
    HttpMethod.POST -> "#1565c0"
    HttpMethod.PUT -> "#ef6c00"
    HttpMethod.DELETE -> "#c62828"
    else -> "#616161"
}

/**
 * HTML 展示用最小转义。
 */
private fun htmlEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * 把 [text] 中所有命中词 [terms] 的片段用黄底黑字标注，返回可直接嵌入
 * `<html>` 的片段（未命中部分仅做 HTML 转义）。[terms] 为空时原样转义返回。
 *
 * 过滤与高亮语义一致：[caseSensitive] 为 true 时精确匹配，否则忽略大小写；
 * [ignoreOrder] 为 true 时高亮所有词的全部出现位置，否则只高亮按顺序贪心扫描到的命中
 * （与 [matchSearchText] 的顺序敏感判定一致）。重叠命中区间先合并再着色，避免嵌套 span。
 */
private fun highlightHtml(text: String, terms: List<String>, caseSensitive: Boolean, ignoreOrder: Boolean): String {
    if (terms.isEmpty() || text.isEmpty()) return htmlEscape(text)
    val hay = if (caseSensitive) text else text.lowercase(Locale.ROOT)
    val needles = if (caseSensitive) terms else terms.map { it.lowercase(Locale.ROOT) }

    // 收集命中区间（Pair 的 second 为排尾下标，语义与 substring(start, end) 一致）
    val ranges = ArrayList<Pair<Int, Int>>()
    if (ignoreOrder) {
        // 顺序无关：每个词的所有出现位置都收集
        for (needle in needles) {
            if (needle.isEmpty()) continue
            var from = 0
            while (true) {
                val idx = hay.indexOf(needle, from)
                if (idx < 0) break
                ranges.add(idx to idx + needle.length)
                from = idx + needle.length
            }
        }
    } else {
        // 顺序敏感：与过滤一致，贪心扫描每个词的第一个命中
        var from = 0
        for (needle in needles) {
            if (needle.isEmpty()) continue
            val idx = hay.indexOf(needle, from)
            if (idx < 0) continue
            ranges.add(idx to idx + needle.length)
            from = idx + needle.length
        }
    }
    if (ranges.isEmpty()) return htmlEscape(text)

    // 排序并按 start 合并重叠/相邻区间（相邻区间并成一个 span，避免过度分片）
    ranges.sortBy { it.first }
    val merged = ArrayList<Pair<Int, Int>>()
    for (range in ranges) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.second) {
            merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
        } else {
            merged.add(range)
        }
    }

    val sb = StringBuilder()
    var pos = 0
    for ((start, end) in merged) {
        sb.append(htmlEscape(text.substring(pos, start)))
        sb.append("<span style='background-color:#fff59d'><font color='#000000'>${htmlEscape(text.substring(start, end))}</font></span>")
        pos = end
    }
    sb.append(htmlEscape(text.substring(pos)))
    return sb.toString()
}

/**
 * 行渲染器：第一行为彩色加粗 HTTP 方法 + 完整路径，第二行灰色显示描述或 Controller 类名。
 * [PlaceholderRow] 渲染为单行灰色斜体提示。
 */
private class EndpointRowRenderer : ListCellRenderer<Any> {
    private val panel = JPanel()
    private val titleLabel = JBLabel()
    private val subtitleLabel = JBLabel()

    /** 当前搜索框输入的匹配词列表（保留原始大小写）；命中片段在渲染时高亮，空列表不高亮。 */
    var highlightTerms: List<String> = emptyList()

    /** 是否区分大小写（与搜索过滤保持一致）；true 时高亮精确匹配。 */
    var caseSensitive: Boolean = false

    /** 是否忽略词序（与搜索过滤保持一致）；true 时高亮所有词的全部出现，false 只高亮顺序贪心命中。 */
    var ignoreOrder: Boolean = true

    init {
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBEmptyBorder(2, 8, 2, 8)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        subtitleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(subtitleLabel)
    }

    override fun getListCellRendererComponent(
        list: JList<out Any>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val background = if (isSelected) list.selectionBackground else list.background
        val foreground = if (isSelected) list.selectionForeground else list.foreground
        panel.background = background
        panel.isOpaque = true
        titleLabel.foreground = foreground

        // 占位行与「下一页」尾行共用同一样式：单行灰色斜体，子标题隐藏
        val placeholderText = when (value) {
            is PlaceholderRow -> value.text
            is NextPageRow -> value.label
            else -> null
        }
        if (placeholderText != null) {
            titleLabel.text = "<html><i><font color='#8a8a8a'>${htmlEscape(placeholderText)}</font></i></html>"
            subtitleLabel.isVisible = false
            return panel
        }
        val item = value as EndpointSearchItem
        val color = httpMethodColor(item.endpoint.httpMethod)
        // 单行：彩色方法 + 路径 + 灰色「（类#方法）· in Module 模块」；类名过长时包名简写
        val displayClass = abbreviateControllerName(item.endpoint.controllerClassQualifiedName)
        val tail = "（$displayClass#${item.endpoint.methodName}）· in Module ${item.group.moduleName}"
        titleLabel.text =
            "<html><b><font color='$color'>${highlightHtml(item.endpoint.httpMethod.name, highlightTerms, caseSensitive, ignoreOrder)}</font></b>" +
            "&nbsp;&nbsp;${highlightHtml(item.endpoint.fullPath, highlightTerms, caseSensitive, ignoreOrder)}" +
            "&nbsp;&nbsp;<font color='#8a8a8a'>${highlightHtml(tail, highlightTerms, caseSensitive, ignoreOrder)}</font></html>"
        subtitleLabel.isVisible = false
        return panel
    }
}

/**
 * 当前编辑器打开文件所属模块名；无打开文件时返回 null。
 * 用于「Limit to this module」范围限定。
 */
private fun computeCurrentModule(project: Project): String? {
    val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
    return ModuleUtil.findModuleForFile(file, project)?.name
}

/**
 * 对 Controller 全限定名做包名简写：整行太长时把包各段缩写为首字母、类名保持完整。
 *
 * 例：`com.woshishui.green.AController` → `c.w.g.AController`。
 * 若简写后反而更短/包名已很短（最后一段点前内容不足），则保留完整名，避免过度缩写。
 */
private fun abbreviateControllerName(qualifiedName: String): String {
    val lastDot = qualifiedName.lastIndexOf('.')
    if (lastDot <= 0) return qualifiedName
    val pkg = qualifiedName.substring(0, lastDot)
    val cls = qualifiedName.substring(lastDot + 1)
    val abbr = pkg.split('.')
        .filter { it.isNotEmpty() }
        .joinToString(".") { it.substring(0, 1) }
    return "$abbr.$cls"
}

