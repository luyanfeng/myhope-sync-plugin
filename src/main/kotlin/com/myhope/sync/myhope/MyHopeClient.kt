package com.myhope.sync.myhope

import com.google.gson.*
import com.intellij.openapi.diagnostic.Logger
import com.myhope.sync.model.*
import com.myhope.sync.util.LogUtil
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * MyHope REST API 客户端（面向 myhope-pr-server）。
 *
 * 认证：第三方客户端走 `/pr-api/` 前缀，必需两个 header：
 * - `X-API-Token: <api-tok_xxx>`（Owner 分发的接入 token）
 * - `X-MyHope-Instance-ID: <mh_xxx>`（客户端本地生成并持久化，每实例唯一）
 *
 * 三层隔离：
 * - `access_instance_id`（服务端生成 acc_xxx）
 * - `user_app_id`（服务端生成 app_xxx，绑定后返回）
 * - `workspace_id`（客户端本地生成 ws_xxx）
 * POST body 传、GET query 传。
 *
 * 所有方法返回 [Result]，网络异常/HTTP 错误返回 [Result.failure]（[MyHopeException]）。
 */
class MyHopeClient(
    private val serverUrl: String,
    private val apiToken: String,          // X-API-Token
    private val instanceId: String,        // X-MyHope-Instance-ID
    private val myhopeUserId: String = "", // 绑定用
    private val myhopeUserName: String = "",
    private val converter: MyHopeDataConverter? = null // 快照序列化复用（保证 data 与 data_hash 同源）
) : AutoCloseable {

    private val normalizedUrl: String = serverUrl.trimEnd('/')

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ItemType::class.java, ItemTypeJsonAdapter())
        .create()

    private val log = Logger.getInstance(MyHopeClient::class.java)

    /** 当前接入 token（X-API-Token）。保留兼容命名。 */
    @Volatile
    var currentAccessToken: String = apiToken
        private set

    /** 绑定后的 myhope_user_id（bind 成功后写入）。 */
    @Volatile
    var currentUserId: String? = null
        private set

    /** X-MyHope-Instance-ID 值（构造时固定）。 */
    val boundInstanceId: String = instanceId

    /** 绑定后服务端返回的 access_instance_id / user_app_id 缓存。 */
    @Volatile
    var boundAccessInstanceId: String? = null
        private set

    @Volatile
    var boundUserAppId: String? = null
        private set

    // ====================================================================
    //  认证 / 绑定
    // ====================================================================

    /**
     * PUT /pr-api/api/bind-myhope — 绑定 MyHope 实例（首次）。
     * 绑定成功后缓存 access_instance_id / user_app_id，并写入 currentUserId。
     */
    fun bindMyHope(): Result<BindingInfo> {
        val body = mapOf(
            "myhope_user_id" to myhopeUserId,
            "myhope_user_name" to myhopeUserName
        )
        val result = execute("PUT", "/pr-api/api/bind-myhope", body = body) { el ->
            val info = gson.fromJson(el, BindingInfo::class.java)
            if (info.ok) {
                boundAccessInstanceId = info.accessInstanceId
                boundUserAppId = info.userAppId
                currentUserId = myhopeUserId.takeIf { it.isNotBlank() }
                log.info("[MyHope] bindMyHope 成功: access_instance_id=${info.accessInstanceId}, user_app_id=${info.userAppId}")
            }
            info
        }
        if (result.isFailure) {
            log.warn("[MyHope] bindMyHope 失败: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    /**
     * GET /pr-api/api/auth/token-info — token 校验 / 启动检查。
     *
     * 服务端中间件 + handler 已强校验 (X-API-Token, X-MyHope-Instance-ID) 绑定存在，
     * 故 **HTTP 200 即视为绑定有效**；本地 `TokenInfo.valid` 字段服务端不下发、恒为 false，
     * 不能作为有效性依据。未绑定 / 被顶掉 → 401 → Result.failure。
     */
    fun tokenInfo(): Result<TokenInfo> {
        val result = execute("GET", "/pr-api/api/auth/token-info") { el ->
            val info = gson.fromJson(el, TokenInfo::class.java)
            if (info.accessInstanceId != null) boundAccessInstanceId = info.accessInstanceId
            if (info.userAppId != null) boundUserAppId = info.userAppId
            log.info("[MyHope] tokenInfo 200 有效: acc=${info.accessInstanceId}, app=${info.userAppId}")
            info
        }
        if (result.isFailure) {
            log.warn("[MyHope] tokenInfo 失败: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    // ====================================================================
    //  工作空间 / 分支
    // ====================================================================

    /**
     * GET /pr-api/api/workspaces — 查询工作空间列表。
     */
    fun listWorkspaces(
        accessInstanceId: String,
        userAppId: String
    ): Result<List<WorkspaceInfo>> {
        val result = execute(
            "GET", "/pr-api/api/workspaces",
            query = mapOf(
                "access_instance_id" to accessInstanceId,
                "user_app_id" to userAppId
            )
        ) { el ->
            val list = if (el.isJsonArray) gson.fromJson(el, Array<WorkspaceInfo>::class.java).toList() else emptyList()
            log.info("[MyHope] listWorkspaces 返回 ${list.size} 个工作空间")
            list
        }
        if (result.isFailure) {
            log.warn("[MyHope] listWorkspaces 失败: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    /**
     * POST /pr-api/api/workspaces — 创建工作空间（local-first，id 客户端生成必传）。
     */
    fun createWorkspace(
        accessInstanceId: String,
        userAppId: String,
        id: String,
        name: String,
        userId: String
    ): Result<WorkspaceInfo> {
        val body = buildMap<String, Any?> {
            put("id", id)
            put("name", name)
            put("user_id", userId)
        }
        val result = execute(
            "POST", "/pr-api/api/workspaces",
            query = mapOf(
                "access_instance_id" to accessInstanceId,
                "user_app_id" to userAppId
            ),
            body = body
        ) { el ->
            val info = gson.fromJson(el, WorkspaceInfo::class.java)
            log.info("[MyHope] createWorkspace 成功: id=${info.id}, name=${info.name}")
            info
        }
        if (result.isFailure) {
            log.warn("[MyHope] createWorkspace 失败: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    /**
     * GET /pr-api/api/branches — 查询分支列表（取 items）。
     */
    fun listBranches(
        accessInstanceId: String,
        userAppId: String,
        page: Int = 1,
        perPage: Int = 50
    ): Result<List<BranchInfo>> {
        val result = execute(
            "GET", "/pr-api/api/branches",
            query = mapOf(
                "access_instance_id" to accessInstanceId,
                "user_app_id" to userAppId,
                "page" to page.toString(),
                "per_page" to perPage.toString()
            )
        ) { el ->
            val items = el.asJsonObject.get("items")
            val list = if (items != null && items.isJsonArray) {
                gson.fromJson(items, Array<BranchInfo>::class.java).toList()
            } else {
                emptyList()
            }
            log.info("[MyHope] listBranches 返回 ${list.size} 个分支")
            list
        }
        if (result.isFailure) {
            log.warn("[MyHope] listBranches 失败: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    /**
     * POST /pr-api/api/branches — 创建分支（local-first，id 客户端生成必传）。
     */
    fun createBranch(
        accessInstanceId: String,
        userAppId: String,
        workspaceId: String,
        id: String,
        name: String,
        visibility: String = "personal",
        description: String? = null
    ): Result<BranchInfo> {
        val body = buildMap<String, Any?> {
            put("id", id)
            put("access_instance_id", accessInstanceId)
            put("user_app_id", userAppId)
            put("workspace_id", workspaceId)
            put("name", name)
            put("visibility", visibility)
            description?.let { put("description", it) }
        }
        val result = execute("POST", "/pr-api/api/branches", body = body) { el ->
            val info = gson.fromJson(el, BranchInfo::class.java)
            log.info("[MyHope] createBranch 成功: id=${info.id}, name=${info.name}")
            info
        }
        if (result.isFailure) {
            log.warn("[MyHope] createBranch 失败: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    // ====================================================================
    //  快照（只读查询；写入一律走 pushOplog 增量，禁止全量推送/导入）
    // ====================================================================

    /**
     * GET /pr-api/api/branches/{branch_id}/snapshot — 读取单元快照。
     */
    fun getSnapshot(
        accessInstanceId: String,
        userAppId: String,
        workspaceId: String,
        branchId: String,
        version: Int? = null
    ): Result<List<SnapshotItem>> {
        val query = buildMap<String, String> {
            put("access_instance_id", accessInstanceId)
            put("user_app_id", userAppId)
            put("workspace_id", workspaceId)
            version?.let { put("version", it.toString()) }
        }
        val result = execute("GET", "/pr-api/api/branches/$branchId/snapshot", query = query) { el ->
            val data = el.asJsonObject.get("data")
            val list = if (data != null && data.isJsonArray) {
                gson.fromJson(data, Array<SnapshotItem>::class.java).toList()
            } else {
                emptyList()
            }
            val version = el.asJsonObject.get("version")?.asInt
            log.info("[MyHope] getSnapshot 返回 ${list.size} 个 items (version=$version)")
            list
        }
        if (result.isFailure) {
            log.warn("[MyHope] getSnapshot 失败: branch=$branchId, ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    // ====================================================================
    //  Oplog
    // ====================================================================

    /**
     * GET /pr-api/api/branches/oplog/head — 单元水位预查。
     */
    fun getOplogHead(
        accessInstanceId: String,
        userAppId: String,
        branchId: String,
        workspaceId: String
    ): Result<OplogHeadInfo> {
        val result = execute(
            "GET", "/pr-api/api/branches/oplog/head",
            query = mapOf(
                "access_instance_id" to accessInstanceId,
                "user_app_id" to userAppId,
                "branch_id" to branchId,
                "workspace_id" to workspaceId
            )
        ) { el ->
            val info = gson.fromJson(el, OplogHeadInfo::class.java)
            log.info("[MyHope] getOplogHead: branch=${info.branchId}, current_seq=${info.currentSeq}")
            info
        }
        if (result.isFailure) {
            log.warn("[MyHope] getOplogHead 失败: branch=$branchId, ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    /**
     * POST /pr-api/api/branches/oplog — 增量推送（基础版）。
     *
     * @param branchOps 形如 `{"{bid}:{wsid}": {"base_seq": N, "ops": [...]}}`
     * @param workspaceId 当前工作空间 id（query 必带，三层隔离）
     * @return per-unit 结果映射；409 也是正常返回（条目 accepted=false，server_max_seq 作新 base_seq）
     */
    fun pushOplog(
        accessInstanceId: String,
        userAppId: String,
        workspaceId: String,
        branchOps: Map<String, Map<String, Any>>,
        merge: Any? = null
    ): Result<Map<String, OplogPushResult>> {
        val body = buildMap<String, Any?> {
            put("branch_ops", branchOps)
            put("merge", merge)
        }
        return execute(
            "POST", "/pr-api/api/branches/oplog",
            query = mapOf(
                "access_instance_id" to accessInstanceId,
                "user_app_id" to userAppId,
                "workspace_id" to workspaceId
            ),
            body = body,
            okStatuses = setOf(200, 409) // 409 也有 per-unit map 体，照常解析
        ) { el ->
            val result = mutableMapOf<String, OplogPushResult>()
            if (el.isJsonObject) {
                for ((key, value) in el.asJsonObject.entrySet()) {
                    result[key] = gson.fromJson(value, OplogPushResult::class.java)
                }
            }
            result
        }
    }

    // ====================================================================
    //  分支保护
    // ====================================================================

    /**
     * GET /pr-api/api/protection-rules/by-branch — 分支保护预检。
     */
    fun getProtectionByBranch(
        accessInstanceId: String,
        userAppId: String,
        branchId: String
    ): Result<ProtectionInfo> {
        val result = execute(
            "GET", "/pr-api/api/protection-rules/by-branch",
            query = mapOf(
                "access_instance_id" to accessInstanceId,
                "user_app_id" to userAppId,
                "branch_id" to branchId
            )
        ) { el ->
            val info = gson.fromJson(el, ProtectionInfo::class.java)
            log.info("[MyHope] getProtectionByBranch: branch=${info.branchId}, is_protected=${info.isProtected}")
            info
        }
        if (result.isFailure) {
            log.warn("[MyHope] getProtectionByBranch 失败: branch=$branchId, ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    // ====================================================================
    //  Pull Request（auto-publish）
    // ====================================================================

    /**
     * GET /pr-api/api/pull-requests — 查询分支 PR 列表（可选按 status 过滤）。
     * 用于 auto-publish 前检查同 source→target 是否已有进行中 PR。
     */
    fun listPullRequests(
        accessInstanceId: String,
        userAppId: String,
        workspaceId: String,
        branchId: String,
        status: String? = null
    ): Result<PullRequestListResult> {
        val query = buildMap<String, String> {
            put("access_instance_id", accessInstanceId)
            put("user_app_id", userAppId)
            put("workspace_id", workspaceId)
            put("branch_id", branchId)
            status?.let { put("status", it) }
        }
        return execute("GET", "/pr-api/api/pull-requests", query = query) { el ->
            gson.fromJson(el, PullRequestListResult::class.java)
        }
    }

    /**
     * POST /pr-api/api/pull-requests — 创建 PR（服务端 status 初始为 draft）。
     *
     * @param diffSnapshot 可选的 diff 快照（普通客户端可不传，由服务端/Web 端生成）。
     */
    fun createPullRequest(
        accessInstanceId: String,
        userAppId: String,
        workspaceId: String,
        sourceBranchId: String,
        targetBranchId: String,
        title: String,
        description: String? = null,
        diffSnapshot: Any? = null
    ): Result<PullRequestInfo> {
        val body = buildMap<String, Any?> {
            put("access_instance_id", accessInstanceId)
            put("user_app_id", userAppId)
            put("workspace_id", workspaceId)
            put("source_branch_id", sourceBranchId)
            put("target_branch_id", targetBranchId)
            put("title", title)
            description?.let { put("description", it) }
            diffSnapshot?.let { put("diff_snapshot", it) }
        }
        val result = execute(
            "POST", "/pr-api/api/pull-requests",
            query = mapOf(
                "access_instance_id" to accessInstanceId,
                "user_app_id" to userAppId,
                "workspace_id" to workspaceId
            ),
            body = body,
            okStatuses = setOf(200, 201)
        ) { el ->
            gson.fromJson(el, PullRequestInfo::class.java)
        }
        if (result.isFailure) {
            log.warn("[MyHope] createPullRequest 失败: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    // ====================================================================
    //  关闭
    // ====================================================================

    override fun close() {
        client.close()
    }

    // ====================================================================
    //  通用请求骨架
    // ====================================================================

    /**
     * 通用 REST 请求骨架。
     *
     * 自动附带 `X-API-Token` / `X-MyHope-Instance-ID` 头。
     * 状态码在 [okStatuses] 内 → 用 [extractor] 解析 body 并返回 success；
     * 否则按错误约定（401/403/409/422/400）解析为 failure。
     */
    private fun <T> execute(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: Any? = null,
        okStatuses: Set<Int> = setOf(200, 201),
        extractor: (JsonElement) -> T
    ): Result<T> {
        return try {
            val request = buildRequest(method, path, query, body)
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            val respBody = response.body()
            LogUtil.stdout { "[MyHope-API] $method $path → HTTP $status" }
            if (status in okStatuses) {
                Result.success(extractor(parseBody(respBody)))
            } else {
                Result.failure(interpretError(status, respBody, path))
            }
        } catch (e: Exception) {
            Result.failure(MyHopeException("网络请求失败: ${e.message}", e))
        }
    }

    private fun buildRequest(
        method: String,
        path: String,
        query: Map<String, String>,
        body: Any?
    ): HttpRequest {
        val uri = buildUri(path, query)
        val builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(30))
            .header("X-API-Token", apiToken)
            .header("X-MyHope-Instance-ID", instanceId)
        if (body != null) {
            builder.header("Content-Type", "application/json")
        }
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(gson.toJson(it)) }
            ?: HttpRequest.BodyPublishers.noBody()
        when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(publisher)
            "PUT" -> builder.PUT(publisher)
            else -> builder.GET()
        }
        return builder.build()
    }

    private fun buildUri(path: String, query: Map<String, String>): URI {
        val base = normalizedUrl + path
        if (query.isEmpty()) return URI.create(base)
        val encoded = query.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return URI.create("$base?$encoded")
    }

    private fun parseBody(body: String): JsonElement {
        if (body.isBlank()) return JsonNull.INSTANCE
        return JsonParser.parseString(body)
    }

    /**
     * 按 myhope 错误约定解析非 2xx 响应。
     */
    private fun interpretError(status: Int, body: String, path: String): MyHopeException {
        val lowered = body.lowercase()
        return when {
            status == 401 ->
                MyHopeException("认证无效或未绑定（401）：请检查 X-API-Token 与实例绑定状态")
            status == 403 && (lowered.contains("protection_denied") || lowered.contains("受保护")) ->
                MyHopeException("分支受保护（protection_denied），无法直接写入")
            status == 409 ->
                MyHopeException("OCC 并发冲突（409）：$body")
            status == 422 ->
                MyHopeException("请求 payload 非法（422，超白名单或 op_type 未知）：$body")
            status == 400 && lowered.contains("empty_branch_ops") ->
                MyHopeException("empty_branch_ops（无差异）")
            else ->
                MyHopeException("HTTP $status @ $path: ${body.take(500)}")
        }
    }

    /** 从错误响应体提取 server_max_seq（OCC 冲突时作为新 base_seq）。 */
    private fun extractServerMaxSeq(body: String): Int? {
        return try {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("server_max_seq")?.asInt
                ?: obj.getAsJsonObject("data")?.get("server_max_seq")?.asInt
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * MyHope API 调用异常。
 */
class MyHopeException(message: String, cause: Throwable? = null) : Exception(message, cause)