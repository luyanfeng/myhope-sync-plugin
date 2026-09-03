package com.myhope.sync.model

import com.google.gson.annotations.SerializedName

/**
 * MyHope 请求数据结构（兼容 myhope 服务端 Zod schema）。
 */
data class MyHopeRequest(
    val v: String = "17",
    val name: String,
    val method: String,
    val endpoint: String,
    val params: List<MyHopeParam> = emptyList(),
    val headers: List<MyHopeHeader> = emptyList(),
    val body: MyHopeBody = MyHopeBody(),
    val auth: MyHopeAuth = MyHopeAuth(),
    val preRequestScript: String = "",
    val testScript: String = "",
    val requestVariables: List<MyHopeVariable> = emptyList(),
    val responses: Map<String, Any> = emptyMap()
)

data class MyHopeParam(
    val key: String,
    val value: String = "",
    val active: Boolean = true
)

data class MyHopeHeader(
    val key: String,
    val value: String,
    val active: Boolean = true
)

data class MyHopeBody(
    val contentType: String? = null,
    val body: Any? = null
)

data class MyHopeAuth(
    val authType: String = "inherit",
    val authActive: Boolean = true
)

data class MyHopeVariable(
    val key: String,
    val value: String = ""
)

/**
 * 同步持久化数据：仅存 localHash + srvHash（本地 hash 判断同步状态）。
 * 插件本地不缓存服务端 id（同一请求数据可能推送到多个集合，缓存单个 id 无意义）。
 * 持久化格式： "localHash,srvHash" / "localHash"。
 */
data class SyncPersistData(
    val localHash: Int = 0,
    val srvHash: Int = 0
) {
    companion object {
        /**
         * 从持久化字符串解析，兼容 "localHash,srvHash" / "localHash" 两种格式。
         */
        fun parse(value: String): SyncPersistData {
            val localHash = value.substringBefore(",").toIntOrNull() ?: 0
            val srvHash = value.substringAfter(",", "0").toIntOrNull() ?: 0
            return SyncPersistData(localHash, srvHash)
        }
    }
}

/**
 * 端点同步状态
 */
enum class SyncStatus {
    /** 未同步到服务端 */
    UNSYNCED,
    /** 已同步，本地内容与上次同步时一致 */
    SYNCED,
    /** 已覆盖更新（本地代码在同步后发生过修改） */
    MODIFIED
}

/**
 * 同步结果（Phase 5 编排将使用，字段结构暂保留）。
 */
data class SyncResult(
    val total: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val collectionsCreated: Int = 0,
    val requestsCreated: Int = 0,
    val requestsSkipped: Int = 0,
    val requestsUpdated: Int = 0,
    val requestsMerged: Int = 0,
    val errors: List<String> = emptyList(),
    val prCreated: Boolean = false,
    val prMessage: String? = null
) {
    val isSuccess: Boolean get() = failed == 0
}

// ====================================================================
//  LogLevel for debug logging control
// ====================================================================

/**
 * 插件日志级别，在设置中可配置。
 * 控制 `log.info()`、`System.err.println` 等调试输出是否显示。
 */
enum class LogLevel(val label: String, val labelZh: String) {
    DEBUG("DEBUG", "调试"),
    INFO("INFO", "信息"),
    WARN("WARN", "警告"),
    ERROR("ERROR", "错误"),
    OFF("OFF", "关闭");

    companion object {
        fun fromId(id: String): LogLevel = entries.find { it.name == id } ?: INFO
    }

    /** 是否允许输出指定级别的日志 */
    fun allows(level: LogLevel): Boolean = ordinal <= level.ordinal
}

// ====================================================================
//  Sync status helper functions
// ====================================================================

/**
 * 计算端点在持久化存储中的唯一键。
 * 用于匹配本地端点 ↔ 存储的 hash ↔ 服务端请求标题。
 */
fun computeEndpointKey(
    endpoint: SpringEndpoint,
    group: ControllerGroup
): String = "${group.controllerQualifiedName}:${endpoint.httpMethod.name}:${endpoint.path}"

/**
 * 计算端点的内容 hash，用于检测本地代码是否修改。
 * 仅 hash 影响实际同步结果的字段（方法、路径、参数）。
 */
fun computeEndpointHash(endpoint: SpringEndpoint): Int {
    var hash = endpoint.httpMethod.ordinal
    hash = 31 * hash + endpoint.path.hashCode()
    hash = 31 * hash + endpoint.fullPath.hashCode()
    val sortedParams = endpoint.parameters.sortedWith(
        compareBy({ it.source.ordinal }, { it.name })
    )
    for (param in sortedParams) {
        hash = 31 * hash + param.name.hashCode()
        hash = 31 * hash + param.type.hashCode()
        hash = 31 * hash + param.source.ordinal
        hash = 31 * hash + (if (param.required) 1 else 0)
    }
    return hash
}

// ====================================================================
//  myhope 服务端数据契约（面向 myhope-pr-server）
// ====================================================================

/**
 * 快照条目类型：collection / request。
 */
enum class ItemType {
    COLLECTION, REQUEST;

    fun asStr(): String = when (this) {
        COLLECTION -> "collection"
        REQUEST -> "request"
    }
}

/**
 * 快照 items 元素（对应 myhope 服务端数据结构规范 §1.1）。
 *
 * 序列化要求：JSON 字段名使用 snake_case（parent_id / pre_request_script /
 * test_script / request_variables），用 @SerializedName 标注。
 * 注意：快照 GET 的 data 是 items 数组（无外壳），元素即此结构。
 * 写入只走增量 push_oplog（op payload 不含 id/parent_id/type/prev_entity_id/next_entity_id 等保留字段）。
 *
 * id 由客户端确定性生成（如 hc_xxx / hr_xxx）。
 */
data class SnapshotItem(
    val id: String,                    // 客户端确定性生成，如 hc_xxx / hr_xxx
    @SerializedName("parent_id")
    val parentId: String?,             // 父集合 id；null=根级。JSON 字段名 parent_id
    val type: ItemType,                // collection / request
    val title: String,
    val method: String? = null,        // request 专用
    val endpoint: String? = null,      // request 专用
    val params: List<MyHopeParam> = emptyList(),
    val headers: List<MyHopeHeader> = emptyList(),
    val auth: MyHopeAuth? = null,
    val body: MyHopeBody? = null,
    @SerializedName("pre_request_script")
    val preRequestScript: String? = null,   // JSON: pre_request_script
    @SerializedName("test_script")
    val testScript: String? = null,         // JSON: test_script
    val description: String? = null,
    @SerializedName("request_variables")
    val requestVariables: List<MyHopeVariable> = emptyList()  // JSON: request_variables
)

/**
 * 工作区信息。
 */
data class WorkspaceInfo(
    val id: String,
    val name: String,
    val role: String? = null,
    @SerializedName("is_default")
    val isDefault: Boolean = false
)

/**
 * 分支信息。
 */
data class BranchInfo(
    val id: String,
    val name: String,
    val visibility: String? = null,
    @SerializedName("is_primary")
    val isPrimary: Boolean = false,
    val description: String? = null,
    @SerializedName("head_commit_version")
    val headCommitVersion: Int? = null
)

/**
 * GET /branches/oplog/head 响应。
 */
data class OplogHeadInfo(
    @SerializedName("branch_id")
    val branchId: String,
    @SerializedName("workspace_id")
    val workspaceId: String,
    @SerializedName("current_seq")
    val currentSeq: Int,
    @SerializedName("head_commit_version")
    val headCommitVersion: Int,
    @SerializedName("head_tree_hash")
    val headTreeHash: String?
)

/**
 * GET /protection-rules/by-branch 响应。
 */
data class ProtectionInfo(
    @SerializedName("branch_id")
    val branchId: String,
    @SerializedName("is_protected")
    val isProtected: Boolean = false,
    @SerializedName("require_approvals")
    val requireApprovals: Int = 1,
    @SerializedName("require_approval_from")
    val requireApprovalFrom: String = "any",
    @SerializedName("allow_admin_bypass")
    val allowAdminBypass: Boolean = true,
    @SerializedName("dismiss_stale_approvals")
    val dismissStaleApprovals: Boolean = false,
    @SerializedName("allow_self_approval")
    val allowSelfApproval: Boolean = false,
    @SerializedName("allow_auto_merge")
    val allowAutoMerge: Boolean = true   // 注意：无规则默认 true（与 auto_publish 语义一致）
)

/**
 * Pull Request 信息（GET /pull-requests 列表项 / POST 创建响应）。
 * 状态枚举（服务端）：draft / open / approved / changes_requested / merged / closed。
 */
data class PullRequestInfo(
    val id: String,
    @SerializedName("access_instance_id")
    val accessInstanceId: String? = null,
    @SerializedName("user_app_id")
    val userAppId: String? = null,
    @SerializedName("branch_id")
    val branchId: String? = null,
    @SerializedName("workspace_id")
    val workspaceId: String? = null,
    @SerializedName("source_branch_id")
    val sourceBranchId: String? = null,
    @SerializedName("target_branch_id")
    val targetBranchId: String? = null,
    @SerializedName("source_branch_name")
    val sourceBranchName: String? = null,
    @SerializedName("target_branch_name")
    val targetBranchName: String? = null,
    val title: String? = null,
    val description: String? = null,
    val status: String = "draft",
    @SerializedName("merge_status")
    val mergeStatus: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null
) {
    /** 是否"进行中"（未 merged / closed）。 */
    val isInProgress: Boolean
        get() = status != "merged" && status != "closed"
}

/**
 * GET /pull-requests 列表响应。
 */
data class PullRequestListResult(
    val items: List<PullRequestInfo> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerializedName("per_page")
    val perPage: Int = 20,
    @SerializedName("total_pages")
    val totalPages: Int = 1
)

/**
 * PUT /bind-myhope 响应。
 */
data class BindingInfo(
    val ok: Boolean = false,
    @SerializedName("access_instance_id")
    val accessInstanceId: String? = null,
    @SerializedName("user_app_id")
    val userAppId: String? = null
)

/**
 * GET /auth/token-info 中的 myhope_binding 对象（绑定信息）。
 */
data class MyHopeBinding(
    @SerializedName("myhope_instance_id")
    val instanceId: String? = null,
    @SerializedName("myhope_user_id")
    val userId: String? = null,
    @SerializedName("myhope_user_name")
    val userName: String? = null
)

/**
 * GET /auth/token-info 响应——字段以后端返回为准。
 *
 * 注意：服务端**不下发** `valid` 字段，Gson 缺失时恒为 false；绑定有效性
 * 以 **HTTP 200** 判断（token-info 中间件/handler 已强校验 token+instance 绑定）。
 * 请勿使用 [valid] 作为有效性依据。
 */
data class TokenInfo(
    val valid: Boolean = false,
    @SerializedName("access_instance_id")
    val accessInstanceId: String? = null,
    @SerializedName("user_app_id")
    val userAppId: String? = null,
    @SerializedName("myhope_binding")
    val myhopeBinding: MyHopeBinding? = null,
    @SerializedName("expires_at")
    val expiresAt: String? = null
)

/**
 * push_oplog 响应 / 409 冲突解析。
 */
data class OplogPushResult(
    val accepted: Boolean,
    @SerializedName("server_max_seq")
    val serverMaxSeq: Int? = null,     // 冲突时=当前水位，作为新 base_seq
    @SerializedName("commit_version")
    val commitVersion: Int? = null,
    val reason: String? = null         // "occ_conflict" 等
)

/**
 * 确定性实体 id 生成（local-first，跨扫描稳定）。
 * 服务端 validate_client_id 允许字符：数字/字母/下划线/连字符/等号。
 * 前缀：hc_=集合、hr_=请求。
 *
 * 集合用 `hc_` + controllerQualifiedName 的 hash；
 * 请求用 `hr_` + "HTTP_METHOD:fullPath" 的 hash。
 */
fun generateEntityId(prefix: String, key: String): String {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
    return "${prefix}_$hash"
}
