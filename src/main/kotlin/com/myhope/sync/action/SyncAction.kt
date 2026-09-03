package com.myhope.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.myhope.sync.myhope.MyHopeClient
import com.myhope.sync.model.ControllerGroup
import com.myhope.sync.model.SyncResult
import com.myhope.sync.psi.SpringControllerParser
import com.myhope.sync.service.SyncService
import com.myhope.sync.settings.getEffectiveSettings
import com.myhope.sync.util.I18n
import javax.swing.SwingUtilities

class SyncAction : AnAction() {

    private val log = Logger.getInstance(SyncAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return

        // 1. Check settings（有效配置：项目覆盖 + 全局默认）
        val effective = getEffectiveSettings(project)
        if (effective.serverUrl.isBlank() || effective.apiToken.isBlank()) {
            Messages.showWarningDialog(
                project,
                I18n.message(project, "dialog.notConfigured.message"),
                "MyHope Sync"
            )
            return
        }

        // 2. Parse controllers in background (with ReadAction)
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            I18n.message(project, "progress.scanning.title"),
            false
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = I18n.message(project, "progress.indicator.scanning")

                val groups: List<ControllerGroup> = try {
                    var result: List<ControllerGroup> = emptyList()
                    ApplicationManager.getApplication().runReadAction {
                        result = SpringControllerParser(project).parseAllControllers()
                    }
                    result
                } catch (ex: Exception) {
                    log.warn("Controller scan failed", ex)
                    SwingUtilities.invokeLater {
                        Messages.showErrorDialog(
                            project,
                            I18n.message(project, "dialog.scanError.message", ex.message ?: ""),
                            I18n.message(project, "dialog.scanError.title")
                        )
                    }
                    return
                }

                val totalEndpoints = groups.sumOf { it.endpoints.size }

                SwingUtilities.invokeLater {
                    // 3. Confirm with user
                    if (groups.isEmpty()) {
                        Messages.showInfoMessage(
                            project,
                            "No Spring Boot controllers found in the project.",
                            I18n.message(project, "dialog.scanError.title")
                        )
                        return@invokeLater
                    }

                    val result = Messages.showYesNoDialog(
                        project,
                        "Found ${groups.size} controllers with $totalEndpoints endpoints.\n\nProceed to sync to MyHope?",
                        I18n.message(project, "dialog.confirmSync.title"),
                        Messages.getQuestionIcon()
                    )

                    if (result != Messages.YES) return@invokeLater

                    // 4. Sync in background
                    ProgressManager.getInstance().run(object : Task.Backgroundable(
                        project,
                        I18n.message(project, "progress.syncing.title"),
                        false
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
                                val syncParser = SpringControllerParser(project)
                                val service = SyncService(project, client, syncParser, indicator)
                                val allGroups = syncParser.parseAllControllers()
                                val syncResult = service.syncToMyHope(allGroups)

                                SwingUtilities.invokeLater {
                                    showSyncResult(project, syncResult)
                                }
                            } finally {
                                client.close()
                            }
                        }
                    })
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabled = project != null
        e.presentation.isVisible = project != null
    }

    private fun showSyncResult(project: com.intellij.openapi.project.Project, result: SyncResult) {
        val message = buildString {
            appendLine(I18n.message(project, "result.total", result.total))
            appendLine(I18n.message(project, "result.success", result.success))
            appendLine(I18n.message(project, "result.failed", result.failed))
            appendLine(I18n.message(project, "result.collections", result.collectionsCreated))
            appendLine(I18n.message(project, "result.requests", result.requestsCreated))
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
