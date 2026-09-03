package com.myhope.sync.settings

import com.intellij.openapi.project.Project

/**
 * 业务统一读取的"有效配置"：`项目级显式覆盖(非空) ? 项目级值 : 全局 AppSettings 默认值`。
 *
 * 合并规则（由 [getEffectiveSettings] 落实）：
 * - 账号级字段（[serverUrl]/[apiToken]/[instanceId]/[accessInstanceId]/[userAppId]/
 *   [myhopeUserId]/[myhopeUserName]/[language]/[logLevel]）：项目级字段 [String.isBlank]（空）
 *   时回落全局 [AppSettings] 值；
 * - 同步目标字段（[workspaceId]/[branchId]/[branchName]/[createSubDirectory]/
 *   [targetCollectionId]）：无全局默认，直接取项目级 [ProjectAppSettings]。
 *
 * instanceId (mh_xxx) 全局单例：全局生成一次（[AppSettings.getOrCreateInstanceId]）；
 * 项目覆盖时不自动为项目生成新 ID，仅当项目显式清空时回落全局。
 */
data class EffectiveSettings(
    val serverUrl: String,
    val apiToken: String,
    val instanceId: String,
    val accessInstanceId: String,
    val userAppId: String,
    val myhopeUserId: String,
    val myhopeUserName: String,
    val language: String,
    val logLevel: String,
    val workspaceId: String,
    val branchId: String,
    val branchName: String,
    val createSubDirectory: Boolean,
    val targetCollectionId: String,
    val autoPublishPr: Boolean,
    val prTargetBranchId: String
)

/** 构造项目的有效配置（合并 全局默认 AppSettings + 项目覆盖 ProjectAppSettings）。 */
fun getEffectiveSettings(project: Project): EffectiveSettings {
    val global = AppSettings.getInstance()
    val projectSettings = ProjectAppSettings.getInstance(project)
    return EffectiveSettings(
        serverUrl = projectSettings.serverUrl.ifBlank { global.serverUrl },
        apiToken = projectSettings.apiToken.ifBlank { global.apiToken },
        instanceId = projectSettings.instanceId.ifBlank { global.getOrCreateInstanceId() },
        accessInstanceId = projectSettings.accessInstanceId.ifBlank { global.accessInstanceId },
        userAppId = projectSettings.userAppId.ifBlank { global.userAppId },
        myhopeUserId = projectSettings.myhopeUserId.ifBlank { global.getOrCreateMyHopeUserId() },
        myhopeUserName = projectSettings.myhopeUserName.ifBlank { global.myhopeUserName },
        language = projectSettings.language.ifBlank { global.language },
        logLevel = projectSettings.logLevel.ifBlank { global.logLevel },
        workspaceId = projectSettings.workspaceId,
        branchId = projectSettings.branchId,
        branchName = projectSettings.branchName,
        createSubDirectory = projectSettings.createSubDirectory,
        targetCollectionId = projectSettings.targetCollectionId,
        autoPublishPr = projectSettings.autoPublishPr,
        prTargetBranchId = projectSettings.prTargetBranchId
    )
}

/**
 * 绑定信息（access_instance_id / user_app_id）写回分层：
 * 项目级已显式覆盖 serverUrl 或 apiToken → 写项目级 [ProjectAppSettings]
 * （保持 acc/app 与 instanceId 三元组同一层，见设置面板说明）；否则写全局 [AppSettings]。
 */
fun writeBackBinding(project: Project, accessInstanceId: String?, userAppId: String?) {
    val projectSettings = ProjectAppSettings.getInstance(project)
    val global = AppSettings.getInstance()
    if (projectSettings.serverUrl.isNotBlank() || projectSettings.apiToken.isNotBlank()) {
        if (!accessInstanceId.isNullOrBlank()) projectSettings.accessInstanceId = accessInstanceId
        if (!userAppId.isNullOrBlank()) projectSettings.userAppId = userAppId
    } else {
        if (!accessInstanceId.isNullOrBlank()) global.accessInstanceId = accessInstanceId
        if (!userAppId.isNullOrBlank()) global.userAppId = userAppId
    }
}