package com.myhope.sync.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.myhope.sync.myhope.MyHopeClient
import com.myhope.sync.myhope.MyHopeDataConverter
import com.myhope.sync.model.ControllerGroup
import com.myhope.sync.model.EndpointParameter
import com.myhope.sync.model.ParamSource
import com.myhope.sync.model.SpringEndpoint
import com.myhope.sync.model.SyncResult
import com.myhope.sync.model.SyncStatus
import com.myhope.sync.model.computeEndpointHash
import com.myhope.sync.model.computeEndpointKey
import com.myhope.sync.psi.SpringControllerParser
import com.myhope.sync.service.SyncService
import com.myhope.sync.settings.ProjectAppSettings
import com.myhope.sync.settings.ProjectAppSettingsConfigurable
import com.myhope.sync.settings.getEffectiveSettings
import com.myhope.sync.settings.writeBackBinding
import com.myhope.sync.util.I18n
import com.myhope.sync.util.LogUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.RenderingHints
import java.awt.BasicStroke
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.FileWriter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

class MyHopeSyncPanel(private val project: Project) {

    private val log = Logger.getInstance(MyHopeSyncPanel::class.java)

    val content: JComponent
    private var table: JBTable
    private var frozenTable: JBTable
    private val tableModel: DefaultTableModel
    private val statusLabel: JLabel
    private val timeLabel: JLabel
    private var bottomPanel: JPanel
    private val searchField: JBTextField
    private val syncedOnlyCheckbox: JCheckBox
    private val copyHintLabel: JLabel
    private val projectButton: JButton
    private val columnsButton: JButton
    private val statsLabel: JLabel
    private val rowSorter: TableRowSorter<TableModel>

    private var scannedGroups: List<ControllerGroup> = emptyList()
    private var rowEndpoints = mutableListOf<SpringEndpoint>()
    private var rowGroups = mutableListOf<ControllerGroup>()
    private var rowSyncStatus = mutableListOf<SyncStatus>()

    /** 自动发现的全部模块/项目名 */
    private val allProjects = mutableListOf<String>()

    /** 显式选中的项目集合。
     *  大小等于 [allProjects] 时 = All（全部选中，默认）。
     *  为空时跳过所有项目。
     */
    private var selectedProjects: Set<String> = emptySet()

    // ── Column visibility ──
    private data class ColumnWidth(val min: Int, val pref: Int, val max: Int = Int.MAX_VALUE)

    /** 列索引 → (列名 i18n key, 默认宽度) */
    private val columnDefaults = mapOf(
        2 to ("table.column.path" to ColumnWidth(50, 300)),
        3 to ("table.column.controller" to ColumnWidth(50, 180)),
        4 to ("table.column.method" to ColumnWidth(110, 150, 150)),  // 固定宽度，需容纳中英文表头和 GET/POST/DELETE
        5 to ("table.column.parameters" to ColumnWidth(50, 200)),
        6 to ("table.column.project" to ColumnWidth(50, 120))
    )

    /**
     * 已从 columnModel 中移除的列，按 modelIndex 索引。
     * 填充列时用于恢复，避免重复创建 TableColumn。
     */
    private val removedColumns = mutableMapOf<Int, javax.swing.table.TableColumn>()

    /**
     * 复制当前选中单元格的文本内容到系统剪贴板 */
    private fun copySelectedCell(table: JTable) {
        try {
            val row = table.selectedRow
            val col = table.selectedColumn
            if (row >= 0 && col >= 0) {
                val modelRow = table.convertRowIndexToModel(row)
                val modelCol = table.convertColumnIndexToModel(col)
                val value = tableModel.getValueAt(modelRow, modelCol)
                val text = if (value is TagCellValue) {
                    if (value.line2 != null) "${value.line1} ${value.line2}" else value.line1
                } else {
                    value?.toString() ?: ""
                }
                if (text.isNotBlank()) {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(text), null)
                }
            }
        } catch (e: Exception) {
            log.warn("复制单元格失败", e)
        }
    }

    init {
        // ── 自动发现所有模块名（用于 Projects 下拉）──
        ApplicationManager.getApplication().runReadAction {
            val modules = ModuleManager.getInstance(project).modules
            allProjects.clear()
            allProjects.addAll(modules.map { it.name }.filter { it.isNotBlank() }.sorted())
        }
        // 从持久化缓存恢复上次选中的项目，过滤掉当前不存在的模块
        val cachedProjects = ProjectAppSettings.getInstance(project).getSelectedProjects()
        selectedProjects = if (cachedProjects.isEmpty()) allProjects.toSet()
            else cachedProjects.filter { it in allProjects }.toSet().ifEmpty { allProjects.toSet() }

        // ── Table columns: # | ☑ | Path | Controller | Method | Parameters | Project ──
        val columns = arrayOf(
            I18n.message(project, "table.column.index"),
            I18n.message(project, "table.column.checkbox"),
            I18n.message(project, "table.column.path"),
            I18n.message(project, "table.column.controller"),
            I18n.message(project, "table.column.method"),
            I18n.message(project, "table.column.parameters"),
            I18n.message(project, "table.column.project")
        )
        tableModel = object : DefaultTableModel(columns, 0) {
            override fun isCellEditable(row: Int, column: Int) = column == 1
            override fun getColumnClass(column: Int): Class<*> {
                return when (column) {
                    0 -> Int::class.java
                    1 -> Boolean::class.java
                    2, 3 -> TagCellValue::class.java
                    else -> String::class.java
                }
            }
            override fun setValueAt(aValue: Any?, row: Int, column: Int) {
                super.setValueAt(aValue, row, column)
                if (column == 1) updateStatsLabel()
            }
        }

        rowSorter = TableRowSorter(tableModel)

        table = JBTable(tableModel).apply {
            rowSorter = this@MyHopeSyncPanel.rowSorter
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            // Checkbox column (index 1)
            val checkboxColumn = getColumnModel().getColumn(1)
            checkboxColumn.minWidth = 90
            checkboxColumn.preferredWidth = 150
            checkboxColumn.maxWidth = 180
            // 显式 JCheckBox 渲染器，避免 JBTable/LAF 默认 Boolean 渲染失效
            checkboxColumn.cellRenderer = object : JCheckBox(), TableCellRenderer {
                init {
                    isBorderPainted = false
                    isOpaque = true
                    horizontalAlignment = SwingConstants.CENTER
                }
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    this.isSelected = value as? Boolean ?: false
                    background = if (isSelected) table?.selectionBackground ?: background
                    else table?.background ?: background
                    return this
                }
            }
            // 显式 JCheckBox 编辑器，点击自动切换选中状态
            checkboxColumn.cellEditor = object : AbstractCellEditor(), TableCellEditor {
                private val checkBox = JCheckBox().apply {
                    isBorderPainted = false
                    horizontalAlignment = SwingConstants.CENTER
                    addActionListener { stopCellEditing() }
                }
                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    checkBox.isSelected = value as? Boolean ?: false
                    return checkBox
                }
                override fun getCellEditorValue(): Any = checkBox.isSelected
            }

            // Sync status background renderer（非复选框列）
            val syncRenderer = SyncStatusCellRenderer()
            setDefaultRenderer(Int::class.java, syncRenderer)
            setDefaultRenderer(String::class.java, syncRenderer)

            // 路径列和接口列多行渲染器
            setDefaultRenderer(TagCellValue::class.java, TagCellRenderer())

            // Other column widths
            try { getColumn(I18n.message(project, "table.column.index"))?.apply {
                minWidth = 50; preferredWidth = 80; maxWidth = 130
            } } catch (_: Exception) {}
            for (colModel in listOf(
                I18n.message(project, "table.column.path") to 300,
                I18n.message(project, "table.column.controller") to 180,
                I18n.message(project, "table.column.method") to 80,
                I18n.message(project, "table.column.parameters") to 200,
                I18n.message(project, "table.column.project") to 120
            )) {
                try { getColumn(colModel.first)?.preferredWidth = colModel.second } catch (_: Exception) {}
            }
            try { getColumn(I18n.message(project, "table.column.syncStatus"))?.apply {
                minWidth = 0; preferredWidth = 0; maxWidth = 0; resizable = false
            } } catch (_: Exception) {}

            // 选中单元格 Ctrl+C 复制
            val mainTable = this@apply
            getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "copyCell"
            )
            getActionMap().put("copyCell", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    copySelectedCell(mainTable)
                }
            })

            // 鼠标悬停时显示复制提示
            val tableHoverAdapter = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    copyHintLabel.isVisible = true
                }
                override fun mouseExited(e: MouseEvent) {
                    copyHintLabel.isVisible = false
                }
            }
            addMouseListener(tableHoverAdapter)
        }

        // ── Frozen columns: 固定前两列（# 和复选框），不随左右滑动 ──
        frozenTable = object : JBTable(tableModel) {
            // 强制行高始终与主表一致，不考虑内部 setRowHeight 影响
            override fun getRowHeight(): Int = table.rowHeight
            override fun getRowHeight(row: Int): Int = table.rowHeight
            // 冻结表（索引列、勾选列）不需要 tooltip
            override fun getToolTipText(event: java.awt.event.MouseEvent?): String? = null
        }.apply {
            // 与主表共享同一个 rowSorter，排序过滤完全同步
            this.rowSorter = this@MyHopeSyncPanel.rowSorter
            // 只保留前两列
            while (columnModel.columnCount > 2) {
                columnModel.removeColumn(columnModel.getColumn(columnModel.columnCount - 1))
            }
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false

            // 索引列宽度
            try { getColumn(I18n.message(project, "table.column.index"))?.apply {
                minWidth = 50; preferredWidth = 80; maxWidth = 130
                // 渲染为 view 序数（过滤/排序后始终从 1 开始）
                cellRenderer = object : DefaultTableCellRenderer() {
                    override fun getTableCellRendererComponent(
                        table: JTable?, value: Any?, isSelected: Boolean,
                        hasFocus: Boolean, row: Int, column: Int
                    ): Component {
                        val comp = super.getTableCellRendererComponent(
                            table, row + 1, isSelected, hasFocus, row, column
                        )
                        horizontalAlignment = CENTER
                        return comp
                    }
                }
            } } catch (_: Exception) {}

            // 复选框列：显式 JCheckBox 渲染器/编辑器
            val frozenCheckbox = getColumnModel().getColumn(1)
            frozenCheckbox.minWidth = 90
            frozenCheckbox.preferredWidth = 150
            frozenCheckbox.maxWidth = 180
            frozenCheckbox.cellRenderer = object : JCheckBox(), TableCellRenderer {
                init { isBorderPainted = false; isOpaque = true; horizontalAlignment = SwingConstants.CENTER }
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    this.isSelected = value as? Boolean ?: false
                    background = if (isSelected) table?.selectionBackground ?: background
                    else table?.background ?: background
                    return this
                }
            }
            frozenCheckbox.cellEditor = object : AbstractCellEditor(), TableCellEditor {
                private val checkBox = JCheckBox().apply {
                    isBorderPainted = false
                    horizontalAlignment = SwingConstants.CENTER
                    addActionListener { stopCellEditing() }
                }
                override fun getTableCellEditorComponent(
                    table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    checkBox.isSelected = value as? Boolean ?: false
                    return checkBox
                }
                override fun getCellEditorValue(): Any = checkBox.isSelected
            }
            // 同步背景色渲染
            setDefaultRenderer(Int::class.java, SyncStatusCellRenderer())

            // 选中单元格 Ctrl+C 复制
            val frozenTableSelf = this@apply
            getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx), "copyCell"
            )
            getActionMap().put("copyCell", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    copySelectedCell(frozenTableSelf)
                }
            })

            // 鼠标悬停时显示复制提示
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    copyHintLabel.isVisible = true
                }
                override fun mouseExited(e: MouseEvent) {
                    copyHintLabel.isVisible = false
                }
            })
        }

        // 移除 frozenTable 自带的 header（否则在 row header viewport 中会使数据行下移偏移）
        frozenTable.setTableHeader(null)

        // 用 frozenTable 的 columnModel 创建一个独立的列头用于 corner 显示
        val cornerHeader = javax.swing.table.JTableHeader(frozenTable.columnModel).apply {
            reorderingAllowed = false
            resizingAllowed = false
        }

        // 隐藏主表前两列（由 frozenTable 替代显示）
        table.getColumnModel().getColumn(0).apply {
            minWidth = 0; maxWidth = 0; preferredWidth = 0; width = 0; resizable = false
        }
        table.getColumnModel().getColumn(1).apply {
            minWidth = 0; maxWidth = 0; preferredWidth = 0; width = 0; resizable = false
        }

        // 选中行同步（主表用 view 索引，frozenTable 无 rowSorter 直接用 model 索引）
        var syncingSelection = false
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !syncingSelection) {
                syncingSelection = true
                try {
                    val viewLead = table.selectionModel.leadSelectionIndex
                    if (viewLead >= 0) {
                        val modelLead = table.convertRowIndexToModel(viewLead)
                        val frozenViewLead = frozenTable.convertRowIndexToView(modelLead)
                        if (frozenViewLead >= 0) {
                            frozenTable.selectionModel.setSelectionInterval(frozenViewLead, frozenViewLead)
                        }
                    }
                } finally { syncingSelection = false }
            }
        }
        frozenTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !syncingSelection) {
                syncingSelection = true
                try {
                    val frozenViewLead = frozenTable.selectionModel.leadSelectionIndex
                    if (frozenViewLead >= 0) {
                        val modelLead = frozenTable.convertRowIndexToModel(frozenViewLead)
                        val viewLead = table.convertRowIndexToView(modelLead)
                        if (viewLead >= 0) {
                            table.selectionModel.setSelectionInterval(viewLead, viewLead)
                        }
                    }
                } finally { syncingSelection = false }
            }
        }

        // 创建带固定列的滚动面板
        val scrollPane = JBScrollPane(table).apply {
            setRowHeaderView(frozenTable)
            setCorner(JScrollPane.UPPER_LEFT_CORNER, cornerHeader)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        // 确保行头视口视图位置与主视口一致（同步滚动偏移）
        scrollPane.rowHeader.viewPosition = Point(0, scrollPane.verticalScrollBar.value)
        scrollPane.verticalScrollBar.addAdjustmentListener {
            scrollPane.rowHeader.viewPosition = Point(0, it.value)
        }

        // 恢复持久化的列可见性
        applyColumnVisibility()

        // 检测用户拖拽列宽并持久化
        table.getTableHeader().addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    saveColumnWidths()
                }
            }
        })

        // ── Status label ──
        statusLabel = JLabel(I18n.message(project, "status.ready"))
        statusLabel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        timeLabel = JLabel(I18n.message(project, "ui.elapsedPlaceholder")).apply {
            // 使用默认 LAF 字体，自动适配缩放，与 statusLabel 样式一致
            minimumSize = Dimension(130, preferredSize.height)
        }

        // ── Search field ──
        searchField = JBTextField().apply {
            emptyText.text = I18n.message(project, "filter.placeholder")
            toolTipText = I18n.message(project, "filter.tooltip")
            preferredSize = Dimension(220, preferredSize.height)
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = applyFilter()
                override fun removeUpdate(e: DocumentEvent) = applyFilter()
                override fun changedUpdate(e: DocumentEvent) = applyFilter()
            })
        }

        // ── Project multi-select button ──
        projectButton = JButton(I18n.message(project, "project.buttonAll")).apply {
            addActionListener { showProjectPopup() }
        }

        // ── Columns toggle button ──
        columnsButton = JButton(I18n.message(project, "button.columns")).apply {
            addActionListener { showColumnPopup() }
        }

        // ── Filter toolbar ──
        syncedOnlyCheckbox = JCheckBox(I18n.message(project, "filter.syncedOnly")).apply {
            addActionListener { applyFilter() }
        }

        val filterToolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
            add(searchField)
            add(Box.createHorizontalStrut(4))
            add(syncedOnlyCheckbox)
            add(Box.createHorizontalStrut(4))
            add(columnsButton)
            add(Box.createHorizontalGlue())
        }

        // ── Action buttons ──
        val syncButton = JButton(I18n.message(project, "button.syncSelected")).apply {
            addActionListener { onSyncSelected() }
        }
        val refreshButton = JButton(I18n.message(project, "button.refresh")).apply {
            addActionListener { onRefresh() }
        }
        val checkSyncButton = JButton(I18n.message(project, "button.checkSync")).apply {
            addActionListener { onCheckSyncStatus() }
        }
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(projectButton)
            add(Box.createHorizontalStrut(8))
            add(refreshButton)
            add(Box.createHorizontalGlue())
            val settingsButton = JButton(I18n.message(project, "button.settings")).apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ProjectAppSettingsConfigurable::class.java)
                }
            }
            add(settingsButton)
        }

        // ── Selection toolbar ──
        val selectAllButton = JButton(I18n.message(project, "button.selectAll")).apply {
            addActionListener { selectAll() }
        }
        val invertButton = JButton(I18n.message(project, "button.invert")).apply {
            addActionListener { invertSelection() }
        }
        statsLabel = JLabel().apply {
            foreground = JBColor.GRAY
        }
        val selectionToolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
            add(JLabel(I18n.message(project, "button.selectLabel")))
            add(selectAllButton)
            add(Box.createHorizontalStrut(4))
            add(invertButton)
            add(Box.createHorizontalStrut(8))
            add(checkSyncButton)
            add(Box.createHorizontalStrut(8))
            add(syncButton)
            add(Box.createHorizontalStrut(8))
            add(statsLabel)
            add(Box.createHorizontalGlue())
            val exportButton = JButton(I18n.message(project, "button.export")).apply {
                addActionListener { onExportCsv() }
            }
            add(exportButton)
        }

        // ── Root layout ──
        val northPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buttonPanel)
            add(filterToolbar)
            add(selectionToolbar)
        }
        copyHintLabel = JLabel(I18n.message(project, "status.copyHint")).apply {
            foreground = JBColor.GRAY
            isVisible = false
        }
        bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(statusLabel)
            add(Box.createHorizontalStrut(16))
            add(copyHintLabel)
            add(Box.createHorizontalGlue())
            add(JLabel("  "))
            add(timeLabel)
        }
        val rootPanel = JPanel(BorderLayout()).apply {
            add(northPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }
        content = rootPanel

        // ── 从缓存恢复上次刷新后的扫描数据 ──
        restoreCachedScanData()
    }

    // ====================================================================
    //  Sync status row renderer
    // ====================================================================

    /**
     * 根据每行的 [rowSyncStatus] 设置非编辑列的背景色。
     * 复选框列已有自己的渲染器，不受影响。
     */
    private inner class SyncStatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) {
                val sorter = table?.rowSorter
                val modelRow = if (sorter != null) {
                    try { sorter.convertRowIndexToModel(row) } catch (_: IndexOutOfBoundsException) { -1 }
                } else {
                    row
                }
                val status = if (modelRow in rowSyncStatus.indices) rowSyncStatus[modelRow]
                    else SyncStatus.UNSYNCED
                background = when (status) {
                    SyncStatus.SYNCED -> JBColor(
                        Color(232, 250, 232),   // 亮色主题：浅绿
                        Color(50, 75, 50)        // 暗色主题：深绿
                    )
                    SyncStatus.MODIFIED -> JBColor(
                        Color(220, 235, 252),   // 亮色主题：浅蓝
                        Color(45, 55, 75)        // 暗色主题：深蓝
                    )
                    SyncStatus.UNSYNCED -> null  // 默认背景
                }
            }
            return comp
        }
    }

    // ====================================================================
    //  Multi-line cell renderer — path & controller columns
    // ====================================================================

    /** 路径列/接口列的单元格值：首行 + 可选的第二行描述 */
    data class TagCellValue(val line1: String, val line2: String?) {
        override fun toString(): String = if (line2 != null) "$line1 $line2" else line1
    }

    /**
     * 多行单元格渲染器：
     * - 首行：纯文本（路径或类名），严格左对齐
     * - 第二行有 [TagCellValue.line2] 时：以圆角标签展示在下方
     * - 兼容同步状态背景色和选中高亮
     */
    private inner class TagCellRenderer : JLabel(), TableCellRenderer {
        private var line1 = ""
        private var line2: String? = null

        private val pad = 4   // 水平内边距
        private val tagPad = 6 // 标签文字内边距

        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val tagValue = value as? TagCellValue
            line1 = tagValue?.line1 ?: value?.toString() ?: ""
            line2 = tagValue?.line2

            val f = table?.font ?: UIManager.getFont("Label.font")
            font = f

            // 同步状态背景
            val sorter = table?.rowSorter
            val modelRow = if (sorter != null) {
                try { sorter.convertRowIndexToModel(row) } catch (_: IndexOutOfBoundsException) { -1 }
            } else { row }
            val status = if (modelRow in rowSyncStatus.indices) rowSyncStatus[modelRow]
            else SyncStatus.UNSYNCED

            if (isSelected) {
                background = table?.selectionBackground ?: table?.background ?: Color.WHITE
                foreground = table?.selectionForeground ?: table?.foreground ?: Color.BLACK
            } else {
                foreground = table?.foreground ?: Color.BLACK
                background = when (status) {
                    SyncStatus.SYNCED -> JBColor(Color(232, 250, 232), Color(50, 75, 50))
                    SyncStatus.MODIFIED -> JBColor(Color(220, 235, 252), Color(45, 55, 75))
                    SyncStatus.UNSYNCED -> table?.background ?: Color.WHITE
                }
            }
            return this
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val fm = g2.fontMetrics
            val w = width
            val h = height
            val arc = 12

            // 1. 单元格背景
            g2.color = background
            g2.fillRect(0, 0, w, h)

            // 2. 第一行 — 严格左对齐
            g2.color = foreground
            g2.drawString(line1, pad, fm.ascent + 2)

            // 3. 第二行标签 — 用前景色低 alpha 填充+描边，任何 LAF 高亮覆盖时光影统一
            val tagY = fm.height + 5
            val tagW = w - pad * 2
            val tagH = (fm.height + 6).coerceAtLeast(arc * 2)

            val alphaFill = 20
            val alphaBorder = 50
            g2.color = Color(foreground.red, foreground.green, foreground.blue, alphaFill)
            g2.fillRoundRect(pad, tagY, tagW, tagH, arc, arc)
            g2.color = Color(foreground.red, foreground.green, foreground.blue, alphaBorder)
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(pad, tagY, tagW, tagH, arc, arc)

            // 标签文字（暗一些，降低视觉层级）
            if (line2 != null) {
                g2.color = Color(foreground.red, foreground.green, foreground.blue, 140)
                g2.drawString(line2, pad + tagPad, tagY + fm.ascent + 1)
            }

            g2.dispose()
        }
    }

    // ====================================================================
    //  Selection
    // ====================================================================

    /** 选中所有可见（未被过滤）的行 */
    private fun selectAll() {
        for (viewRow in 0 until viewRowCount()) {
            val modelRow = rowSorter.convertRowIndexToModel(viewRow)
            tableModel.setValueAt(true, modelRow, 1)
        }
        updateStatsLabel()
    }

    /** 反选所有可见行 */
    private fun invertSelection() {
        for (viewRow in 0 until viewRowCount()) {
            val modelRow = rowSorter.convertRowIndexToModel(viewRow)
            val current = tableModel.getValueAt(modelRow, 1) as? Boolean ?: false
            tableModel.setValueAt(!current, modelRow, 1)
        }
        updateStatsLabel()
    }

    /** 排序/过滤后的可见行数 */
    private fun viewRowCount(): Int = rowSorter.viewRowCount

    /** 更新统计标签：选中/未选中、已同步/未同步（仅可见行） */
    private fun updateStatsLabel() {
        val visibleRows = viewRowCount()
        var selected = 0
        var synced = 0
        for (viewRow in 0 until visibleRows) {
            val modelRow = rowSorter.convertRowIndexToModel(viewRow)
            val checked = tableModel.getValueAt(modelRow, 1) as? Boolean ?: false
            if (checked) selected++
            if (modelRow in rowSyncStatus.indices && rowSyncStatus[modelRow] == SyncStatus.SYNCED) synced++
        }
        val unselected = visibleRows - selected
        val unsynced = visibleRows - synced
        statsLabel.text = I18n.message(project, "button.statsFormat", selected, unselected, synced, unsynced)
    }

    // ====================================================================
    //  Project multi-select popup
    // ====================================================================

    private fun updateProjectButton() {
        projectButton.text = when {
            selectedProjects.size == allProjects.size -> I18n.message(project, "project.buttonAll")
            selectedProjects.size == 1 -> I18n.message(project, "project.single", selectedProjects.first())
            selectedProjects.isEmpty() -> I18n.message(project, "project.none")
            else -> {
                val sorted = selectedProjects.sorted()
                I18n.message(project, "project.multi", sorted.first())
            }
        }
        // 重置 preferredSize 让按钮自动适应新文字的宽度
        projectButton.preferredSize = null
        projectButton.revalidate()
    }

    private fun showProjectPopup() {
        if (allProjects.isEmpty()) return

        val checkboxes = allProjects.map { project ->
            JCheckBox(project, project in selectedProjects).apply {
                isOpaque = false
                addActionListener {
                    if (isSelected) selectedProjects = selectedProjects + project
                    else selectedProjects = selectedProjects - project
                    updateProjectButton()
                    saveSelectedProjectsToCache()
                }
            }
        }

        // ── Header: 全选 / 反选 ──
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(JButton(I18n.message(project, "project.allPopup")).apply {
                addActionListener {
                    val allSelected = checkboxes.all { it.isSelected }
                    checkboxes.forEach { it.isSelected = !allSelected }
                    selectedProjects = checkboxes.filter { it.isSelected }.map { it.text }.toSet()
                    updateProjectButton()
                    saveSelectedProjectsToCache()
                }
            })
            add(JButton(I18n.message(project, "project.invert")).apply {
                addActionListener {
                    checkboxes.forEach { it.isSelected = !it.isSelected }
                    selectedProjects = checkboxes.filter { it.isSelected }.map { it.text }.toSet()
                    updateProjectButton()
                    saveSelectedProjectsToCache()
                }
            })
        }

        // ── Scrollable checkbox list ──
        val listPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val gbc = GridBagConstraints().apply {
                gridwidth = GridBagConstraints.REMAINDER
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
                weightx = 1.0
            }
            checkboxes.forEach { add(it, gbc) }
        }

        val scrollPane = JScrollPane(listPanel).apply {
            val itemHeight = 22
            val maxVisible = 15
            preferredSize = Dimension(360, minOf(allProjects.size * itemHeight + 4, maxVisible * itemHeight))
            border = null
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            add(headerPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(contentPanel, scrollPane)
            .setMovable(false)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()
            .showUnderneathOf(projectButton)
    }

    // ====================================================================
    //  Filter
    // ====================================================================

    private fun applyFilter() {
        val raw = searchField.text.trim()
        val searchText = raw.lowercase()
        val isExcludeMode = searchText.startsWith("^")
        val excludeTerm = if (isExcludeMode) searchText.removePrefix("^") else ""

        rowSorter.rowFilter = object : RowFilter<TableModel, Int>() {
            override fun include(entry: Entry<out TableModel, out Int>): Boolean {
                // "仅已同步"模式：只保留 SYNCED 行
                if (syncedOnlyCheckbox.isSelected) {
                    val modelRow = entry.identifier
                    if (modelRow !in rowSyncStatus.indices || rowSyncStatus[modelRow] != SyncStatus.SYNCED) {
                        return false
                    }
                }

                if (searchText.isEmpty()) return true

                // 目标列：路径(2)、Controller(3)、方法(4)、Parameters(5)、项目(6)
                val targetCols = setOf(2, 3, 4, 5, 6)

                if (isExcludeMode) {
                    // ^xxx：在目标列中任一列包含 xxx 的行应被排除
                    return targetCols.none { col ->
                        entry.getStringValue(col)?.lowercase()?.contains(excludeTerm) == true
                    }
                }

                // 普通搜索：目标列中任一列包含搜索词
                return targetCols.any { col ->
                    entry.getStringValue(col)?.lowercase()?.contains(searchText) == true
                }
            }
        }
        // 过滤自动选中：可见行 √，不可见行 ✗
        syncSelectionWithFilter()
        updateStatsLabel()
    }

    // ====================================================================
    //  Export CSV
    // ====================================================================

    /** 弹出文件保存对话框，将当前表格数据导出为 CSV。 */
    private fun onExportCsv() {
        if (tableModel.rowCount == 0) {
            Messages.showWarningDialog(
                I18n.message(project, "export.noData"),
                I18n.message(project, "export.title")
            )
            return
        }

        val descriptor = FileSaverDescriptor(I18n.message(project, "export.dialogTitle"), "", "csv")
        val saver = FileChooser.chooseFile(descriptor, project, null)

        // 保存对话框路径在 VirtualFile 中，需要转为本地文件系统路径
        val savePath = if (saver != null) {
            saver.path.let {
                if (it.endsWith(".csv", ignoreCase = true)) it else "$it.csv"
            }
        } else return

        try {
            FileWriter(savePath, Charsets.UTF_8).use { writer ->
                // UTF-8 BOM — Excel 正确识别中文
                writer.write("\uFEFF")

                // 表头：路径和接口类拆分为 路径/描述、接口类/Api标签
                val headers = arrayOf(
                    I18n.message(project, "table.column.index"),
                    I18n.message(project, "table.column.path"),
                    I18n.message(project, "export.colDescription"),
                    I18n.message(project, "table.column.controller"),
                    I18n.message(project, "export.colApiTag"),
                    I18n.message(project, "table.column.method"),
                    I18n.message(project, "table.column.parameters"),
                    I18n.message(project, "table.column.project")
                )
                writer.write(headers.joinToString(",") { escapeCsv(it) })
                writer.write("\n")

                // 数据行（所有可见行，按当前排序）
                for (viewRow in 0 until rowSorter.viewRowCount) {
                    val modelRow = rowSorter.convertRowIndexToModel(viewRow)

                    val index = (modelRow + 1).toString()
                    val pathCell = tableModel.getValueAt(modelRow, 2)
                    val controllerCell = tableModel.getValueAt(modelRow, 3)
                    val method = cellToString(tableModel.getValueAt(modelRow, 4))
                    val params = cellToString(tableModel.getValueAt(modelRow, 5))
                    val project = cellToString(tableModel.getValueAt(modelRow, 6))

                    val path = (pathCell as? TagCellValue)?.line1 ?: ""
                    val desc = (pathCell as? TagCellValue)?.line2 ?: ""
                    val controller = (controllerCell as? TagCellValue)?.line1 ?: ""
                    val apiTag = (controllerCell as? TagCellValue)?.line2 ?: ""

                    val line = listOf(index, path, desc, controller, apiTag, method, params, project)
                        .joinToString(",") { escapeCsv(it) }
                    writer.write(line)
                    writer.write("\n")
                }
            }

            statusLabel.text = I18n.message(project, "export.done", File(savePath).name)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                I18n.message(project, "export.failed", e.message),
                I18n.message(project, "export.errorTitle")
            )
        }
    }

    /** 将单元格值转为纯文本字符串 */
    private fun cellToString(value: Any?): String = when (value) {
        is TagCellValue -> value.toString()
        else -> value?.toString() ?: ""
    }

    /** CSV 字段转义：含逗号/引号/换行的字段用双引号包裹 */
    private fun escapeCsv(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    /** 可见行选中，隐藏行取消选中 */
    private fun syncSelectionWithFilter() {
        for (modelRow in 0 until tableModel.dataVector.size) {
            val viewRow = rowSorter.convertRowIndexToView(modelRow)
            tableModel.setValueAt(viewRow >= 0, modelRow, 1)
        }
    }

    // ====================================================================
    //  Column auto-size & width persistence
    // ====================================================================

    /** 列 2-5 按内容自适应宽度。用户已保存的宽度不覆盖。 */
    private fun autoSizeColumns() {
        val saved = ProjectAppSettings.getInstance(project).getColumnWidthMap()
        val metrics = table.getFontMetrics(table.font)

        for (colIdx in 2..6) {
            // 用户已手动调过宽度的列不覆盖
            if (colIdx in saved) continue
            // 已隐藏（已从 columnModel 中移除）的列跳过
            val col = findVisibleColumn(colIdx) ?: continue
            val headerText = col.headerValue?.toString() ?: ""
            var maxWidth = metrics.stringWidth(headerText) + 20

            for (row in 0 until tableModel.dataVector.size) {
                val value = tableModel.getValueAt(row, colIdx)?.toString() ?: ""
                val w = metrics.stringWidth(value) + 24
                if (w > maxWidth) maxWidth = w
            }

            col.minWidth = 50
            col.preferredWidth = (maxWidth + 20).coerceIn(60, 600)
            col.maxWidth = Int.MAX_VALUE
        }
    }

    /** 保存列 2-5 的当前宽度到 [AppSettings]，已移除的列自动跳过。 */
    private fun saveColumnWidths() {
        val widths = mutableMapOf<Int, Int>()
        for (colIdx in 2..6) {
            val col = findVisibleColumn(colIdx) ?: continue
            try { widths[colIdx] = col.width } catch (_: Exception) {}
        }
        ProjectAppSettings.getInstance(project).setColumnWidths(widths)
    }

    // ====================================================================
    //  Column visibility — 物理移除/添加列
    // ====================================================================

    /**
     * 在 columnModel 中查找 modelIndex 匹配的列。
     * 仅遍历当前可见（未移除）的列。
     */
    private fun findVisibleColumn(modelIndex: Int): javax.swing.table.TableColumn? {
        val colModel = table.getColumnModel()
        for (i in 0 until colModel.columnCount) {
            val col = colModel.getColumn(i)
            if (col.modelIndex == modelIndex) return col
        }
        return null
    }

    /**
     * 将被移除的列重新添加到 columnModel 的正确位置。
     * 插入到第一个 modelIndex 大于 [colIdx] 的列之前，保持原始顺序。
     */
    private fun restoreRemovedColumn(
        col: javax.swing.table.TableColumn,
        colIdx: Int,
        w: ColumnWidth,
        saved: Map<Int, Int>
    ) {
        val colModel = table.getColumnModel()
        col.minWidth = w.min
        val savedWidth = saved[colIdx]
        col.preferredWidth = if (savedWidth != null && savedWidth > w.min) savedWidth else w.pref
        col.maxWidth = w.max

        // 找到插入位置：第一个 modelIndex > colIdx 的 view 索引
        var insertIdx = colModel.columnCount
        for (i in 0 until colModel.columnCount) {
            if (colModel.getColumn(i).modelIndex > colIdx) {
                insertIdx = i
                break
            }
        }

        colModel.addColumn(col)
        // 若插入位置不在末尾，移过去
        if (insertIdx < colModel.columnCount - 1) {
            colModel.moveColumn(colModel.columnCount - 1, insertIdx)
        }
    }

    /** 从 [AppSettings] 恢复列可见性及持久化宽度 */
    private fun applyColumnVisibility() {
        val hidden = ProjectAppSettings.getInstance(project).getHiddenColumnSet()
        val saved = ProjectAppSettings.getInstance(project).getColumnWidthMap()
        for ((colIdx, columnDef) in columnDefaults) {
            val (_, w) = columnDef
            if (colIdx in hidden) {
                // 隐藏：若列当前在 columnModel 中，移除
                val existing = findVisibleColumn(colIdx)
                if (existing != null) {
                    removedColumns[colIdx] = existing
                    table.getColumnModel().removeColumn(existing)
                }
            } else {
                // 显示：若列当前被移除，加回
                val removed = removedColumns.remove(colIdx)
                if (removed != null) {
                    restoreRemovedColumn(removed, colIdx, w, saved)
                } else {
                    // 已在模型中的列：应用持久化的宽度
                    val col = findVisibleColumn(colIdx) ?: continue
                    val savedWidth = saved[colIdx]
                    if (savedWidth != null && savedWidth > w.min) {
                        col.preferredWidth = savedWidth
                    }
                    col.minWidth = w.min
                    col.maxWidth = w.max
                }
            }
        }
    }

    /** 切换单列可见性并持久化 */
    private fun toggleColumn(columnIdx: Int, visible: Boolean) {
        val projectSettings = ProjectAppSettings.getInstance(project)
        val hidden = projectSettings.getHiddenColumnSet().toMutableSet()

        if (visible) {
            hidden.remove(columnIdx)
            val removed = removedColumns.remove(columnIdx)
            if (removed != null) {
                val (_, w) = columnDefaults[columnIdx] ?: return
                val saved = projectSettings.getColumnWidthMap()
                restoreRemovedColumn(removed, columnIdx, w, saved)
            }
        } else {
            hidden.add(columnIdx)
            val col = findVisibleColumn(columnIdx)
            if (col != null) {
                removedColumns[columnIdx] = col
                table.getColumnModel().removeColumn(col)
            }
        }

        projectSettings.setHiddenColumns(hidden)
        table.revalidate()
    }

    private fun showColumnPopup() {
        val popup = JPopupMenu()
        val hidden = ProjectAppSettings.getInstance(project).getHiddenColumnSet()

        for ((colIdx, pair) in columnDefaults.entries.sortedBy { it.key }) {
            val colName = I18n.message(project, pair.first)
            val isVisible = colIdx !in hidden
            val cb = JCheckBox(colName, isVisible)
            cb.isOpaque = false
            cb.addActionListener { toggleColumn(colIdx, cb.isSelected) }
            popup.add(cb)
        }

        popup.show(columnsButton, 0, columnsButton.height)
    }

    // ====================================================================
    //  Actions
    // ====================================================================

    private fun onRefresh() {
        if (selectedProjects.isEmpty()) {
            statusLabel.text = I18n.message(project, "status.noProjectsSelected")
            Messages.showWarningDialog(
                project,
                I18n.message(project, "dialog.noProjectsSelected.message"),
                I18n.message(project, "dialog.noProjectsSelected.title")
            )
            return
        }
        statusLabel.text = I18n.message(project, "status.scanning")
        timeLabel.text = I18n.message(project, "ui.elapsedPlaceholder")
        val startTime = System.currentTimeMillis()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, I18n.message(project, "progress.scanning.title"), false
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = I18n.message(project, "progress.indicator.scanning")

                // 1. Controller 扫描
                var t = System.currentTimeMillis()
                val (groups, error) = scanControllersSafe()
                val scanCost = System.currentTimeMillis() - t
                LogUtil.info(log, project) { "[耗时] Controller 扫描: ${scanCost}ms" }

                // 更新耗时（不受错误影响，始终显示）
                val elapsed = System.currentTimeMillis() - startTime
                val elapsedText = I18n.message(project, "ui.elapsed", elapsed)
                SwingUtilities.invokeLater {
                    timeLabel.text = elapsedText
                    timeLabel.revalidate()
                    timeLabel.repaint()
                    bottomPanel.revalidate()
                    bottomPanel.repaint()
                }

                // 扫描失败时直接返回
                if (error != null) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = I18n.message(project, "status.scanFailed", error.message ?: "")
                        Messages.showErrorDialog(
                            project,
                            I18n.message(project, "dialog.scanError.message", error.message ?: ""),
                            I18n.message(project, "dialog.scanError.title")
                        )
                    }
                    return
                }

                // 2. 统一更新 UI（自动刷新场景：同步状态由 refreshTable 按持久化本地 hash 判断，保持纯本地，不查服务端）
                SwingUtilities.invokeLater {
                    scannedGroups = groups ?: emptyList()
                    // 刷新后重新应用过滤（保留用户输入的过滤串）
                    t = System.currentTimeMillis()
                    refreshTable()
                    val tableCost = System.currentTimeMillis() - t
                    LogUtil.info(log, project) { "[耗时] 刷新表格: ${tableCost}ms" }
                    updateStatsLabel()
                    cacheCurrentScanData() // 缓存扫描结果
                    updateStatusAfterScan()
                }
            }
        })
    }


    private fun onSyncSelected() {
        val selectedPairs = getSelectedEndpointsWithGroups()
        if (selectedPairs.isEmpty()) {
            Messages.showInfoMessage(
                project,
                I18n.message(project, "dialog.noSelection.message"),
                I18n.message(project, "dialog.noSelection.title")
            )
            return
        }

        val totalSelected = selectedPairs.size
        val confirmResult = Messages.showYesNoDialog(
            project,
            I18n.message(project, "dialog.confirmSync.message", totalSelected),
            I18n.message(project, "dialog.confirmSync.title"),
            Messages.getQuestionIcon()
        )
        if (confirmResult != Messages.YES) return

        val effective = getEffectiveSettings(project)
        val projectSettings = ProjectAppSettings.getInstance(project)
        if (effective.serverUrl.isBlank() || effective.apiToken.isBlank()) {
            Messages.showWarningDialog(
                project,
                I18n.message(project, "dialog.notConfigured.message"),
                "MyHope Sync"
            )
            return
        }

        statusLabel.text = I18n.message(project, "status.syncing", totalSelected)
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, I18n.message(project, "progress.syncing.title"), false
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val client = MyHopeClient(
                    serverUrl = effective.serverUrl,
                    apiToken = effective.apiToken,
                    instanceId = effective.instanceId,
                    myhopeUserId = effective.myhopeUserId,
                    myhopeUserName = effective.myhopeUserName
                )
                try {
                // ── 1. 确保绑定（access_instance_id / user_app_id）──
                var acc = effective.accessInstanceId
                var app = effective.userAppId
                if (acc.isBlank() || app.isBlank()) {
                    val ti = client.tokenInfo()
                    if (ti.isSuccess) {
                        // HTTP 200 即绑定有效（服务端已校验 token+instance）；勿依赖本地 TokenInfo.valid（恒 false）
                        val info = ti.getOrThrow()
                        info.accessInstanceId?.let { acc = it }
                        info.userAppId?.let { app = it }
                    }
                    if (acc.isBlank() || app.isBlank()) {
                        val bind = client.bindMyHope()
                        if (bind.isSuccess) {
                            val b = bind.getOrThrow()
                            if (b.ok) {
                                b.accessInstanceId?.let { acc = it }
                                b.userAppId?.let { app = it }
                            }
                        }
                    }
                    if (acc.isNotBlank() && app.isNotBlank()) {
                        // 绑定写回分层：项目覆盖 serverUrl/token → 项目级，否则全局
                        writeBackBinding(project, acc, app)
                    }
                    if (acc.isBlank() || app.isBlank()) {
                        SwingUtilities.invokeLater {
                            Messages.showErrorDialog(
                                project, I18n.message(project, "dialog.targetPicker.loadFailed", "绑定失败"),
                                I18n.message(project, "dialog.targetPicker.title")
                            )
                        }
                        return
                    }
                }

                // ── 2. 后台线程加载工作空间 / 分支列表 ──
                val wsResult = client.listWorkspaces(acc, app)
                val brResult = client.listBranches(acc, app)
                val workspaces = wsResult.getOrNull() ?: emptyList()
                val branches = brResult.getOrNull() ?: emptyList()
                val wsErr = wsResult.exceptionOrNull()?.message
                val brErr = brResult.exceptionOrNull()?.message
                val listError = listOfNotNull(wsErr, brErr).joinToString(" / ").takeIf { it.isNotBlank() }

                // ── 3. 回 EDT 弹目标选择对话框 ──
                var ok = false
                var selTargetId: String? = null
                // 解析要回显的 workspace 名 / branch 名（与 SyncService.syncToMyHope 同源）
                val wsName = workspaces.firstOrNull { it.id == projectSettings.workspaceId }?.name
                    ?: workspaces.firstOrNull { it.isDefault }?.name
                    ?: workspaces.firstOrNull()?.name
                val brName = projectSettings.branchName.ifBlank { project.name }
                SwingUtilities.invokeAndWait {
                    val dialog = TargetPickerDialog(
                        project, effective.serverUrl, effective.apiToken, acc, app,
                        wsName, brName, listError
                    )
                    if (dialog.showAndGet()) {
                        ok = true
                        selTargetId = dialog.selectedCollectionId
                    }
                }
                if (!ok) return // 用户取消 → 中止同步

                // ── 5. 同步 ──
                val parser = SpringControllerParser(project)
                val service = SyncService(project, client, parser, indicator)

                val filteredGroups = buildFilteredGroups()
                // 开始推送 — 记录推送耗时
                SwingUtilities.invokeLater {
                    timeLabel.text = I18n.message(project, "ui.elapsedPlaceholder")
                }
                val pushStartTime = System.currentTimeMillis()
                val syncResult = service.syncToMyHope(filteredGroups, selTargetId)
                val pushElapsed = System.currentTimeMillis() - pushStartTime
                val pushElapsedText = I18n.message(project, "ui.elapsed", pushElapsed)

                SwingUtilities.invokeLater {
                    timeLabel.text = pushElapsedText
                    timeLabel.revalidate()
                    timeLabel.repaint()
                    bottomPanel.revalidate()
                    bottomPanel.repaint()

                    showSyncResult(syncResult)
                    updateStatusAfterSync(syncResult)

                    // ── 同步后记录 hash（MVP：本地与服务端 hash 均记为本地 hash，保证本地未改 → SYNCED）──
                    val syncMap = projectSettings.getSyncStatusMap().toMutableMap()
                    for (group in filteredGroups) {
                        for (endpoint in group.endpoints) {
                            val key = computeEndpointKey(endpoint, group)
                            val localHash = computeEndpointHash(endpoint)
                            syncMap[key] = MyHopeDataConverter.buildSyncValue(localHash, 0)
                        }
                    }
                    projectSettings.setSyncStatusMap(syncMap)

                    val syncedKeys = mutableSetOf<String>()
                    for (group in filteredGroups) {
                        for (endpoint in group.endpoints) {
                            syncedKeys.add(computeEndpointKey(endpoint, group))
                        }
                    }
                    for (i in rowEndpoints.indices) {
                        val key = computeEndpointKey(rowEndpoints[i], rowGroups[i])
                        if (key in syncedKeys) {
                            rowSyncStatus[i] = SyncStatus.SYNCED
                        }
                    }

                    applyFilter()
                }
                } finally {
                    client.close()
                }
            }
        })
    }

    // ====================================================================
    //  Sync status check
    // ====================================================================

    /**
     * "检查同步状态"按钮：对比列表中已勾选的端点与服务端请求，更新其同步状态。
     *
     * 不复扫 Controller、不刷新表格，只查已勾选项。
     * 仅从服务端获取数据做对比，不修改服务端数据。
     */
    private fun onCheckSyncStatus() {
        val selectedPairs = getSelectedEndpointsWithGroups()
        if (selectedPairs.isEmpty()) {
            Messages.showInfoMessage(
                project,
                I18n.message(project, "dialog.noSelection.message"),
                I18n.message(project, "dialog.checkSync.title")
            )
            return
        }

        val effective = getEffectiveSettings(project)
        if (effective.serverUrl.isBlank() || effective.apiToken.isBlank()) {
            Messages.showWarningDialog(
                project,
                I18n.message(project, "dialog.notConfigured.message"),
                "MyHope Sync"
            )
            return
        }

        val filteredGroups = buildFilteredGroups()

        statusLabel.text = I18n.message(project, "status.checking")
        timeLabel.text = I18n.message(project, "ui.elapsedPlaceholder")
        val startTime = System.currentTimeMillis()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, I18n.message(project, "progress.checking.title"), false
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = I18n.message(project, "progress.indicator.checking")

                try {
                    // 阶段1：查询服务端数据（performServerStatusCheck 内部拉取 getSnapshot 按 method+endpoint 匹配；
                    // 失败直接抛异常，由外层 catch 报错，不静默）
                    SwingUtilities.invokeLater {
                        statusLabel.text = I18n.message(project, "status.checkingFetch")
                    }
                    val statuses = performServerStatusCheck(filteredGroups, indicator)

                    // 阶段2：对比完成，更新状态
                    val elapsed = System.currentTimeMillis() - startTime
                    val elapsedText = I18n.message(project, "ui.elapsed", elapsed)
                    SwingUtilities.invokeLater {
                        timeLabel.text = elapsedText
                        timeLabel.revalidate()
                        timeLabel.repaint()
                        bottomPanel.revalidate()
                        bottomPanel.repaint()

                        // 构建 endpointKey → status 映射
                        val keyToStatus = mutableMapOf<String, SyncStatus>()
                        var idx = 0
                        for (group in filteredGroups) {
                            for (endpoint in group.endpoints) {
                                keyToStatus[computeEndpointKey(endpoint, group)] = statuses[idx++]
                            }
                        }
                        // 只更新已勾选行的状态
                        for (i in rowEndpoints.indices) {
                            val key = computeEndpointKey(rowEndpoints[i], rowGroups[i])
                            val rowStatus = keyToStatus[key]
                            if (rowStatus != null) {
                                rowSyncStatus[i] = rowStatus
                            }
                        }
                        applyFilter()
                        updateStatsLabel()
                        statusLabel.text = I18n.message(project, "status.checkDone")
                    }
                } catch (e: Exception) {
                    log.warn("检查同步状态异常: ${e.message}", e)
                    SwingUtilities.invokeLater {
                        val elapsed = System.currentTimeMillis() - startTime
                        timeLabel.text = I18n.message(project, "ui.elapsed", elapsed)
                        timeLabel.revalidate()
                        bottomPanel.revalidate()
                        bottomPanel.repaint()
                        statusLabel.text = I18n.message(project, "status.checkFailed")
                        Messages.showErrorDialog(
                            project,
                            I18n.message(project, "check.exception", e.message),
                            I18n.message(project, "dialog.checkSync.title")
                        )
                    }
                }
            }
        })
    }

    // ====================================================================
    //  Sync status check helper
    // ====================================================================

    /**
     * 在后台线程中执行同步状态检查（服务端查询版，仅手动「检查同步状态」调用）。
     *
     * 拉取服务端快照（getSnapshot），按 `method + endpoint(fullPath)` 在目标目录树
     * （[ProjectAppSettings.targetCollectionId]，空=根级）内匹配服务端 Request item：
     * 命中 → SYNCED；未命中 → UNSYNCED。**只判存在性，不比内容**。
     *
     * 失败直接抛异常（由调用方捕获并弹窗报错），不静默、不兜底、不重试：
     * - getSnapshot 失败 → Result.getOrThrow() 抛出；
     * - 绑定信息缺失（access_instance_id / user_app_id 为空）→ IllegalStateException。
     *
     * @param groups 当前分组数据（与返回状态列表按「group → endpoint」展开顺序一致）
     * @param indicator 进度指示器（可选），用于更新提示文本
     * @return 与 [groups] 展开后端点顺序一致的 [SyncStatus] 列表
     */
    private fun performServerStatusCheck(
        groups: List<ControllerGroup>,
        indicator: ProgressIndicator? = null
    ): List<SyncStatus> {
        val effective = getEffectiveSettings(project)
        val projectSettings = ProjectAppSettings.getInstance(project)

        val statusSize = groups.sumOf { it.endpoints.size }
        // 未同步过（无分支/工作空间）→ 全部 UNSYNCED
        if (projectSettings.branchId.isBlank() || projectSettings.workspaceId.isBlank()) {
            LogUtil.info(log, project) { "branchId/workspaceId 未配置，全部标记为 UNSYNCED" }
            return MutableList(statusSize) { SyncStatus.UNSYNCED }
        }
        // 服务端未配置 → 无法查询，全部标记为 UNSYNCED（调用方已在前置校验拦截，此处兜底返回）
        if (effective.serverUrl.isBlank() || effective.apiToken.isBlank()) {
            LogUtil.info(log, project) { "服务端未配置，全部标记为 UNSYNCED" }
            return MutableList(statusSize) { SyncStatus.UNSYNCED }
        }
        val acc = effective.accessInstanceId
        val app = effective.userAppId
        if (acc.isBlank() || app.isBlank()) {
            throw IllegalStateException("未完成 myhope 实例绑定，无法检查服务端同步状态")
        }

        // 目标范围与同步保持一致：targetCollectionId 为空 → 根级（null）
        val targetParentId = projectSettings.targetCollectionId.takeIf { it.isNotBlank() }

        val client = MyHopeClient(
            serverUrl = effective.serverUrl,
            apiToken = effective.apiToken,
            instanceId = effective.instanceId,
            myhopeUserId = effective.myhopeUserId,
            myhopeUserName = effective.myhopeUserName
        )
        try {
            LogUtil.info(log, project) { "=== 检查同步状态（服务端查询版）: branch=${projectSettings.branchId} ws=${projectSettings.workspaceId} target=$targetParentId ===" }
            val serverResult = client.getSnapshot(acc, app, projectSettings.workspaceId, projectSettings.branchId)
            // 失败直接抛异常 → 调用方 catch 弹窗报错（不静默）
            val serverItems = serverResult.getOrThrow()

            // 服务端目标目录树内 request 匹配索引：parent 集合 title → (method:endpoint → item)。
            // 与 buildOps 同一口径（request 只在同一 parent 集合内匹配，不同集合不互相抢占）。
            val serverRequestIndex = MyHopeDataConverter().buildServerRequestIndexByParentTitle(serverItems, targetParentId)
            val converter = MyHopeDataConverter()

            val flat = groups.flatMap { group -> group.endpoints.map { group to it } }
            val total = flat.size
            val statuses = MutableList(total) { SyncStatus.UNSYNCED }
            for (i in flat.indices) {
                val group = flat[i].first
                val endpoint = flat[i].second

                val progressText = I18n.message(project, "progress.checking.item",
                    group.controllerClassName, endpoint.path, i + 1, total)
                indicator?.text = progressText
                // 定期更新面板底部状态（每 5 条更新一次，避免 flooding EDT）
                if (i % 5 == 0 || i == total - 1) {
                    SwingUtilities.invokeLater { statusLabel.text = progressText }
                }

                // 与 toSnapshotItems 相同的 controller 集合 title 规则（apiTag ?: classLevelPath ?: controllerClassName）
                val collectionTitle = converter.sanitizeTitle(
                    when {
                        !group.apiTag.isNullOrBlank() -> group.apiTag
                        !group.classLevelPath.isNullOrBlank() -> group.classLevelPath
                        else -> group.controllerClassName
                    }
                )
                // 服务端同 parent 集合内存在同 method+endpoint（内容不另比）→ SYNCED；否则 UNSYNCED
                val matchKey = "${endpoint.httpMethod.name}:${endpoint.fullPath}"
                statuses[i] = if (serverRequestIndex[collectionTitle]?.containsKey(matchKey) == true) {
                    SyncStatus.SYNCED
                } else {
                    SyncStatus.UNSYNCED
                }
            }
            LogUtil.info(log, project) { "=== 检查同步状态完成（服务端查询版）: ${statuses.count { it == SyncStatus.SYNCED }}/$total 命中 ===" }
            return statuses
        } finally {
            client.close()
        }
    }

    // ====================================================================
    //  Scanning helpers
    // ====================================================================

    /**
     * 在 [ReadAction] 内执行 Controller 扫描，按 [selectedProjects] 过滤模块。
     *
     * [selectedProjects] 为空时不扫描任何模块（跳过）。
     */
    private fun scanControllersSafe(): Pair<List<ControllerGroup>?, Throwable?> {
        if (selectedProjects.isEmpty()) {
            return Pair(emptyList(), null)
        }
        return try {
            var groups: List<ControllerGroup> = emptyList()
            ApplicationManager.getApplication().runReadAction {
                groups = SpringControllerParser(project).parseAllControllers(selectedProjects)
            }
            Pair(groups, null)
        } catch (e: Exception) {
            log.warn("Controller 扫描异常: ${e.message}", e)
            Pair(null, e)
        }
    }

    // ====================================================================
    //  Build filtered groups from checked rows
    // ====================================================================

    /**
     * 收集表格中所有选中（复选框 √）的端点及其所属 [ControllerGroup]。
     */
    private fun getSelectedEndpointsWithGroups(): List<Pair<ControllerGroup, SpringEndpoint>> {
        val selected = mutableListOf<Pair<ControllerGroup, SpringEndpoint>>()
        for (viewRow in 0 until viewRowCount()) {
            val modelRow = rowSorter.convertRowIndexToModel(viewRow)
            val checked = tableModel.getValueAt(modelRow, 1) as? Boolean ?: false
            if (checked && modelRow < rowEndpoints.size && modelRow < rowGroups.size) {
                selected.add(rowGroups[modelRow] to rowEndpoints[modelRow])
            }
        }
        return selected
    }

    /**
     * 将选中的端点重新聚合成 [ControllerGroup] 列表供同步。
     * 每个组只包含被选中的端点。
     */
    private fun buildFilteredGroups(): List<ControllerGroup> {
        val selected = getSelectedEndpointsWithGroups()
        // 按 controllerQualifiedName@moduleName 分组
        val groupMap = linkedMapOf<String, Pair<ControllerGroup, MutableList<SpringEndpoint>>>()
        for ((group, endpoint) in selected) {
            val key = "${group.controllerQualifiedName}@${group.moduleName}"
            val entry = groupMap.getOrPut(key) { group to mutableListOf() }
            entry.second.add(endpoint)
        }
        return groupMap.values.map { (group, endpoints) ->
            group.copy(endpoints = endpoints)
        }
    }

    // ====================================================================
    //  Table display
    // ====================================================================

    private fun refreshTable() {
        // 临时分离 frozen 表的 rowSorter，避免添加行时与主表重复通知导致崩溃
        frozenTable.rowSorter = null

        tableModel.dataVector.clear()
        tableModel.fireTableDataChanged()
        rowEndpoints.clear()
        rowGroups.clear()
        rowSyncStatus.clear()

        val persistedMap = ProjectAppSettings.getInstance(project).getSyncStatusMap()

        var index = 0
        for (group in scannedGroups) {
            for (endpoint in group.endpoints) {
                index++
                rowEndpoints.add(endpoint)
                rowGroups.add(group)

                // 从持久化数据做启发式推断同步状态
                val key = computeEndpointKey(endpoint, group)
                val currentHash = computeEndpointHash(endpoint)
                val storedValue = persistedMap[key]
                val storedLocalHash = if (storedValue != null) MyHopeDataConverter.parseLocalHash(storedValue) else null
                val status = when {
                    storedValue == null -> SyncStatus.UNSYNCED
                    storedLocalHash == currentHash -> SyncStatus.SYNCED
                    else -> SyncStatus.MODIFIED
                }
                rowSyncStatus.add(status)

                // ── 多行单元格：标题并入路径列，接口类列追加 @Api value ──
                val desc = endpoint.description?.takeIf { it.isNotBlank() }
                val apiTag = group.apiTag?.takeIf { it.isNotBlank() }
                val pathCell = TagCellValue(endpoint.fullPath, desc)
                val controllerCell = TagCellValue(endpoint.controllerClassName, apiTag)
                tableModel.addRow(
                    arrayOf<Any>(
                        index,
                        false,
                        pathCell,
                        controllerCell,
                        endpoint.httpMethod.name,
                        formatParameters(endpoint.parameters, endpoint.consumes),
                        group.moduleName
                    )
                )
            }
        }

        // 恢复 frozen 表的 rowSorter
        frozenTable.rowSorter = this@MyHopeSyncPanel.rowSorter

        // 行高适配两行内容（frozenTable 已覆盖 getRowHeight 跟随主表）
        table.rowHeight = table.getFontMetrics(table.font).height * 2 + 16

        updateProjectButton()
        applyFilter()
        autoSizeColumns()
        applyColumnVisibility() // 确保隐藏列不因 autoSizeColumns 而重新显示
        updateStatsLabel()
    }

    // ====================================================================
    //  Scan/project cache persistence
    // ====================================================================

    /** 将当前的 [scannedGroups] 持久化缓存到设置 */
    private fun cacheCurrentScanData() {
        if (scannedGroups.isEmpty()) return
        ProjectAppSettings.getInstance(project).setCachedScanGroups(scannedGroups)
    }

    /** 从持久化缓存恢复上次的扫描数据 */
    private fun restoreCachedScanData() {
        val projectSettings = ProjectAppSettings.getInstance(project)
        val cached = projectSettings.getCachedScanGroups()
        if (cached.isNullOrEmpty()) return
        scannedGroups = cached
        refreshTable()
        statusLabel.text = I18n.message(project, "status.cachedData")
    }

    /** 将当前 [selectedProjects] 持久化缓存到设置 */
    private fun saveSelectedProjectsToCache() {
        ProjectAppSettings.getInstance(project).setSelectedProjects(selectedProjects)
    }

    private fun formatParameters(parameters: List<EndpointParameter>, consumes: List<String> = emptyList()): String {
        if (parameters.isEmpty()) return ""

        // 预先计算 consumes 关键词，用于判断 QUERY 参数是真正的 query 还是 form-data/form
        val consumesLower = consumes.joinToString(" ").lowercase()
        val isFormData = "form-data" in consumesLower || "multipart" in consumesLower
        val isFormUrlEncoded = "urlencoded" in consumesLower || "x-www-form-urlencoded" in consumesLower

        val parts = mutableListOf<String>()
        val grouped = parameters.groupBy { it.source }

        for ((source, params) in grouped.entries) {
            when (source) {
                ParamSource.QUERY -> {
                    val items = mutableListOf<String>()
                    for (param in params) {
                        if (param.objectFields.isNotEmpty()) {
                            param.objectFields.forEach { field ->
                                items.add("$field=")
                            }
                        } else {
                            val value = param.defaultValue?.takeIf { it.isNotBlank() } ?: ""
                            items.add("${param.name}=$value")
                        }
                    }
                    if (items.isEmpty()) continue
                    val prefix = when {
                        isFormData -> "form-data"
                        isFormUrlEncoded -> "form"
                        else -> "query"
                    }
                    parts.add("$prefix: ${items.joinToString("&")}")
                }
                ParamSource.BODY -> {
                    // 根据 consumes 推断 body 格式前缀
                    val prefix = resolveBodyPrefix(consumes)
                    for (param in params) {
                        if (param.bodyJsonSkeleton != null) {
                            // 使用递归解析生成的 JSON 骨架展示
                            parts.add("$prefix: ${param.bodyJsonSkeleton}")
                        } else {
                            // 无法解析类型时回退为旧格式
                            parts.add("$prefix: {\"${param.name}\":...}")
                        }
                    }
                }
                ParamSource.HEADER -> {
                    val display = params.joinToString(", ") { it.name }
                    parts.add("header: $display")
                }
                ParamSource.PATH -> {
                    // PATH 变量已在 Path 列显示，不重复
                }
            }
        }

        if (parts.isEmpty()) return ""

        val result = parts.joinToString(" | ")
        return if (result.length > 200) result.take(197) + "..." else result
    }

    /**
     * 根据 consumes 推断 body 的请求格式前缀。
     * - multipart/form-data → form-data
     * - application/x-www-form-urlencoded → form
     * - 其他/未指定 → raw-json
     */
    private fun resolveBodyPrefix(consumes: List<String>): String {
        val combined = consumes.joinToString(" ").lowercase()
        return when {
            "form-data" in combined || "multipart" in combined -> "form-data"
            "urlencoded" in combined || "x-www-form-urlencoded" in combined -> "form"
            else -> "raw-json"
        }
    }

    // ====================================================================
    //  Status
    // ====================================================================

    private fun updateStatusAfterScan() {
        val endpointCount = scannedGroups.sumOf { it.endpoints.size }
        val projectsStr = if (scannedGroups.isNotEmpty())
            scannedGroups.map { it.moduleName }.distinct().joinToString { m -> m.ifEmpty { "?" } }
        else ""
        statusLabel.text = I18n.message(project, "status.scanResult", endpointCount, scannedGroups.size, projectsStr)
    }

    private fun updateStatusAfterSync(result: SyncResult) {
        val suffix = if (result.errors.isNotEmpty()) I18n.message(project, "result.errorsSuffix", result.errors.size) else ""
        statusLabel.text = I18n.message(project, "status.syncResult", result.success, result.failed, suffix)
    }

    // ====================================================================
    //  Sync result dialog
    // ====================================================================

    private fun showSyncResult(result: SyncResult) {
        val message = buildString {
            appendLine(I18n.message(project, "result.total", result.total))
            appendLine(I18n.message(project, "result.success", result.success))
            appendLine(I18n.message(project, "result.failed", result.failed))
            appendLine(I18n.message(project, "result.collections", result.collectionsCreated))
            appendLine(I18n.message(project, "result.requests", result.requestsCreated))
            if (result.requestsSkipped > 0) appendLine(I18n.message(project, "result.skipped", result.requestsSkipped))
            if (result.requestsUpdated > 0) appendLine(I18n.message(project, "result.updated", result.requestsUpdated))
            if (result.requestsMerged > 0) appendLine(I18n.message(project, "result.merged", result.requestsMerged))
            if (result.prCreated) appendLine(I18n.message(project, "result.prCreated"))
            if (!result.prCreated && result.prMessage != null) {
                appendLine(I18n.message(project, "result.prFailed", result.prMessage))
            }
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine(I18n.message(project, "result.errors"))
                result.errors.forEach { appendLine("  - $it") }
            }
        }
        val title = I18n.message(project, "dialog.syncResult.title")
        if (result.isSuccess) {
            Messages.showInfoMessage(project, message, title)
        } else {
            Messages.showErrorDialog(project, message, title)
        }
    }
}
