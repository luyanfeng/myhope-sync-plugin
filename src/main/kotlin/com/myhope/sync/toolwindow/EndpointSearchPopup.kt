package com.myhope.sync.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
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
import com.intellij.psi.PsiManager
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
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
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

/** 防抖时长（毫秒）：停止输入这么久后才真正执行解析 + 过滤。 */
private const val SEARCH_DEBOUNCE_MS = 2000L

/**
 * 弹出式全局端点搜索（类似 ApiPost Helper 的 Ctrl+\ 接口搜索）。
 *
 * 打开时不预扫、不预列任何端点，仅展示 "Type to search…" 提示；
 * 输入触发 2 秒防抖，用户停止输入后才在后台实时解析当前项目的
 * Controller（[SpringControllerParser.parseAllControllers]）并按关键字过滤。
 */
fun showEndpointSearch(project: Project) {
    val model = DefaultListModel<Any>()
    val debounce = Alarm(Alarm.ThreadToUse.SWING_THREAD)
    // 单调递增的搜索序号：仅最新一次触发的结果允许回填，避免旧请求覆盖新结果。
    var searchCounter = 0

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

    val list = JBList(model)
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.isFocusable = false // 保持焦点在搜索框，方向键由搜索框统一驱动
    list.fixedCellHeight = 40
    list.cellRenderer = EndpointRowRenderer()

    val scroll = JBScrollPane(list)
    // 默认宽度较宽（参考成熟接口搜索弹窗），用户可拖拽右下角改尺寸
    scroll.preferredSize = Dimension(1040, 360)

    /** 按可见行数动态收缩列表高度：占位/空结果时收得很矮，结果多时最多到 360。 */
    fun fitListHeight(rowCount: Int) {
        // 占位或空结果：显示 1 行的高矮；否则最多显示 8 行（约 320px），并从 preferredSize 同步
        list.visibleRowCount = rowCount.coerceIn(1, 8)
        scroll.preferredSize = Dimension(
            scroll.preferredSize.width,
            (rowCount.coerceIn(1, 8) * list.fixedCellHeight + 12).coerceAtMost(360)
        )
        // pack(relayoutContent, reposition)：重排内容并重新定位，以匹配收缩后的尺寸
        popup?.pack(false, true)
    }

    fun showPlaceholder(message: String) {
        model.clear()
        model.addElement(PlaceholderRow(message))
        fitListHeight(1)
    }

    fun showResults(results: List<EndpointSearchItem>) {
        model.clear()
        if (results.isEmpty()) {
            model.addElement(PlaceholderRow(I18n.message(project, "search.endpoint.noMatch")))
        } else {
            results.forEach { model.addElement(it) }
        }
        fitListHeight(model.size())
        if (model.size() > 0) {
            list.selectedIndex = 0
            list.ensureIndexIsVisible(0)
        }
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
        val query = searchField.text.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) {
            showPlaceholder(I18n.message(project, "search.endpoint.typeToSearch"))
            return
        }
        showPlaceholder(I18n.message(project, "search.endpoint.searching"))
        val requestId = ++searchCounter
        debounce.addRequest({
            recordHistory(query)
            val moduleNames = if (limitCheck.isSelected) setOfNotNull(currentModule) else null
            runEndpointSearch(project, query, requestId, moduleNames) { results ->
                // 弹窗已关闭或期间产生了更新的输入时，丢弃过期结果。
                if (popup?.isDisposed != true && searchCounter == requestId) {
                    showResults(results)
                }
            }
        }, SEARCH_DEBOUNCE_MS)
    }

    fun openSelected() {
        val value = list.selectedValue ?: return
        if (value !is EndpointSearchItem) return
        popup?.closeOk(null)
        navigateToSource(project, value)
    }

    fun moveSelection(delta: Int) {
        if (model.size() == 0) return
        var next = list.selectedIndex + delta
        if (next < 0) next = 0
        if (next >= model.size()) next = model.size() - 1
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
            if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                openSelected()
            }
        }
    })

    val topPanel = JPanel(BorderLayout(6, 0))
    topPanel.add(searchField, BorderLayout.CENTER)
    topPanel.add(limitCheck, BorderLayout.EAST)

    val content = JPanel(BorderLayout())
    content.border = JBEmptyBorder(6, 6, 6, 6)
    content.add(topPanel, BorderLayout.NORTH)
    content.add(scroll, BorderLayout.CENTER)

    showPlaceholder(I18n.message(project, "search.endpoint.typeToSearch"))
    val created = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(content, searchField)
        .setRequestFocus(true)
        .setTitle(I18n.message(project, "search.endpoint.title"))
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
    // 预热：后台提前解析全部 module 的缓存，用户输入首个查询时直接命中，避免首次等 1-3 秒。
    ProgressManager.getInstance().run(object : Task.Backgroundable(
        project,
        I18n.message(project, "search.endpoint.title"),
        false
    ) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            resolveGroups(project, null)
        }
    })
}

/**
 * 在后台线程实时解析当前项目的全部 Controller，按 [query]（小写）过滤，
 * 结果通过 [onResult] 回传（回调切回 EDT）。解析前校验弹窗仍然存活。
 */
private fun runEndpointSearch(
    project: Project,
    query: String,
    requestId: Int,
    moduleNames: Set<String>? = null,
    onResult: (List<EndpointSearchItem>) -> Unit
) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(
        project,
        I18n.message(project, "search.endpoint.title"),
        false
    ) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            // 读按 module 粒度的缓存解析结果（首次触发解析，之后复用，仅该 module 变更时重算）
            val groups = resolveGroups(project, moduleNames)
            val results = groups.flatMap { group ->
                group.endpoints.map { EndpointSearchItem(group, it) }
            }.filter { buildSearchText(it.group, it.endpoint).contains(query) }
            SwingUtilities.invokeLater {
                onResult(results)
            }
        }
    })
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
 * 构建某一条目的可检索文本（小写）：方法名、HTTP 方法、完整路径、相对路径、
 * Controller 类名、描述、apiTag 以及所有参数名。
 */
private fun buildSearchText(group: ControllerGroup, endpoint: SpringEndpoint): String = buildString {
    append(endpoint.methodName.lowercase(Locale.ROOT))
    append(' ')
    append(endpoint.httpMethod.name.lowercase(Locale.ROOT))
    append(' ')
    append(endpoint.fullPath.lowercase(Locale.ROOT))
    append(' ')
    append(endpoint.path.lowercase(Locale.ROOT))
    append(' ')
    append(group.controllerClassName.lowercase(Locale.ROOT))
    append(' ')
    append(endpoint.description?.lowercase(Locale.ROOT) ?: "")
    append(' ')
    append(group.apiTag?.lowercase(Locale.ROOT) ?: "")
    for (parameter in endpoint.parameters) {
        append(' ')
        append(parameter.name.lowercase(Locale.ROOT))
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
 * 行渲染器：第一行为彩色加粗 HTTP 方法 + 完整路径，第二行灰色显示描述或 Controller 类名。
 * [PlaceholderRow] 渲染为单行灰色斜体提示。
 */
private class EndpointRowRenderer : ListCellRenderer<Any> {
    private val panel = JPanel()
    private val titleLabel = JBLabel()
    private val subtitleLabel = JBLabel()

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

        if (value is PlaceholderRow) {
            titleLabel.text = "<html><i><font color='#8a8a8a'>${htmlEscape(value.text)}</font></i></html>"
            subtitleLabel.isVisible = false
            return panel
        }
        val item = value as EndpointSearchItem
        val color = httpMethodColor(item.endpoint.httpMethod)
        // 单行：彩色方法 + 路径 + 灰色「（类#方法）· in Module 模块」
        val tail = "（${item.endpoint.controllerClassQualifiedName}#${item.endpoint.methodName}）· in Module ${item.group.moduleName}"
        titleLabel.text =
            "<html><b><font color='$color'>${item.endpoint.httpMethod.name}</font></b>" +
            "&nbsp;&nbsp;${htmlEscape(item.endpoint.fullPath)}" +
            "&nbsp;&nbsp;<font color='#8a8a8a'>${htmlEscape(tail)}</font></html>"
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
 * 按 module 粒度缓存已解析的 [ControllerGroup]，避免每次搜索都全量重扫 PSI。
 *
 * - 每个 module 一条缓存项；用 [PsiModificationTracker] 的全局修改计数做 stamp，
 *   仅当计数变化时才整体失效重建（代码不变时复用，性能达标）。
 * - IDEA 启动后首次访问触发解析（搜索弹窗会主动预热），之后秒级返回。
 * - key 用 module.name；同一 project 内 module 名唯一。
 */
private object ControllerIndexCache {
    private data class Entry(val stamp: Long, val groups: List<ControllerGroup>)
    // key 用 Module 对象（跨 project 的 Module 实例不同，避免同名 module 串缓存）
    private val cache = ConcurrentHashMap<Module, Entry>()

    fun get(project: Project, module: Module): List<ControllerGroup> {
        val stamp = PsiManager.getInstance(project).modificationTracker.modificationCount
        cache.entries.removeIf { it.value.stamp != stamp } // 代码有改动时清失效项
        val cached = cache[module]
        if (cached != null) return cached.groups
        val groups = try {
            SpringControllerParser(project).parseModule(module)
        } catch (e: Exception) {
            emptyList()
        }
        cache[module] = Entry(stamp, groups)
        return groups
    }

    /** 清空全部缓存（如需要强制刷新时调用）。 */
    fun clear() = cache.clear()
}

/**
 * 解析目标 module 集合的 Controller 分组（优先命中 [ControllerIndexCache]）。
 * [moduleNames] 为 null 表示全部 module；非 null 时只取名称命中的 module。
 * 需在读操作外调用时自行包 [ApplicationManager.getApplication().runReadAction]。
 */
private fun resolveGroups(project: Project, moduleNames: Set<String>?): List<ControllerGroup> {
    val modules = ModuleManager.getInstance(project).modules
    val target = if (moduleNames != null) modules.filter { it.name in moduleNames } else modules.toList()
    return ApplicationManager.getApplication().runReadAction(Computable {
        target.flatMap { ControllerIndexCache.get(project, it) }
    })
}
