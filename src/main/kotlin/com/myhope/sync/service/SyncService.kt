package com.myhope.sync.service

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.myhope.sync.myhope.MyHopeClient
import com.myhope.sync.myhope.MyHopeDataConverter
import com.myhope.sync.model.*
import com.myhope.sync.psi.SpringControllerParser
import com.myhope.sync.settings.AppSettings
import com.myhope.sync.settings.ProjectAppSettings
import com.myhope.sync.settings.getEffectiveSettings
import com.myhope.sync.settings.writeBackBinding
import com.myhope.sync.util.I18n

/**
 * 同步编排服务（面向 myhope-pr-server）。
 *
 * 协调从 Spring Controller 解析到 myhope 分支快照的同步流程：
 * 绑定校验 → 工作空间 → 分支（匹配/创建）→ items 转换 → getOplogHead 实时水位 →
 * getSnapshot（diff 基准）→ buildOps（method+url/title 匹配，同一 parent 集合内）→
 * 分批 push_oplog（≤500/批，OCC 409 用 server_max_seq 更新水位重推≤3）。
 * 唯一写入通道为增量 push_oplog；禁止全量推送/导入接口（分支为空时 buildOps 生成全量 create ops）。
 * 水位不本地缓存（每次同步实时查询，保证多实例一致）。
 */
class SyncService(
    private val project: Project,
    private val client: MyHopeClient,
    private val parser: SpringControllerParser,
    private val indicator: ProgressIndicator? = null
) {

    private val log = Logger.getInstance(SyncService::class.java)
    private val converter = MyHopeDataConverter()

    /**
     * 同步 Controller 分组到 myhope 分支。
     *
     * 流程：绑定校验 → 工作空间 → 分支(匹配/创建) → items 转换 → getOplogHead 实时水位 →
     * getSnapshot → buildOps → push_oplog
     * （diff 生成 op、分批推送、409 OCC 用 server_max_seq 重推）。
     *
     * 任一必要查询失败（getOplogHead / getSnapshot）直接报错中止，不静默、不兜底、不重试；
     * 写入只走增量 push_oplog（禁止全量推送/导入接口）。
     */
    fun syncToMyHope(
        groups: List<ControllerGroup>,
        targetParentCollectionId: String? = null
    ): SyncResult {
        val totalEndpoints = groups.sumOf { it.endpoints.size }
        val errors = mutableListOf<String>()
        val effective = getEffectiveSettings(project)
        val projectSettings = ProjectAppSettings.getInstance(project)
        log.info("syncToMyHope 开始，端点数=$totalEndpoints")

        indicator?.let {
            it.text = "开始同步到 MyHope..."
            it.isIndeterminate = false
            it.fraction = 0.0
        }

        // ========== 1. 绑定校验 ==========
        if (effective.apiToken.isBlank()) {
            log.warn("未配置 API Token，中止同步")
            errors.add("未配置 API Token，请在设置中填写")
            return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
        }

        // 确保实例 id 已生成（有效配置读取时已保证：全局单例生成）
        AppSettings.getInstance().getOrCreateInstanceId()

        var accessInstanceId = effective.accessInstanceId
        var userAppId = effective.userAppId

        // 无条件 tokenInfo 轻校验：即便本地有缓存也校验服务端绑定是否仍有效。
        // 服务端 myhope_bindings 按 pr_user_id upsert 覆盖，token-info 要求 (pr_user, mh_xxx) 精确匹配，
        // 被其他实例顶掉后本地缓存已失效 → 必须校验并自愈抢回，避免使用失效绑定导致同步失败。
        var boundOk = false
        val tokenInfoResult = client.tokenInfo()
        if (tokenInfoResult.isSuccess) {
            val info = tokenInfoResult.getOrNull()
            // HTTP 200 即代表服务端已确认 (token, instance) 绑定有效；tokenInfo 成功且 acc/app 非空即可用。
            // （勿依赖本地 TokenInfo.valid——服务端不下发该字段，恒为 false。）
            if (info != null &&
                !info.accessInstanceId.isNullOrBlank() && !info.userAppId.isNullOrBlank()
            ) {
                // 成功且有效 → 用返回值覆盖本地（可能优于旧缓存），写回分层继续
                accessInstanceId = info.accessInstanceId
                userAppId = info.userAppId
                writeBackBinding(project, accessInstanceId, userAppId)
                log.info("tokenInfo 校验通过并回写绑定: acc=$accessInstanceId app=$userAppId")
                boundOk = true
            }
        }

        // 未获得有效绑定（tokenInfo 失败 / invalid / 未绑定）
        if (!boundOk) {
            val wasBound = effective.accessInstanceId.isNotBlank() || effective.userAppId.isNotBlank()
            val tokenErrMsg = tokenInfoResult.exceptionOrNull()?.message ?: ""
            val isNetworkFault = tokenErrMsg.contains("网络请求失败")

            if (wasBound && !isNetworkFault) {
                // 曾绑定过且 tokenInfo 失败非网络故障 → 判定"绑定可能被其他实例顶掉"。
                // 禁止自动抢回绑定：同步立即中止，弹【重新绑定】通知由用户决定是否恢复。
                log.warn("绑定可能已被其他实例顶掉，同步中止（等待用户通过通知按钮重新绑定）")
                val abortMsg =
                    "MyHope 绑定可能已被其他实例顶掉，同步中止。请点击通知中的「重新绑定」按钮恢复绑定"
                errors.add(abortMsg)
                notifyBindingLostWithRebind()
                return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
            }

            // 从未绑定（首次绑定）/ tokenInfo 网络故障 → 保留自动 bindMyHope 流程：
            // 成功继续，失败明确报错返回，不静默
            val bindResult = client.bindMyHope()
            if (bindResult.isSuccess) {
                val binding = bindResult.getOrNull()
                if (binding != null && binding.ok &&
                    !binding.accessInstanceId.isNullOrBlank() && !binding.userAppId.isNullOrBlank()
                ) {
                    accessInstanceId = binding.accessInstanceId
                    userAppId = binding.userAppId
                    writeBackBinding(project, accessInstanceId, userAppId)
                    log.info("bindMyHope 绑定成功: acc=$accessInstanceId app=$userAppId")
                } else {
                    log.warn("绑定 myhope 失败: ${bindResult.exceptionOrNull()?.message ?: "服务端未返回 access_instance_id/user_app_id"}")
                    errors.add(
                        "绑定 myhope 失败: ${bindResult.exceptionOrNull()?.message
                            ?: "服务端未返回 access_instance_id/user_app_id"}"
                    )
                    return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
                }
            } else {
                log.warn("绑定 myhope 失败: ${bindResult.exceptionOrNull()?.message}")
                errors.add("绑定 myhope 失败: ${bindResult.exceptionOrNull()?.message}")
                return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
            }
        }

        // 用户标识自动生成（不再要求手填；有效配置读取时已通过全局 getOrCreateMyHopeUserId 保证）

        indicator?.let {
            it.text = "校验通过，正在获取工作空间..."
            it.fraction = 0.1
        }

        // ========== 2. 工作空间 ==========
        val workspacesResult = client.listWorkspaces(accessInstanceId, userAppId)
        if (workspacesResult.isFailure) {
            log.warn("获取工作空间失败: ${workspacesResult.exceptionOrNull()?.message}")
            errors.add("获取工作空间失败: ${workspacesResult.exceptionOrNull()?.message}")
            return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
        }
        val workspaces = workspacesResult.getOrNull() ?: emptyList()
        if (workspaces.isEmpty()) {
            log.warn("没有可用工作空间")
            errors.add("没有可用工作空间")
            return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
        }
        val workspace = workspaces.firstOrNull { it.id == projectSettings.workspaceId }
            ?: workspaces.firstOrNull { it.isDefault }
            ?: workspaces.first()
        val workspaceId = workspace.id
        projectSettings.workspaceId = workspaceId
        log.info("工作空间列表 ${workspaces.size} 个，选用 ${workspace.name} ($workspaceId)")

        indicator?.let {
            it.text = "工作空间: ${workspace.name}，正在获取分支..."
            it.fraction = 0.25
        }

        // ========== 3. 分支（按名匹配 / 创建） ==========
        val branchName = projectSettings.branchName.ifBlank { project.name }
        val branchesResult = client.listBranches(accessInstanceId, userAppId)
        if (branchesResult.isFailure) {
            log.warn("获取分支失败: ${branchesResult.exceptionOrNull()?.message}")
            errors.add("获取分支失败: ${branchesResult.exceptionOrNull()?.message}")
            return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
        }
        val branches = branchesResult.getOrNull() ?: emptyList()
        var branchId = branches.firstOrNull { it.name == branchName }?.id
        if (branchId == null) {
            val createResult = client.createBranch(
                accessInstanceId = accessInstanceId,
                userAppId = userAppId,
                workspaceId = workspaceId,
                id = generateEntityId("br", branchName),
                name = branchName,
                visibility = "personal"
            )
            if (createResult.isSuccess) {
                branchId = createResult.getOrNull()?.id
                if (branchId.isNullOrBlank()) {
                    log.warn("创建分支失败: 服务端未返回分支 id")
                    errors.add("创建分支失败: 服务端未返回分支 id")
                    return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
                }
                log.info("创建分支成功: $branchName → $branchId")
            } else {
                log.warn("创建分支失败: ${createResult.exceptionOrNull()?.message}")
                errors.add("创建分支失败: ${createResult.exceptionOrNull()?.message}")
                return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
            }
        }
        projectSettings.branchId = branchId
        projectSettings.branchName = branchName
        log.info("分支列表 ${branches.size} 个，匹配/创建 $branchName → $branchId")

        indicator?.let {
            it.text = "分支: $branchName，正在转换 items..."
            it.fraction = 0.5
        }

        // ========== 4. items 转换 ==========
        val items = converter.toSnapshotItems(groups, projectSettings.createSubDirectory, targetParentCollectionId)
        if (items.isEmpty()) {
            log.warn("没有可同步的端点")
            errors.add("没有可同步的端点")
            return SyncResult(total = totalEndpoints, failed = totalEndpoints, errors = errors)
        }
        val collectionCount = items.count { it.type == ItemType.COLLECTION }
        val requestCount = items.count { it.type == ItemType.REQUEST }
        log.info("items 转换完成: ${items.size} 个（集合 $collectionCount / 请求 $requestCount）")

        indicator?.let {
            it.text = "正在同步（增量/初始化）..."
            it.fraction = 0.6
        }

        // ========== 5. 实时水位（不缓存） & 增量同步 ==========
        // 每次同步先 getOplogHead 拿服务端真实 currentSeq 作 baseSeq；失败直接报错中断（不静默、不兜底、不重试）
        val headResult = client.getOplogHead(accessInstanceId, userAppId, branchId, workspaceId)
        if (headResult.isFailure) {
            val errMsg = "获取服务端水位失败: ${headResult.exceptionOrNull()?.message}"
            log.warn(errMsg)
            errors.add(errMsg)
            indicator?.let { it.text = "同步失败"; it.fraction = 1.0 }
            return SyncResult(total = totalEndpoints, success = 0, failed = totalEndpoints, errors = errors)
        }
        val baseSeq = headResult.getOrNull()?.currentSeq ?: 0
        log.info("getOplogHead 实时水位: current_seq=$baseSeq")

        // ========== 6. 增量路径（唯一推送通道） ==========
        // 禁全量推送：不调用 putSnapshot/导入类接口。分支为空（base_seq=0）时 buildOps 自然
        // 把全部本地 items 生成为 create ops，经 pushOplog 增量落库（等价"增量初始化"）。
        // a. 拉服务端当前 items（diff 基准）。失败即中止，避免基于错误基准生成错误 ops
        val serverResult = client.getSnapshot(accessInstanceId, userAppId, workspaceId, branchId)
        if (serverResult.isFailure) {
            val errMsg = "获取服务端快照失败，已中止增量同步: ${serverResult.exceptionOrNull()?.message}"
            log.warn(errMsg)
            errors.add(errMsg)
            indicator?.let { it.text = "同步失败"; it.fraction = 1.0 }
            return SyncResult(total = totalEndpoints, success = 0, failed = totalEndpoints, errors = errors)
        }
        val serverItems = serverResult.getOrNull() ?: emptyList()

        // b. 生成本地 ops（method+url/title 匹配，限定目标目录范围，不做 id 比较）
        val authorId = effective.myhopeUserId.ifBlank { "unknown" }
        val ops = converter.buildOps(
            localItems = items,
            serverItems = serverItems,
            baseSeq = baseSeq,
            branchId = branchId,
            workspaceId = workspaceId,
            authorId = authorId,
            targetParentId = targetParentCollectionId
        )
        if (ops.isEmpty()) {
            log.info("目标目录内本地与服务端一致，无 ops 可推")
            indicator?.let { it.text = "同步完成（无变化）"; it.fraction = 1.0 }
            // 无差异：实际未推送任何 op，success 记 0（避免误导为"成功推送 N 个"），无失败
            return SyncResult(total = totalEndpoints, success = 0, failed = 0, errors = errors)
        }
        val createdCollections = ops.count { it["op_type"] == "create_collection" }
        val createdRequests = ops.count { it["op_type"] == "create_request" }
        val updatedRequests = ops.count { isRequestOp(it) && (it["op_type"] as? String)?.startsWith("update_") == true }

        // c. 分批 push（≤500/批），每批独立 OCC：409 → server_max_seq 更新水位重推，最多 3 次
        val batches = ops.chunked(500)
        var currentSeq = baseSeq
        var pushedOps = 0
        var pushedRequestOps = 0
        var success = true
        for (batch in batches) {
            var attempt = 0
            var batchOk = false
            var batchHardFailed = false
            while (attempt < 3) {
                attempt++
                val reseq = batch.mapIndexed { i, op ->
                    (op as MutableMap<String, Any?>).apply { put("seq", currentSeq + 1 + i) }
                }
                val unitKey = "$branchId:$workspaceId"
                val result = client.pushOplog(
                    accessInstanceId = accessInstanceId,
                    userAppId = userAppId,
                    workspaceId = workspaceId,
                    branchOps = mapOf(unitKey to mapOf("base_seq" to currentSeq, "ops" to reseq))
                )
                val unit = result.getOrNull()?.get(unitKey)
                when {
                    result.isFailure -> {
                        errors.add("增量推送失败: ${result.exceptionOrNull()?.message}")
                        batchHardFailed = true
                        break
                    }
                    unit?.accepted == true -> {
                        currentSeq = unit.serverMaxSeq ?: (currentSeq + batch.size)
                        pushedOps += batch.size
                        pushedRequestOps += batch.count { isRequestOp(it) }
                        batchOk = true
                        break
                    }
                    unit?.accepted == false -> {
                        val newBase = unit.serverMaxSeq ?: currentSeq
                        log.warn("OCC 冲突，server_max_seq=$newBase，重试第 $attempt 次")
                        currentSeq = newBase
                        // 循环继续重推本批（re-seq 基于 newBase）
                    }
                    else -> {
                        errors.add("推送响应解析失败")
                        batchHardFailed = true
                        break
                    }
                }
            }
            if (!batchOk) {
                if (!batchHardFailed) {
                    errors.add("同步冲突（OCC），重试 3 次后仍失败，请稍后重试或到 Web 端处理")
                }
                success = false
                break
            }
        }

        // d. 水位不本地持久化（oplogBaseSeq 已删除）：下次同步实时 getOplogHead 重新获取，
        //    已成功推送的 batch 不会重复（服务端已落库的 seq 由服务端水位保证）
        indicator?.let {
            it.text = if (success) "同步完成" else "同步失败"
            it.fraction = 1.0
        }

        log.info("syncToMyHope 增量完成: success=$success, ops=$pushedOps, errors=${errors.size}")

        // auto-publish：同步成功且有实际推送时，若开关开启且配置了目标分支 → 尝试创建 PR（失败不阻断同步）
        var prCreated = false
        var prError: String? = null
        if (success && pushedOps > 0 && effective.autoPublishPr && effective.prTargetBranchId.isNotBlank()) {
            try {
                prCreated = autoPublishPr(accessInstanceId, userAppId, workspaceId, branchId)
            } catch (e: Throwable) {
                prError = e.message ?: e.javaClass.simpleName
                log.warn("auto-publish PR 失败: $prError")
            }
        }

        return SyncResult(
            total = totalEndpoints,
            success = pushedRequestOps,
            failed = if (errors.isEmpty()) 0 else (totalEndpoints - pushedRequestOps).coerceAtLeast(0),
            collectionsCreated = if (success) createdCollections else 0,
            requestsCreated = if (success) createdRequests else 0,
            requestsUpdated = if (success) updatedRequests else 0,
            errors = errors,
            prCreated = prCreated,
            prMessage = if (prCreated) null else prError
        )
    }

    /**
     * auto-publish：当前分支（source）→ 配置的目标分支（target）创建 PR。
     * 若同 source→target 已存在进行中 PR 则跳过（不重复建）。
     * @return 是否本次实际创建了 PR（false 可能是跳过或失败，失败经异常抛出）
     */
    private fun autoPublishPr(
        accessInstanceId: String,
        userAppId: String,
        workspaceId: String,
        sourceBranchId: String
    ): Boolean {
        val targetBranchId = getEffectiveSettings(project).prTargetBranchId
        val projectSettings = ProjectAppSettings.getInstance(project)

        // 1. 检查同 source→target 是否已有进行中 PR（branch_id=source 的列表，过滤 status 非 merged/closed）
        val listResult = client.listPullRequests(accessInstanceId, userAppId, workspaceId, sourceBranchId)
        if (listResult.isFailure) {
            throw IllegalStateException("查询已有 PR 失败: ${listResult.exceptionOrNull()?.message}")
        }
        val existing = listResult.getOrNull()?.items.orEmpty()
            .filter { it.targetBranchId == targetBranchId && it.isInProgress }
        if (existing.isNotEmpty()) {
            log.info("auto-publish: 同 source→target 已有进行中 PR（${existing.first().id}），跳过创建")
            return false
        }

        // 2. 创建 PR
        val targetBranchName = listResult.getOrNull()?.items.orEmpty()
            .firstOrNull { it.targetBranchId == targetBranchId }?.targetBranchName
            ?: targetBranchId
        val title = "API 同步: ${projectSettings.branchName.ifBlank { project.name }} → $targetBranchName"
        val description = "由 MyHope Sync 插件自动发布（${java.time.LocalDateTime.now()}）"
        val createResult = client.createPullRequest(
            accessInstanceId = accessInstanceId,
            userAppId = userAppId,
            workspaceId = workspaceId,
            sourceBranchId = sourceBranchId,
            targetBranchId = targetBranchId,
            title = title,
            description = description
        )
        if (createResult.isFailure) {
            throw IllegalStateException("创建 PR 失败: ${createResult.exceptionOrNull()?.message}")
        }
        val pr = createResult.getOrNull()
        log.info("auto-publish: PR 创建成功 id=${pr?.id} title=${pr?.title}")
        Notifications.Bus.notify(
            Notification(
                "MyHope Sync",
                I18n.message(project, "notification.prCreated.title"),
                I18n.message(project, "notification.prCreated.content", pr?.id ?: ""),
                NotificationType.INFORMATION
            ),
            project
        )
        return true
    }

    /** 是否为请求 op（entity_id 前缀 hr_）。 */
    private fun isRequestOp(op: Map<String, Any?>): Boolean =
        (op["entity_id"] as? String)?.startsWith("hr_") == true

    // ====================================================================
    //  绑定被顶掉处理：弹【重新绑定】通知，由用户决定是否抢回（禁止自动 bindMyHope）
    // ====================================================================

    /** 弹"绑定可能被顶掉"错误通知，携带【重新绑定】按钮。点击才后台 bindMyHope 并写回。 */
    private fun notifyBindingLostWithRebind() {
        Notifications.Bus.notify(
            Notification(
                "MyHope Sync",
                I18n.message(project, "notification.bindingLost.title"),
                I18n.message(project, "notification.bindingLost.content"),
                NotificationType.ERROR
            ).addAction(NotificationAction.createSimple(
                I18n.message(project, "notification.bindingLost.action.rebind")
            ) { rebindBinding() }),
            project
        )
    }

    /**
     * 用户在通知上点击【重新绑定】：后台线程用**实时有效配置**重建 client 并 bindMyHope，
     * 成功 → [writeBackBinding] 写回 + 成功通知；失败 → 错误通知（含异常消息）。
     */
    private fun rebindBinding() {
        Thread(Runnable {
            val effective = getEffectiveSettings(project)
            val client = MyHopeClient(
                serverUrl = effective.serverUrl,
                apiToken = effective.apiToken,
                instanceId = effective.instanceId,
                myhopeUserId = effective.myhopeUserId,
                myhopeUserName = effective.myhopeUserName
            )
            try {
                val result = client.bindMyHope()
                if (result.isSuccess) {
                    val b = result.getOrNull()
                    if (b != null && b.ok &&
                        !b.accessInstanceId.isNullOrBlank() && !b.userAppId.isNullOrBlank()
                    ) {
                        writeBackBinding(project, b.accessInstanceId, b.userAppId)
                        Notifications.Bus.notify(
                            Notification(
                                "MyHope Sync",
                                I18n.message(project, "notification.rebindSuccess.title"),
                                I18n.message(project, "notification.rebindSuccess.content"),
                                NotificationType.INFORMATION
                            ),
                            project
                        )
                    } else {
                        Notifications.Bus.notify(
                            Notification(
                                "MyHope Sync",
                                I18n.message(project, "notification.bindingLost.title"),
                                I18n.message(
                                    project, "notification.rebindFailed.content",
                                    result.exceptionOrNull()?.message ?: "服务端未返回 access_instance_id/user_app_id"
                                ),
                                NotificationType.ERROR
                            ),
                            project
                        )
                    }
                } else {
                    Notifications.Bus.notify(
                        Notification(
                            "MyHope Sync",
                            I18n.message(project, "notification.bindingLost.title"),
                            I18n.message(
                                project, "notification.rebindFailed.content",
                                result.exceptionOrNull()?.message ?: "绑定失败"
                            ),
                            NotificationType.ERROR
                        ),
                        project
                    )
                }
            } catch (e: Throwable) {
                Notifications.Bus.notify(
                    Notification(
                        "MyHope Sync",
                        I18n.message(project, "notification.bindingLost.title"),
                        I18n.message(project, "notification.rebindFailed.content", e.message ?: e.javaClass.simpleName),
                        NotificationType.ERROR
                    ),
                    project
                )
            } finally {
                client.close()
            }
        }, "MyHope-rebind-from-sync").apply { isDaemon = true; start() }
    }
}