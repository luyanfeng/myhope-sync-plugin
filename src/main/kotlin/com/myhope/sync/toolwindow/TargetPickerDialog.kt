package com.myhope.sync.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.myhope.sync.model.ItemType
import com.myhope.sync.model.SnapshotItem
import com.myhope.sync.myhope.MyHopeClient
import com.myhope.sync.settings.ProjectAppSettings
import com.myhope.sync.settings.getEffectiveSettings
import com.myhope.sync.util.I18n
import java.awt.Color
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * 同步目标选择对话框：
 * - 顶部：工作空间 + 分支（只读回显，告诉用户数据推送到哪里；不在此处选择）；
 * - 中部：当前分支下的集合树（JTree），按 [SnapshotItem.parentId] 构建层级，
 *   仅展示 type == [ItemType.COLLECTION] 的条目（请求不显示）；
 * - 底部：已选路径标签 + 「以项目名生成子目录」复选框。
 *
 * 工作空间/分支不在本对话框内选择，由调用方在设置面板预先配置（[ProjectAppSettings.workspaceId]/
 * [ProjectAppSettings.branchId]）；本对话框直接读取后展示快照树。调用方传入的 [workspaceName]/
 * [branchName] 仅作显示，找不到时回退到 id 或「未配置」标记。
 *
 * 线程安全：
 * - UI 组件在 EDT 上构造；
 * - 网络请求在后台线程执行；
 * - Model 更新用 [SwingUtilities.invokeLater] 切回 EDT。
 */
class TargetPickerDialog(
    private val project: Project,
    private val serverUrl: String,
    private val apiToken: String,
    private val accessInstanceId: String,
    private val userAppId: String,
    private val workspaceName: String?,   // 只读回显：当前设置的工作空间名（找不到则 null）
    private val branchName: String?,       // 只读回显：当前设置的分支名
    private val error: String? = null
) : DialogWrapper(project) {

    private val log = Logger.getInstance(TargetPickerDialog::class.java)

    // ============================ UI 组件 ============================

    /** 顶部只读回显：当前设置的工作空间（"Workspace: <name>"）。 */
    private val workspaceLabel = JBLabel()

    /** 顶部只读回显：当前设置的分支（"Branch: <name>"）。 */
    private val branchLabel = JBLabel()

    private val errorLabel = JBLabel().apply { isVisible = false }
    private val statusLabel = JBLabel().apply { isVisible = false }

    /** 不可见树根（userObject=null）。虚拟根（可见）是其唯一子节点，真实集合挂在虚拟根下。 */
    private val rootNode = DefaultMutableTreeNode(null)

    /** 虚拟根节点：标题"根级"，id="" 表示"同步到分支根"（targetParentId=null）。 */
    private val virtualRootNode = DefaultMutableTreeNode(CollectionTreeItem("", I18n.message(project, "dialog.targetPicker.root")))
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = JTree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        toggleClickCount = 1
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        // 集合节点统一显示「集合/文件夹」图标（userObject 为 CollectionTreeItem）。
        // 文本、选中态、颜色等仍走 DefaultTreeCellRenderer 默认。
        cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): java.awt.Component {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode
                if (node?.userObject is CollectionTreeItem) {
                    icon = AllIcons.Nodes.Folder
                }
                return this
            }
        }
    }

    private val pathLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    private val createSubDirCheckBox = JCheckBox().apply {
        border = BorderFactory.createEmptyBorder(0, 8, 4, 8)
    }

    // ============================ 异步加载 ============================

    /** 后台线程池：仅用于快照树加载；daemon 线程，不阻塞 JVM 退出。 */
    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TargetPickerDialog-loader").apply { isDaemon = true }
    }

    /** 懒加载的 MyHopeClient；首次拉快照树时构造，dispose 时关闭。 */
    @Volatile
    private var client: MyHopeClient? = null

    /** 对话框是否已 dispose。后台任务完成切回 EDT 前先检查，避免写已释放组件。 */
    @Volatile
    private var disposed = false

    init {
        title = I18n.message(project, "dialog.targetPicker.title")

        val settings = ProjectAppSettings.getInstance(project)
        val notSet = I18n.message(project, "dialog.targetPicker.workspaceNotSet")

        // ─── 顶部只读回显：workspace / branch ───
        workspaceLabel.text = "${I18n.message(project, "dialog.targetPicker.workspace")} " +
            (workspaceName?.takeIf { it.isNotBlank() }
                ?: settings.workspaceId.takeIf { it.isNotBlank() }
                ?: notSet)
        branchLabel.text = "${I18n.message(project, "dialog.targetPicker.branch")} " +
            (branchName?.takeIf { it.isNotBlank() }
                ?: settings.branchId.takeIf { it.isNotBlank() }
                ?: notSet)

        // ─── 错误状态：error!=null → 显示错误并禁用确定 ───
        if (error != null) {
            errorLabel.text = error
            errorLabel.foreground = JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x66, 0x66))
            errorLabel.isVisible = true
        }

        // ─── 集合树：整行点击展开（参考 Hoppscotch 旧插件 CollectionPickerDialog） ───
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (node.isLeaf) return
                val rowBounds = tree.getRowBounds(tree.getRowForPath(path)) ?: return
                val handleWidth = 20
                if (e.x >= rowBounds.x + handleWidth) {
                    if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                }
            }
        })
        tree.addTreeSelectionListener { updatePathLabelFromSelection() }

        // ─── 路径标签 & 子目录复选框 ───
        pathLabel.text = I18n.message(project, "dialog.targetPicker.pathNone")
        createSubDirCheckBox.text = I18n.message(project, "dialog.targetPicker.subDirectory")
        createSubDirCheckBox.isSelected = settings.createSubDirectory

        // 树结构：虚拟根（可见，id="" 表示根级）作为唯一顶层节点，真实集合挂在它下面
        rootNode.add(virtualRootNode)
        treeModel.reload()
        // 默认选中虚拟根（同步到根级）
        tree.selectionPath = TreePath(arrayOf<Any>(rootNode, virtualRootNode))

        // 必须先建好所有组件再调用 init()（init 会触发 createCenterPanel）
        init()

        if (error != null) {
            isOKActionEnabled = false
        } else {
            // 初次进入时尝试拉一次快照树；未配置 workspace/branch 时显示提示但不禁用 OK
            val wsId = settings.workspaceId
            val brId = settings.branchId
            if (wsId.isBlank() || brId.isBlank()) {
                showTargetNotConfigured()
            } else {
                loadSnapshotTree(wsId, brId)
            }
        }
    }

    /**
     * workspaceId / branchId 为空时：集合树区显示提示，确定仍可点（可同步到根级）。
     */
    private fun showTargetNotConfigured() {
        statusLabel.text = I18n.message(project, "dialog.targetPicker.targetNotConfigured")
        statusLabel.isVisible = true
        tree.isEnabled = false
        updatePathLabelFromSelection()
    }

    // ============================ 面板布局 ============================

    override fun createCenterPanel(): JComponent {
        return panel {
            row { cell(workspaceLabel) }
            row { cell(branchLabel) }
            if (errorLabel.isVisible) {
                row { cell(errorLabel) }
            }
            if (statusLabel.isVisible) {
                row { cell(statusLabel) }
            }
            row {
                cell(JBScrollPane(tree).apply {
                    preferredSize = Dimension(800, 260)
                    minimumSize = Dimension(560, 160)
                }).align(Align.FILL)
            }.resizableRow()
            row { cell(pathLabel) }
            row { cell(createSubDirCheckBox) }
        }
    }

    // ============================ 快照树加载 ============================

    /**
     * 后台拉取当前【工作空间 + 分支】的快照，构造集合层级后切回 EDT 重建 model。
     * 数据源完全来自 [ProjectAppSettings.workspaceId] / [ProjectAppSettings.branchId]；无下拉时不重载
     * （用户需去设置面板修改后再重新打开对话框）。
     */
    private fun loadSnapshotTree(workspaceId: String, branchId: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = I18n.message(project, "dialog.targetPicker.loading")
            statusLabel.isVisible = true
            tree.isEnabled = false
        }

        bgExecutor.submit {
            val c = client ?: run {
                val instanceId = getEffectiveSettings(project).instanceId
                MyHopeClient(serverUrl, apiToken, instanceId).also { client = it }
            }
            val result = runCatching { c.getSnapshot(accessInstanceId, userAppId, workspaceId, branchId) }
                .getOrElse { Result.failure(it) }
            SwingUtilities.invokeLater {
                if (disposed) return@invokeLater
                tree.isEnabled = true
                if (result.isSuccess) {
                    val items = result.getOrNull().orEmpty()
                    rebuildTree(items)
                    statusLabel.isVisible = false
                } else {
                    val msg = result.exceptionOrNull()?.message ?: ""
                    log.warn("Failed to load snapshot tree: $msg")
                    statusLabel.text = I18n.message(project, "dialog.targetPicker.treeError", msg)
                    statusLabel.isVisible = true
                    // 加载失败：保留虚拟根（根级仍可选中），仅清空其下集合
                    virtualRootNode.removeAllChildren()
                    treeModel.reload()
                    tree.selectionPath = TreePath(arrayOf<Any>(rootNode, virtualRootNode))
                }
                updatePathLabelFromSelection()
            }
        }
    }

    /**
     * 从 [SnapshotItem] 列表构建集合树：
     * - 仅保留 type == [ItemType.COLLECTION]；
     * - 用 parentId 关联父子；parentId == null 的为根集合，挂在虚拟根下；
     * - 用 toString() 显示 title（[CollectionTreeItem.toString]）；
     * - 分支无数据时只保留虚拟根节点（仍可选中 → 同步到根级）。
     */
    private fun rebuildTree(items: List<SnapshotItem>) {
        virtualRootNode.removeAllChildren()
        val collections = items.filter { it.type == ItemType.COLLECTION }
        if (collections.isEmpty()) {
            treeModel.reload()
            // 无数据：仅虚拟根可选中（同步到根级）
            tree.selectionPath = TreePath(arrayOf<Any>(rootNode, virtualRootNode))
            updatePathLabelFromSelection()
            return
        }
        val childrenMap = collections.groupBy { it.parentId }
        // parentId 指向不存在的 id 的孤儿集合也视为根集合（groupBy(null) 自然处理）
        fun addRecursive(parentId: String?, parentNode: DefaultMutableTreeNode) {
            val kids = childrenMap[parentId].orEmpty()
            for (kid in kids) {
                val treeNode = DefaultMutableTreeNode(CollectionTreeItem(kid.id, kid.title))
                parentNode.add(treeNode)
                addRecursive(kid.id, treeNode)
            }
        }
        addRecursive(null, virtualRootNode)
        treeModel.reload()
        // 恢复已保存的同步目标选中（存在则选中，避免用户直接 OK 时误清空上次选择；无则回落到虚拟根）
        val savedTargetId = ProjectAppSettings.getInstance(project).targetCollectionId
        val savedNode = findNodeById(virtualRootNode, savedTargetId)
        if (savedNode != null) {
            tree.selectionPath = TreePath(savedNode.path)
        } else {
            tree.selectionPath = TreePath(arrayOf<Any>(rootNode, virtualRootNode))
        }
        // 默认展开第一层（虚拟根的直接子节点），便于用户直接看到顶层目录
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            tree.expandPath(TreePath(arrayOf<Any>(rootNode, virtualRootNode)))
            for (i in 0 until virtualRootNode.childCount) {
                val child = virtualRootNode.getChildAt(i)
                val path = TreePath(arrayOf<Any>(rootNode, virtualRootNode, child))
                tree.expandPath(path)
            }
            // 恢复的已保存目标若在更深层，展开其父节点链使选中可见
            var parent = savedNode?.parent as? DefaultMutableTreeNode
            while (parent != null && parent !== rootNode) {
                tree.expandPath(TreePath(parent.path))
                parent = parent.parent as? DefaultMutableTreeNode
            }
        }
    }

    /** 在 [node] 子树内按 id 查找集合节点；找不到返回 null。 */
    private fun findNodeById(node: DefaultMutableTreeNode, id: String): DefaultMutableTreeNode? {
        if (id.isBlank()) return null
        if ((node.userObject as? CollectionTreeItem)?.id == id) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            findNodeById(child, id)?.let { return it }
        }
        return null
    }

    // ============================ 路径标签 ============================

    /**
     * 从根节点到当前选中节点按 "/" 拼接 title；未选中时显示「无（同步到根级）」。
     */
    private fun updatePathLabelFromSelection() {
        val sel = tree.selectionPath
        if (sel == null) {
            pathLabel.text = I18n.message(project, "dialog.targetPicker.pathNone")
            return
        }
        val node = sel.lastPathComponent as? DefaultMutableTreeNode ?: return
        val item = node.userObject as? CollectionTreeItem ?: return
        pathLabel.text = I18n.message(project, "dialog.targetPicker.pathSelected", buildPathString(node))
    }

    private fun buildPathString(node: DefaultMutableTreeNode): String {
        val segments = mutableListOf<String>()
        var current: DefaultMutableTreeNode? = node
        while (current != null) {
            val obj = current.userObject
            if (obj is CollectionTreeItem) {
                segments.add(obj.title)
            }
            current = current.parent as? DefaultMutableTreeNode
        }
        segments.reverse()
        return segments.joinToString("/")
    }

    // ============================ 持久化 & 生命周期 ============================

    override fun doOKAction() {
        val settings = ProjectAppSettings.getInstance(project)
        settings.createSubDirectory = createSubDirCheckBox.isSelected
        // 目标集合写回：
        // - 选中具体集合 → 写入其 id（后续同步到该集合下）
        // - 选中虚拟根（根级）→ 清空 targetCollectionId（后续同步到分支根，可覆盖历史遗留的旧目标）
        val selNode = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
        val selId = (selNode?.userObject as? CollectionTreeItem)?.id
        if (selId.isNullOrBlank()) {
            settings.targetCollectionId = ""
        } else {
            settings.targetCollectionId = selId
        }
        super.doOKAction()
    }

    override fun dispose() {
        // 先标记 disposed，避免后台任务完成切回 EDT 后写已释放组件
        disposed = true
        try {
            bgExecutor.shutdownNow()
        } catch (_: Exception) {
            // 静默忽略 executor 关闭异常，不影响 dispose 主流程
        }
        // MyHopeClient.close 内部关 HttpClient，可能阻塞等待进行中请求；
        // 移到 pooled 线程上执行，避免拖住 EDT
        val c = client
        client = null
        if (c != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    c.close()
                } catch (e: Exception) {
                    log.warn("Failed to close MyHopeClient: ${e.message}")
                }
            }
        }
        super.dispose()
    }

    // ============================ 返回属性 ============================

    /** 用户选中的集合 id；选中虚拟根（根级）或未选中 → null。 */
    val selectedCollectionId: String?
        get() {
            val sel = tree.selectionPath ?: return null
            val node = sel.lastPathComponent as? DefaultMutableTreeNode ?: return null
            val id = (node.userObject as? CollectionTreeItem)?.id ?: return null
            return id.takeIf { it.isNotBlank() }  // 虚拟根 id="" → null（根级）
        }

    // ============================ 内部数据 ============================

    /**
     * 树节点承载的数据：[id] 为服务端集合 id，[title] 为集合显示名。
     * 根节点 userObject 为 null，表示"同步到根级"。
     */
    private data class CollectionTreeItem(
        val id: String,
        val title: String
    ) {
        override fun toString(): String = title
    }
}
