package com.myhope.sync.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.myhope.sync.model.BranchInfo
import com.myhope.sync.model.LogLevel
import com.myhope.sync.model.WorkspaceInfo
import com.myhope.sync.model.generateEntityId
import com.myhope.sync.myhope.MyHopeClient
import com.myhope.sync.util.I18n
import java.awt.Color
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * 项目级设置面板（Settings → Project Settings → MyHope Sync）。
 *
 * 合并自原 AppSettingsConfigurable + ProjectAppSettingsConfigurable，语义为
 * "全局默认模板 + 项目级覆盖"：
 * - 服务器/API Token/实例ID/语言/日志等账号级字段：本面板展示/编辑**有效配置**（合并后值）。
 *   文本字段非空显示项目覆盖值，为空则占位提示"（使用全局默认：xxx）"；
 *   语言/日志下拉提供"（使用全局默认）"哨兵项表达"未覆盖（跟随全局）"。
 * - 同步目标（工作空间/分支）：仅项目级（无全局默认）。
 * - "验证"按钮用**有效配置**发起 X-API-Token 校验并自动绑定；成功后绑定信息（acc/app/userId）
 *   分层写回：勾选"同步到全局默认"→ 写全局；否则 serverUrl/token 存在项目覆盖 → 写项目级，
 *   否则写全局（保持三元组同一层一致性）。
 */
class ProjectAppSettingsConfigurable(private val project: Project) : Configurable {

    companion object {
        private val LOG = Logger.getInstance(ProjectAppSettingsConfigurable::class.java)

        /** 语言下拉的"未覆盖（跟随全局默认）"哨兵项。 */
        private const val LANGUAGE_USE_GLOBAL = "@useGlobalLang"

        /** 日志级别下拉的"未覆盖（跟随全局默认）"哨兵项。 */
        private const val LOG_LEVEL_USE_GLOBAL = "@useGlobalLogLevel"
    }

    // ---- 账号级（可被项目覆盖，空 = 回落全局默认）----
    private var serverUrlField = JBTextField().apply { columns = 50 }
    private var accessTokenField = JBPasswordField().apply { columns = 50 }
    private var instanceIdField = JBTextField().apply { columns = 50; isEditable = false }
    private var showTokensCheckbox = JBCheckBox(I18n.message(project, "settings.showTokens")).apply { isSelected = false }
    private var syncToGlobalCheckbox = JBCheckBox(I18n.message(project, "settings.syncToGlobal")).apply { isSelected = false }
    private var verifyStatusLabel = JBLabel().apply { isVisible = false }
    private lateinit var verifyButton: JButton
    private var languageCombo = JComboBox<Any>()
    private var logLevelCombo = JComboBox<Any>()

    // ---- 同步目标 ----
    private var workspaceCombo = ComboBox<WorkspaceInfo>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    text = (value as? WorkspaceInfo)?.name ?: ""
                }
            }
        }
    }
    private var branchCombo = ComboBox<BranchInfo>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    text = (value as? BranchInfo)?.name ?: ""
                }
            }
        }
    }
    private lateinit var newWorkspaceButton: JButton
    private lateinit var newBranchButton: JButton

    // ---- auto-publish PR ----
    private var autoPublishPrCheckbox = JBCheckBox(I18n.message(project, "settings.autoPublishPr")).apply { isSelected = false }
    private var prTargetBranchCombo = ComboBox<BranchInfo>().apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    text = (value as? BranchInfo)?.name ?: ""
                }
            }
        }
        isEnabled = false
    }
    // 自动加载生命周期保护：对话框关闭时 Disposer 触发，置位取消标志，避免后续写已释放的组件
    private val uiDisposable = Disposer.newDisposable()
    @Volatile
    private var targetsLoadingCancelled = false

    /** 初始化快照，isModified 对比用（对比当前 UI 与初始展示值）。 */
    private data class InitSnapshot(
        val serverUrl: String,
        val apiToken: String,
        val languageItem: Any?,
        val logLevelItem: Any?
    )

    private lateinit var initSnapshot: InitSnapshot

    init {
        showTokensCheckbox.addActionListener {
            val echoChar = if (showTokensCheckbox.isSelected) '\u0000' else '•'
            accessTokenField.echoChar = echoChar
        }
        // 对话框释放时置位取消标志，使在途的自动加载不再写已释放的组件
        Disposer.register(uiDisposable, Disposable { targetsLoadingCancelled = true })
    }

    override fun getDisplayName(): String = "MyHope Sync"

    override fun createComponent(): JComponent {
        return panel {
            group(I18n.message(project, "settings.group.server")) {
                row(I18n.message(project, "settings.serverUrl")) {
                    cell(serverUrlField)
                        .focused()
                }
                row(I18n.message(project, "settings.apiToken")) {
                    cell(accessTokenField)
                }
                row(I18n.message(project, "settings.instanceId")) {
                    cell(instanceIdField)
                }
                row("") {
                    cell(showTokensCheckbox)
                        .comment(I18n.message(project, "settings.showTokens.comment"))
                }
                row("") {
                    cell(syncToGlobalCheckbox)
                        .comment(I18n.message(project, "settings.syncToGlobal.comment"))
                    button(I18n.message(project, "settings.verify")) {
                        verifyTokens()
                    }.also { verifyButton = it.component }
                    cell(verifyStatusLabel)
                }
            }
            group(I18n.message(project, "settings.group.target")) {
                row(I18n.message(project, "settings.workspace")) {
                    cell(workspaceCombo)
                        .columns(45)
                    button(I18n.message(project, "settings.createWorkspace")) {
                        onCreateWorkspace()
                    }.also { newWorkspaceButton = it.component }
                }
                row(I18n.message(project, "settings.branch")) {
                    cell(branchCombo)
                        .columns(45)
                    button(I18n.message(project, "settings.createBranch")) {
                        onCreateBranch()
                    }.also { newBranchButton = it.component }
                }
                row {
                    comment(I18n.message(project, "settings.group.target.comment"))
                }
            }
            group(I18n.message(project, "settings.group.pr")) {
                row {
                    cell(autoPublishPrCheckbox)
                        .comment(I18n.message(project, "settings.autoPublishPr.comment"))
                }
                row(I18n.message(project, "settings.prTargetBranch")) {
                    cell(prTargetBranchCombo)
                        .columns(45)
                }
                row {
                    comment(I18n.message(project, "settings.group.pr.comment"))
                }
            }
            group(I18n.message(project, "settings.group.language")) {
                row(I18n.message(project, "settings.language")) {
                    cell(languageCombo)
                }
                row {
                    comment(I18n.message(project, "settings.language.comment"))
                }
            }
            group(I18n.message(project, "settings.group.logging")) {
                row(I18n.message(project, "settings.logLevel")) {
                    cell(logLevelCombo)
                }
                row {
                    comment(I18n.message(project, "settings.logging.comment"))
                }
            }
            group(I18n.message(project, "settings.group.note")) {
                row {
                    comment(I18n.message(project, "settings.note.overrideSnapshot"))
                }
            }
        }.also {
            initFieldsFromSettings()
        }
    }

    // ====================================================================
    //  字段初始化 / 快照 / isModified / apply / reset
    // ====================================================================

    /** 按持久化设置（项目覆盖 + 全局默认构成的有效配置）初始化所有字段，并重算快照。 */
    private fun initFieldsFromSettings() {
        val projectSettings = ProjectAppSettings.getInstance(project)
        val effective = getEffectiveSettings(project)

        // 服务器：显示当前生效值（项目覆盖优先，空则展示全局默认）；不改动不写项目层（保持回落语义）
        serverUrlField.text = effective.serverUrl
        accessTokenField.text = effective.apiToken
        instanceIdField.text = effective.instanceId
        syncToGlobalCheckbox.isSelected = false
        showTokensCheckbox.isSelected = false
        accessTokenField.echoChar = if (showTokensCheckbox.isSelected) '\u0000' else '•'

        // 语言："（使用全局默认）" / English / 中文
        languageCombo.model = DefaultComboBoxModel(
            arrayOf<Any>(LANGUAGE_USE_GLOBAL, "English", "中文")
        )
        languageCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    text = when (value) {
                        LANGUAGE_USE_GLOBAL -> I18n.message(project, "settings.useGlobalDefault")
                        "中文" -> "中文"
                        "English" -> "English"
                        else -> ""
                    }
                }
            }
        }
        languageCombo.selectedItem =
            if (projectSettings.language.isBlank()) LANGUAGE_USE_GLOBAL
            else if (projectSettings.language == "zh") "中文" else "English"

        // 日志级别："（使用全局默认）" + LogLevel 枚举
        val logLevelItems: MutableList<Any> = mutableListOf(LOG_LEVEL_USE_GLOBAL)
        logLevelItems.addAll(LogLevel.entries)
        logLevelCombo.model = DefaultComboBoxModel(logLevelItems.toTypedArray())
        logLevelCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    when (value) {
                        LOG_LEVEL_USE_GLOBAL -> text = I18n.message(project, "settings.useGlobalDefault")
                        is LogLevel -> {
                            val level = value
                            text = if (getEffectiveSettings(project).language == "zh") level.labelZh else level.label
                        }
                        else -> text = ""
                    }
                }
            }
        }
        logLevelCombo.selectedItem =
            if (projectSettings.logLevel.isBlank()) LOG_LEVEL_USE_GLOBAL
            else LogLevel.fromId(projectSettings.logLevel)

        initSnapshot = InitSnapshot(
            serverUrl = serverUrlField.text,
            apiToken = String(accessTokenField.password),
            languageItem = languageCombo.selectedItem,
            logLevelItem = logLevelCombo.selectedItem
        )

        // 同步目标：有效配置已绑定（acc/app 非空）→ 后台自动加载启用；否则保持灰色待验证
        val autoLoad = effective.accessInstanceId.isNotBlank() && effective.userAppId.isNotBlank()
        disableTargetCombos()
        if (autoLoad) reloadTargetsAsync()

        // auto-publish PR：开关 + 目标分支（分支列表由 loadBranches 一并填充）
        autoPublishPrCheckbox.isSelected = projectSettings.autoPublishPr
        prTargetBranchCombo.removeAllItems()
        prTargetBranchCombo.isEnabled = autoPublishPrCheckbox.isSelected && autoLoad
        val savedTarget = projectSettings.prTargetBranchId
        if (savedTarget.isNotBlank()) {
            // 目标分支 id 直接存为哨兵项（名称待 loadBranches 后回填/匹配）；简单起见留空由下拉选中
            prTargetBranchCombo.putClientProperty("savedPrTargetId", savedTarget)
        }
        autoPublishPrCheckbox.addActionListener {
            prTargetBranchCombo.isEnabled = autoPublishPrCheckbox.isSelected && autoLoad
            if (autoPublishPrCheckbox.isSelected && prTargetBranchCombo.itemCount == 0 && autoLoad) {
                reloadTargetsAsync()
            }
        }
    }

    override fun isModified(): Boolean {
        val projectSettings = ProjectAppSettings.getInstance(project)
        val snapshot = initSnapshot
        val wsChanged = workspaceCombo.itemCount > 0 &&
            (workspaceCombo.selectedItem as? WorkspaceInfo)?.id != projectSettings.workspaceId
        val brChanged = branchCombo.itemCount > 0 &&
            (branchCombo.selectedItem as? BranchInfo)?.id != projectSettings.branchId
        val prChanged = autoPublishPrCheckbox.isSelected != projectSettings.autoPublishPr ||
            ((prTargetBranchCombo.selectedItem as? BranchInfo)?.id ?: "") != projectSettings.prTargetBranchId
        return serverUrlField.text != snapshot.serverUrl
            || String(accessTokenField.password) != snapshot.apiToken
            || languageCombo.selectedItem != snapshot.languageItem
            || logLevelCombo.selectedItem != snapshot.logLevelItem
            || wsChanged || brChanged || prChanged
    }

    override fun apply() {
        val projectSettings = ProjectAppSettings.getInstance(project)
        // 账号级覆盖写回：字段显示有效值（含全局默认回落）；仅当用户实际修改字段时才写项目级覆盖，
        // 未修改保持空/原值 → 全局默认仍生效（避免把全局默认值误固化到项目层）
        if (serverUrlField.text.trim() != initSnapshot.serverUrl.trim()) {
            projectSettings.serverUrl = serverUrlField.text.trim()
        }
        if (String(accessTokenField.password).trim() != initSnapshot.apiToken.trim()) {
            projectSettings.apiToken = String(accessTokenField.password).trim()
        }
        projectSettings.language = when (languageCombo.selectedItem) {
            LANGUAGE_USE_GLOBAL -> ""
            "中文" -> "zh"
            else -> "en"
        }
        projectSettings.logLevel = (logLevelCombo.selectedItem as? LogLevel)?.name ?: ""
        // 同步目标写回（仅项目级）
        (workspaceCombo.selectedItem as? WorkspaceInfo)?.let { projectSettings.workspaceId = it.id }
        (branchCombo.selectedItem as? BranchInfo)?.let {
            projectSettings.branchId = it.id
            projectSettings.branchName = it.name
        }
        // auto-publish PR 写回
        projectSettings.autoPublishPr = autoPublishPrCheckbox.isSelected
        projectSettings.prTargetBranchId = (prTargetBranchCombo.selectedItem as? BranchInfo)?.id ?: ""
    }

    override fun reset() {
        initFieldsFromSettings()
    }

    // ====================================================================
    //  Token 验证（有效配置发请求；成功 → 分层写回 + 刷新同步目标）
    // ====================================================================

    /** 校验 API Token → 自动绑定 → 回显绑定信息 → 分层写回。 */
    private fun verifyTokens() {
        if (!::verifyButton.isInitialized || !verifyButton.isEnabled) return

        val global = AppSettings.getInstance()
        val projectSettings = ProjectAppSettings.getInstance(project)

        // 有效配置：当前面板输入（非空则覆盖）回落全局默认
        val url = serverUrlField.text.trim().ifBlank { global.serverUrl }
        val at = String(accessTokenField.password).trim().ifBlank { global.apiToken }
        if (url.isBlank()) {
            showVerifyResult(false, I18n.message(project, "settings.verify.enterUrl"))
            return
        }
        if (at.isBlank()) {
            showVerifyResult(false, I18n.message(project, "settings.verify.enterToken"))
            return
        }
        // instanceId 全局单例：项目覆盖时不自动生成新 ID，仅未覆盖时回落全局（必要时生成一次）
        val instanceId = projectSettings.instanceId.ifBlank { global.getOrCreateInstanceId() }
        val userId = projectSettings.myhopeUserId.ifBlank { global.getOrCreateMyHopeUserId() }
        val userName = projectSettings.myhopeUserName.ifBlank { global.myhopeUserName }

        verifyButton.isEnabled = false
        showVerifyResult(null, I18n.message(project, "settings.verify.verifying"))

        val verifyThread = Thread(Runnable {
            val client = MyHopeClient(url, at, instanceId, userId, userName)
            try {
                var bound = false
                var detail: String? = null
                var boundAcc: String? = null
                var boundApp: String? = null
                var boundUserId: String? = null
                var boundUserName: String? = null

                // 1. tokenInfo（已绑定则直接恢复 acc/app，并回填服务端记录的绑定用户标识）
                //    HTTP 200 即代表绑定有效（服务端已校验 token+instance）；勿依赖本地 TokenInfo.valid（恒 false）
                val tokenInfo = client.tokenInfo()
                if (tokenInfo.isSuccess) {
                    val info = tokenInfo.getOrNull()
                    if (info != null) {
                        val sb = StringBuilder(I18n.message(project, "settings.verify.success"))
                        info.accessInstanceId?.let {
                            boundAcc = it
                            sb.append(" | access_instance_id=$it")
                        }
                        info.userAppId?.let {
                            boundApp = it
                            sb.append(" | user_app_id=$it")
                        }
                        info.myhopeBinding?.let { b ->
                            if (!b.userId.isNullOrBlank()) {
                                boundUserId = b.userId
                                if (!b.userName.isNullOrBlank()) boundUserName = b.userName
                            }
                        }
                        detail = sb.toString()
                        bound = true
                    }
                }

                // 2. 未绑定 → bindMyHope（用户标识已自动生成）
                if (!bound) {
                    val bind = client.bindMyHope()
                    if (bind.isSuccess) {
                        val b = bind.getOrNull()
                        if (b != null && b.ok) {
                            boundAcc = b.accessInstanceId
                            boundApp = b.userAppId
                            detail = I18n.message(project, "settings.verify.bound") +
                                " | access_instance_id=${b.accessInstanceId} | user_app_id=${b.userAppId}"
                            bound = true
                        } else {
                            detail = bind.exceptionOrNull()?.message ?: I18n.message(project, "settings.verify.notBound")
                        }
                    } else {
                        detail = bind.exceptionOrNull()?.message ?: I18n.message(project, "settings.verify.notBound")
                    }
                }

                val finalBound = bound
                val finalDetail = detail
                val finalAcc = boundAcc
                val finalApp = boundApp

                SwingUtilities.invokeLater {
                    verifyButton.isEnabled = true
                    if (finalBound) {
                        writeBackVerified(url, at, finalAcc, finalApp, boundUserId, boundUserName)
                        // 绑定成功后刷新同步目标下拉（此前可能因未绑定而禁用）
                        reloadTargetsAsync()
                        showVerifyResult(true, finalDetail ?: I18n.message(project, "settings.verify.success"))
                    } else {
                        showVerifyResult(false, finalDetail ?: I18n.message(project, "settings.verify.notBound"))
                    }
                }
            } catch (e: Throwable) {
                LOG.error("verifyTokens caught Throwable", e)
                SwingUtilities.invokeLater {
                    verifyButton.isEnabled = true
                    showVerifyResult(false, I18n.message(project, "settings.verify.exception", e.message ?: e.javaClass.simpleName))
                }
            } finally {
                client.close()
            }
        }, "MyHope-verify-token")
        verifyThread.isDaemon = true
        verifyThread.start()
    }

    /**
     * 验证成功后分层写回：
     * 勾选"同步到全局默认" → 写全局 [AppSettings]（serverUrl/token/绑定信息）；
     * 否则 serverUrl/token 存在项目级覆盖（面板输入或已持久化）→ 写项目级 [ProjectAppSettings]；
     * 都无 → 写全局。保证 acc/app 与 serverUrl/token 在同一层（三元组一致性）。
     */
    private fun writeBackVerified(
        url: String,
        token: String,
        acc: String?,
        app: String?,
        myhopeUserId: String?,
        myhopeUserName: String?
    ) {
        val global = AppSettings.getInstance()
        val projectSettings = ProjectAppSettings.getInstance(project)
        val syncToGlobal = syncToGlobalCheckbox.isSelected
        // 项目覆盖判定：字段显示有效值（含全局回落），仅当面板输入或持久化值与全局默认不同才算项目级覆盖
        val hasProjectOverride = fieldDiffersFromGlobal(serverUrlField.text, global.serverUrl)
            || fieldDiffersFromGlobal(String(accessTokenField.password), global.apiToken)
            || projectSettings.serverUrl.isNotBlank()
            || projectSettings.apiToken.isNotBlank()

        if (syncToGlobal || !hasProjectOverride) {
            global.serverUrl = url
            global.apiToken = token
            applyBinding(global, acc, app, myhopeUserId, myhopeUserName)
        }
        if (hasProjectOverride && !syncToGlobal) {
            applyBinding(projectSettings, acc, app, myhopeUserId, myhopeUserName)
        }
    }

    private fun applyBinding(target: AppSettings, acc: String?, app: String?, uid: String?, uname: String?) {
        if (!acc.isNullOrBlank()) target.accessInstanceId = acc
        if (!app.isNullOrBlank()) target.userAppId = app
        if (!uid.isNullOrBlank()) target.myhopeUserId = uid
        if (!uname.isNullOrBlank()) target.myhopeUserName = uname
    }

    private fun applyBinding(target: ProjectAppSettings, acc: String?, app: String?, uid: String?, uname: String?) {
        if (!acc.isNullOrBlank()) target.accessInstanceId = acc
        if (!app.isNullOrBlank()) target.userAppId = app
        if (!uid.isNullOrBlank()) target.myhopeUserId = uid
        if (!uname.isNullOrBlank()) target.myhopeUserName = uname
    }

    /** 显示验证结果。valid=null 表示进行中。 */
    private fun showVerifyResult(valid: Boolean?, msg: String) {
        verifyStatusLabel.text = msg
        verifyStatusLabel.isVisible = true
        verifyStatusLabel.foreground = when (valid) {
            null -> null  // default (Verifying...)
            true -> Color(0x00, 0x80, 0x00)    // green
            false -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x66, 0x66))  // red
        }
    }

    /** 面板输入值相对全局默认是否构成项目级覆盖（空输入视为未覆盖）。 */
    private fun fieldDiffersFromGlobal(fieldValue: String, globalValue: String): Boolean =
        fieldValue.trim().isNotBlank() && fieldValue.trim() != globalValue.trim()

    // ====================================================================
    //  工作空间/分支加载
    // ====================================================================

    /** 构造 MyHopeClient（用有效配置：项目覆盖 + 全局默认）。 */
    private fun buildClient(): MyHopeClient {
        val effective = getEffectiveSettings(project)
        return MyHopeClient(
            serverUrl = effective.serverUrl,
            apiToken = effective.apiToken,
            instanceId = effective.instanceId,
            myhopeUserId = effective.myhopeUserId,
            myhopeUserName = effective.myhopeUserName
        )
    }

    /** 后台线程加载工作空间/分支（勿在 EDT 直接调用——内部是同步网络请求）。 */
    private fun reloadTargetsAsync() {
        Thread(Runnable { reloadTargets() }, "MyHope-project-load-targets").apply { isDaemon = true; start() }
    }

    /** 按有效配置的 acc/app 加载工作空间与分支下拉；未绑定则保持禁用。 */
    private fun reloadTargets() {
        val effective = getEffectiveSettings(project)
        val acc = effective.accessInstanceId
        val app = effective.userAppId
        if (acc.isBlank() || app.isBlank()) {
            SwingUtilities.invokeLater {
                if (targetsLoadingCancelled) return@invokeLater
                disableTargetCombos()
            }
            return
        }
        val client = buildClient()
        try {
            loadWorkspaces(client, acc, app)
            loadBranches(client, acc, app)
        } catch (e: Throwable) {
            LOG.error("auto load targets caught Throwable", e)
            SwingUtilities.invokeLater {
                if (targetsLoadingCancelled) return@invokeLater
                disableTargetCombos()
            }
        } finally {
            client.close()
        }
    }

    /** 加载工作空间下拉并启用。 */
    private fun loadWorkspaces(client: MyHopeClient, acc: String, app: String) {
        val projectSettings = ProjectAppSettings.getInstance(project)
        val result = client.listWorkspaces(acc, app)
        val list = result.getOrNull() ?: emptyList()
        val previousId = (workspaceCombo.selectedItem as? WorkspaceInfo)?.id ?: projectSettings.workspaceId
        SwingUtilities.invokeLater {
            if (targetsLoadingCancelled) return@invokeLater
            workspaceCombo.removeAllItems()
            list.forEach { workspaceCombo.addItem(it) }
            val sel = list.firstOrNull { it.id == previousId }
                ?: list.firstOrNull { it.isDefault }
                ?: list.firstOrNull()
            if (sel != null) workspaceCombo.selectedItem = sel
            workspaceCombo.isEnabled = list.isNotEmpty()
            newWorkspaceButton.isEnabled = true
        }
    }

    /** 加载分支下拉并启用。 */
    private fun loadBranches(client: MyHopeClient, acc: String, app: String) {
        val projectSettings = ProjectAppSettings.getInstance(project)
        val result = client.listBranches(acc, app)
        val list = result.getOrNull() ?: emptyList()
        val previousId = (branchCombo.selectedItem as? BranchInfo)?.id ?: projectSettings.branchId
        val savedPrTargetId = (prTargetBranchCombo.getClientProperty("savedPrTargetId") as? String)
            ?: projectSettings.prTargetBranchId
        SwingUtilities.invokeLater {
            if (targetsLoadingCancelled) return@invokeLater
            branchCombo.removeAllItems()
            list.forEach { branchCombo.addItem(it) }
            val sel = list.firstOrNull { it.id == previousId } ?: list.firstOrNull()
            if (sel != null) branchCombo.selectedItem = sel
            branchCombo.isEnabled = list.isNotEmpty()
            newBranchButton.isEnabled = true

            // 同步填充 PR 目标分支下拉（与 branchCombo 同源）
            prTargetBranchCombo.removeAllItems()
            list.forEach { prTargetBranchCombo.addItem(it) }
            val prSel = list.firstOrNull { it.id == savedPrTargetId }
                ?: list.firstOrNull { it.id == projectSettings.branchId }
            if (prSel != null) prTargetBranchCombo.selectedItem = prSel
            prTargetBranchCombo.isEnabled = autoPublishPrCheckbox.isSelected && list.isNotEmpty()
        }
    }

    private fun disableTargetCombos() {
        workspaceCombo.removeAllItems()
        branchCombo.removeAllItems()
        workspaceCombo.isEnabled = false
        branchCombo.isEnabled = false
        newWorkspaceButton.isEnabled = false
        newBranchButton.isEnabled = false
        prTargetBranchCombo.removeAllItems()
        prTargetBranchCombo.isEnabled = false
    }

    // ====================================================================
    //  新建工作空间 / 分支
    // ====================================================================

    private fun onCreateWorkspace() {
        val name = Messages.showInputDialog(
            project, I18n.message(project, "settings.workspaceName"), I18n.message(project, "settings.createWorkspace"), null
        )?.trim() ?: return
        if (name.isBlank()) return
        val effective = getEffectiveSettings(project)
        val acc = effective.accessInstanceId
        val app = effective.userAppId
        if (acc.isBlank() || app.isBlank()) return
        val client = buildClient()
        val userId = effective.myhopeUserId
        newWorkspaceButton.isEnabled = false
        Thread(Runnable {
            try {
                val result = client.createWorkspace(acc, app, generateEntityId("ws", name), name, userId)
                if (result.isSuccess) {
                    // 后台线程刷新下拉（loadWorkspaces 内部再 invokeLater 更新 UI），避免网络请求阻塞 EDT
                    loadWorkspaces(client, acc, app)
                    SwingUtilities.invokeLater {
                        if (targetsLoadingCancelled) { client.close(); return@invokeLater }
                        newWorkspaceButton.isEnabled = true
                        client.close()
                    }
                } else {
                    SwingUtilities.invokeLater {
                        if (targetsLoadingCancelled) { client.close(); return@invokeLater }
                        newWorkspaceButton.isEnabled = true
                        Messages.showErrorDialog(
                            project, result.exceptionOrNull()?.message ?: "创建失败",
                            I18n.message(project, "settings.createWorkspace")
                        )
                        client.close()
                    }
                }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    if (targetsLoadingCancelled) { client.close(); return@invokeLater }
                    newWorkspaceButton.isEnabled = true
                    Messages.showErrorDialog(
                        project, e.message ?: e.javaClass.simpleName,
                        I18n.message(project, "settings.createWorkspace")
                    )
                    client.close()
                }
            }
        }, "MyHope-project-create-ws").apply { isDaemon = true; start() }
    }

    private fun onCreateBranch() {
        val name = Messages.showInputDialog(
            project, I18n.message(project, "settings.branchName"), I18n.message(project, "settings.createBranch"), null
        )?.trim() ?: return
        if (name.isBlank()) return
        val effective = getEffectiveSettings(project)
        val acc = effective.accessInstanceId
        val app = effective.userAppId
        val projectSettings = ProjectAppSettings.getInstance(project)
        val wsId = (workspaceCombo.selectedItem as? WorkspaceInfo)?.id ?: projectSettings.workspaceId
        if (acc.isBlank() || app.isBlank() || wsId.isBlank()) return
        val client = buildClient()
        newBranchButton.isEnabled = false
        Thread(Runnable {
            try {
                val result = client.createBranch(
                    accessInstanceId = acc,
                    userAppId = app,
                    workspaceId = wsId,
                    id = generateEntityId("br", name),
                    name = name,
                    visibility = "personal"
                )
                if (result.isSuccess) {
                    // 后台线程刷新下拉（loadBranches 内部再 invokeLater 更新 UI），避免网络请求阻塞 EDT
                    loadBranches(client, acc, app)
                    SwingUtilities.invokeLater {
                        if (targetsLoadingCancelled) { client.close(); return@invokeLater }
                        newBranchButton.isEnabled = true
                        client.close()
                    }
                } else {
                    SwingUtilities.invokeLater {
                        if (targetsLoadingCancelled) { client.close(); return@invokeLater }
                        newBranchButton.isEnabled = true
                        Messages.showErrorDialog(
                            project, result.exceptionOrNull()?.message ?: "创建失败",
                            I18n.message(project, "settings.createBranch")
                        )
                        client.close()
                    }
                }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    if (targetsLoadingCancelled) { client.close(); return@invokeLater }
                    newBranchButton.isEnabled = true
                    Messages.showErrorDialog(
                        project, e.message ?: e.javaClass.simpleName,
                        I18n.message(project, "settings.createBranch")
                    )
                    client.close()
                }
            }
        }, "MyHope-project-create-br").apply { isDaemon = true; start() }
    }

    /** 对话框释放时取消在途的自动加载，避免后续写已释放的组件。 */
    override fun disposeUIResources() {
        Disposer.dispose(uiDisposable)
    }
}