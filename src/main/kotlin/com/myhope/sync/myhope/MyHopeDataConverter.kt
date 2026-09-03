package com.myhope.sync.myhope

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.myhope.sync.model.*
import java.lang.reflect.Type

/**
 * Spring 端点数据到 MyHope 请求数据格式的转换器。
 *
 * 负责将 [SpringEndpoint] 转换为 [MyHopeRequest]，
 * 并序列化为 JSON 字符串以用于 GraphQL 变更操作。
 */
class MyHopeDataConverter {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    companion object {
        /**
         * 快照 items 序列化专用 Gson。
         * 紧凑输出（无 pretty printing）+ 保留 null（与旧插件 1.2.4 教训一致，
         * 服务端 body.contentType / auth 允许 null）。
         * [ItemType] 序列化为小写字符串（collection / request），与 myhope 服务端契约一致。
         */
        private val snapshotGson: Gson = GsonBuilder()
            .serializeNulls()
            .registerTypeAdapter(ItemType::class.java, ItemTypeJsonAdapter())
            .create()

        /**
         * 从持久化字符串中解析本地 hash。
         * 格式： "localHash,srvHash" → localHash；"localHash" → localHash
         */
        fun parseLocalHash(value: String): Int {
            return value.substringBefore(",").toIntOrNull() ?: 0
        }

        /**
         * 从持久化字符串中解析服务端请求 hash。
         * 格式： "localHash,srvHash" → srvHash
         */
        fun parseSrvReqHash(value: String): Int {
            return value.substringAfter(",", "0").toIntOrNull() ?: 0
        }

        /**
         * 构建持久化值字符串。
         * 格式： "localHash,srvHash"
         */
        fun buildSyncValue(localHash: Int, srvHash: Int): String = "$localHash,$srvHash"

        /**
         * 根据参数类型名返回 HTTP 请求中合理的占位值（字符串形式）。
         * 用于路径变量、query param 等场景。
         */
        @JvmStatic
        fun placeholderValueForType(type: String): String = when {
            type in listOf(
                "int", "Integer", "java.lang.Integer",
                "long", "Long", "java.lang.Long",
                "short", "Short", "java.lang.Short",
                "byte", "Byte", "java.lang.Byte"
            ) -> "0"
            type in listOf(
                "double", "Double", "java.lang.Double",
                "float", "Float", "java.lang.Float"
            ) -> "0.0"
            type in listOf("boolean", "Boolean", "java.lang.Boolean") -> "true"
            type in listOf("java.util.UUID") -> "00000000-0000-0000-0000-000000000000"
            type in listOf(
                "String", "java.lang.String",
                "char", "Character", "java.lang.Character",
                "java.time.LocalDate", "java.time.LocalDateTime",
                "java.time.LocalTime", "java.util.Date"
            ) -> ""
            type.startsWith("List<") || type.startsWith("Set<") || type.startsWith("Collection<") -> ""
            else -> ""
        }
    }

    /**
     * 将单个 [SpringEndpoint] 转换为 [MyHopeRequest]。
     *
     * 转换包括：
     * - HTTP 方法和路径 → MyHope 请求方法和端点
     * - 路径参数（如 `/users/{id}`）→ MyHope 参数
     * - 查询参数 → MyHope 参数
     * - 请求头参数 → MyHope 请求头
     * - @RequestBody → JSON 请求体模板
     * - Content-Type 请求头（根据 consumes 或请求体存在自动设置）
     */
    fun toMyHopeRequest(endpoint: SpringEndpoint): MyHopeRequest {
        val params = mutableListOf<MyHopeParam>()
        val headers = mutableListOf<MyHopeHeader>()

        // 判断 Content-Type 是否为 form-data 或 urlencoded
        val consumesLower = endpoint.consumes.joinToString(" ").lowercase()
        val isFormData = "form-data" in consumesLower || "multipart" in consumesLower
        val isFormUrlEncoded = "urlencoded" in consumesLower || "x-www-form-urlencoded" in consumesLower
        val isFormLike = isFormData || isFormUrlEncoded

        // 收集 QUERY 参数中需要转为 form body 的字段（当 consumes 为 form-data/urlencoded 时）
        val formBodyParams = mutableListOf<EndpointParameter>()

        // ---- 路径参数 ----
        // 从 fullPath 中提取 {paramName} 模式的路径变量
        val pathParamNames = extractPathParams(endpoint.fullPath).toSet()
        for (paramName in pathParamNames) {
            val endpointParam = endpoint.parameters.find {
                it.name == paramName && it.source == ParamSource.PATH
            }
            val value = when {
                endpointParam?.defaultValue != null -> endpointParam.defaultValue!!
                endpointParam != null -> placeholderValueForType(endpointParam.type)
                else -> paramName // 无对应参数 → 用参数名自身
            }
            params.add(
                MyHopeParam(
                    key = paramName,
                    value = value,
                    active = true
                )
            )
        }

        // ---- 查询参数 ----
        // 当 consumes 为 form-data/urlencoded 时，@RequestParam 参数是表单字段，不放入 URL 查询串
        // MultipartFile 无论是否有 consumes 都转入 form body
        val isBodyMethod = endpoint.httpMethod.name in listOf("POST", "PUT", "PATCH")
        for (ep in endpoint.parameters.filter { it.source == ParamSource.QUERY }) {
            // 避免与路径变量重名
            if (ep.name !in pathParamNames) {
                if (isFormLike || ep.isMultipartFile) {
                    formBodyParams.add(ep)
                } else {
                    if (ep.objectFields.isNotEmpty()) {
                        // 复杂对象（无 @RequestBody）展开为多个字段参数
                        for (field in ep.objectFields) {
                            params.add(MyHopeParam(key = field, value = "", active = true))
                        }
                        // POST/PUT/PATCH 中无 @RequestBody 的 POJO 也支持 form 表单传参
                        if (isBodyMethod) {
                            ep.objectFields.forEach { field ->
                                formBodyParams.add(
                                    EndpointParameter(
                                        name = field, type = "String",
                                        source = ParamSource.QUERY, required = false
                                    )
                                )
                            }
                        }
                    } else {
                        params.add(
                            MyHopeParam(
                                key = ep.name,
                                value = ep.defaultValue ?: placeholderValueForType(ep.type),
                                active = true
                            )
                        )
                    }
                }
            }
        }

        // ---- 请求头参数 ----
        for (ep in endpoint.parameters.filter { it.source == ParamSource.HEADER }) {
            headers.add(
                MyHopeHeader(
                    key = ep.name,
                    value = ep.defaultValue ?: "",
                    active = true
                )
            )
        }

        // ---- Content-Type 请求头 ----
        val hasRequestBody = endpoint.parameters.any { it.source == ParamSource.BODY } || formBodyParams.isNotEmpty()
        if (endpoint.consumes.isNotEmpty()) {
            // 使用 @RequestMapping(consumes = ...) 指定的 Content-Type
            headers.add(
                MyHopeHeader(
                    key = "Content-Type",
                    value = endpoint.consumes.first(),
                    active = true
                )
            )
        } else if (formBodyParams.isNotEmpty()) {
            // form 表单参数 → 默认 application/x-www-form-urlencoded（含 multipart file 时改为 multipart/form-data）
            val ct = if (formBodyParams.any { it.isMultipartFile }) "multipart/form-data" else "application/x-www-form-urlencoded"
            headers.add(
                MyHopeHeader(
                    key = "Content-Type",
                    value = ct,
                    active = true
                )
            )
        } else if (endpoint.parameters.any { it.source == ParamSource.BODY }) {
            // @RequestBody 但未指定 consumes → 默认 application/json
            headers.add(
                MyHopeHeader(
                    key = "Content-Type",
                    value = "application/json",
                    active = true
                )
            )
        }

        // ---- 请求体 ----
        val body = when {
            formBodyParams.isNotEmpty() -> {
                val isMultipart = formBodyParams.any { it.isMultipartFile }
                val ct = endpoint.consumes.firstOrNull()
                    ?: if (isMultipart) "multipart/form-data"
                    else "application/x-www-form-urlencoded"
                // 当 multipart 模式下同时有 @RequestBody 时，把 JSON 模板也作为 text 字段加入 body
                if (isMultipart) {
                    val bodyParams = endpoint.parameters.filter { it.source == ParamSource.BODY }
                    for (bp in bodyParams) {
                        val jsonValue = bp.bodyJsonTemplate ?: "{}"
                        formBodyParams.add(
                            EndpointParameter(
                                name = bp.name, type = "String",
                                source = ParamSource.QUERY, required = false,
                                defaultValue = jsonValue
                            )
                        )
                    }
                }
                buildFormBody(formBodyParams, ct)
            }
            hasRequestBody -> buildRequestBodyTemplate(endpoint)
            else -> MyHopeBody()
        }

        val displayName = endpoint.description?.takeIf { it.isNotBlank() }
            ?: "${endpoint.httpMethod.name} ${endpoint.fullPath}"
        return MyHopeRequest(
            name = displayName,
            method = endpoint.httpMethod.name,
            endpoint = endpoint.fullPath,
            params = params,
            headers = headers,
            body = body
        )
    }

    /**
     * 将 [MyHopeRequest] 序列化为 JSON 字符串，
     * 可用作 GraphQL 变更操作的请求体的一部分。
     */
    fun toRequestRequestBody(request: MyHopeRequest): String {
        return gson.toJson(request)
    }

    // ======================== 快照 items 转换（Phase 4） ========================

    /**
     * 将 [ControllerGroup] 列表转换为 myhope 快照 items 扁平数组。
     *
     * - 每个 group → 1 个 collection item
     * - 每个 endpoint → 1 个 request item
     *
     * [createSubDirectories] 为 true 时（默认，与设置项一致），在 Controller 集合之上
     * 按模块名（project.moduleName）增加一层模块集合，形成
     * 「模块集合(parent=目标) → Controller 集合(parent=模块集合) → 请求」层级；
     * 同名模块只创建一个模块集合（共享）。moduleName 为空的 Controller 不建模块层
     * （其 Controller 集合 parent=目标）。为 false 时 Controller 集合直接 parent=目标。
     *
     * [targetParentId] 为同步目标的父级集合 id（用户在选择弹窗中选中的目标集合）；
     * 为空时模块/Controller 集合挂根级（parent=null），与默认行为一致。
     *
     * request 的 params/headers/auth/body 直接复用 [toMyHopeRequest] 的生成逻辑，
     * 不重复实现参数/body 转换。
     */
    fun toSnapshotItems(
        groups: List<ControllerGroup>,
        createSubDirectories: Boolean = true,
        targetParentId: String? = null
    ): List<SnapshotItem> {
        val items = mutableListOf<SnapshotItem>()

        // id 生成带目标目录后缀：服务端 id 全局唯一，同一 method+url 推根级和目录 A 必须生成
        // 两个不同 id 实体；加 `@目标目录id`（根级统一 "@root"）后缀保证跨目录唯一，又保证推
        // 同一目录时 id 稳定可复用（同目录重推命中同一实体）。
        val idSuffix = targetParentId?.let { "@$it" } ?: "@root"

        // 勾选时：先按模块名去重收集模块集合（模块集合 parent=目标，先于其下的 Controller 集合出现）
        val moduleIdByModuleName = LinkedHashMap<String, String>()
        if (createSubDirectories) {
            for (group in groups) {
                if (group.moduleName.isNotBlank() && group.moduleName !in moduleIdByModuleName) {
                    moduleIdByModuleName[group.moduleName] =
                        generateEntityId("hc", "module:${group.moduleName}$idSuffix")
                }
            }
            for ((moduleName, moduleId) in moduleIdByModuleName) {
                items.add(
                    SnapshotItem(
                        id = moduleId,
                        parentId = targetParentId,
                        type = ItemType.COLLECTION,
                        title = sanitizeTitle(moduleName)
                    )
                )
            }
        }

        for (group in groups) {
            val collectionId = generateEntityId("hc", "${group.controllerQualifiedName}$idSuffix")
            val collectionTitle = sanitizeTitle(
                when {
                    !group.apiTag.isNullOrBlank() -> group.apiTag
                    !group.classLevelPath.isNullOrBlank() -> group.classLevelPath
                    else -> group.controllerClassName
                }
            )
            // 模块名非空且开启子目录 → 挂在模块集合下；否则挂到目标（目标为空则根级）
            val controllerParent = if (createSubDirectories && group.moduleName.isNotBlank()) {
                moduleIdByModuleName[group.moduleName]
            } else targetParentId
            items.add(
                SnapshotItem(
                    id = collectionId,
                    parentId = controllerParent,
                    type = ItemType.COLLECTION,
                    title = collectionTitle
                )
            )

            for (endpoint in group.endpoints) {
                // 复用现有请求转换逻辑，避免重复实现参数/body 转换
                val request = toMyHopeRequest(endpoint)
                items.add(
                    SnapshotItem(
                        id = generateEntityId("hr", "${endpoint.httpMethod.name}:${endpoint.fullPath}$idSuffix"),
                        parentId = collectionId,
                        type = ItemType.REQUEST,
                        title = endpoint.description?.takeIf { it.isNotBlank() } ?: endpoint.fullPath,
                        method = endpoint.httpMethod.name,
                        endpoint = endpoint.fullPath,
                        params = request.params,
                        headers = request.headers,
                        auth = request.auth,
                        body = request.body,
                        preRequestScript = null,
                        testScript = null,
                        description = endpoint.description,
                        requestVariables = emptyList()
                    )
                )
            }
        }
        return items
    }

    /**
     * 本地 items 与服务端现有 items 对比，生成最小化 oplog op 列表。
     *
     * 判断 create/update **不看 id**：
     * - **request**：在**同一 parent 集合**（按集合 title 归并）内按 `method + endpoint(fullPath)` 匹配
     *   → 命中即 update（用服务端实体的真实 id 发 update op；陈旧实体同 method+url 但 id 不同也走 update，不新建）；
     *   → 未命中 create（用本地 id）。不同集合/目录下的同 method+url **不互相抢占**，各自独立判定。
     * - **collection**（模块集合 / controller 集合）：不参与 method+url 匹配，按 **title** 在目标目录树内
     *   匹配 → 命中即 update（用服务端 id）、未命中 create（用本地 id）。
     *
     * 匹配范围限定在目标目录树内：根级（[targetParentId] 为 null）时服务端快照全量参与匹配；否则仅
     * 限定在 [targetParentId] 为根的子树内。
     * 父集合先于子集合/请求处理（父在前），保证父集合 op 先落库。
     * 顶层的 op 结构与旧版一致：create/update 各 op 结构（op_meta、payload 白名单、created_at=millis）；
     * **不生成 move_entity / delete_entity**（单向推送语义）。update 顶层 parent_id 一律 null。
     *
     * @param localItems  本地 items（toSnapshotItems 产物，id 带目标目录后缀）
     * @param serverItems 服务端快照 items（diff 基准；空 = 服务端无 items）
     * @param baseSeq     上次成功推送的 seq（当前服务端水位，来自 getOplogHead current_seq）
     * @param branchId    当前分支 id（op 顶层 branch_id 用）
     * @param workspaceId 当前工作空间 id（op 顶层 workspace_id 必带）
     * @param authorId    操作人（settings.myhopeUserId）
     * @param targetParentId 同步目标父集合 id；null=根级（匹配范围限定到目标目录树）
     * @return op 列表（Map 形式，seq 从 baseSeq+1 连续递增）
     */
    fun buildOps(
        localItems: List<SnapshotItem>,
        serverItems: List<SnapshotItem>,
        baseSeq: Int,
        branchId: String,
        workspaceId: String,
        authorId: String,
        targetParentId: String? = null
    ): List<Map<String, Any?>> {
        val serverById = serverItems.associateBy { it.id }

        // 服务端 items 限定到目标目录树内：targetParentId==null（根级）→ 全量参与匹配（整棵快照树都属于根级）；
        // 否则仅 parentId 链向上可达 targetParentId 的子树
        val serverTargetItems = serverItems.filter { isUnderTargetParent(it, targetParentId, serverById) }

        // 拓扑排序：保证父集合 op 先于子集合/请求 op
        val localById = localItems.associateBy { it.id }
        val ordered = mutableListOf<SnapshotItem>()
        val visited = mutableSetOf<String>()
        fun visit(item: SnapshotItem) {
            if (item.id in visited) return
            visited.add(item.id)
            val parentId = item.parentId
            if (parentId != null) {
                val parent = localById[parentId]
                if (parent != null && parent.type == ItemType.COLLECTION) visit(parent)
            }
            ordered.add(item)
        }
        localItems.forEach { visit(it) }

        // 服务端 target 目录树内匹配索引（不看 id）：
        // - collection item → 键 title（服务端集合 id 从该索引取）
        // - request item → 按「parent 集合 title」分组，组内键 "method:endpoint"。
        //   匹配限定在同一 parent 集合内（按 title 归并）：推到指定目录的请求只与同目录下已有请求
        //   判 update，不同目录的同 method+url 不互相抢占 → 新集合下的请求必然 create 进该集合。
        val serverRequestByParentTitle = serverTargetItems
            .filter { it.type == ItemType.REQUEST }
            .groupBy { req -> serverTargetItems
                .firstOrNull { it.type == ItemType.COLLECTION && it.id == req.parentId }?.title
                ?: ""
            }
            .mapValues { (_, reqs) ->
                reqs.associateBy { computeMatchKey(it.method, it.endpoint) }
            }
        val serverCollectionByTitle = serverTargetItems
            .filter { it.type == ItemType.COLLECTION }
            .associateBy { it.title }

        val ops = mutableListOf<Map<String, Any?>>()
        var seq = baseSeq
        // 本地集合 id → 服务端匹配到的实体 id 映射：父集合在服务端已存在（update 用服务端 id）时，
        // 其下新建子集合/请求的 parent 必须指向服务端父 id，否则指向不存在的本地 id。
        val localIdToServerId = mutableMapOf<String, String>()
        fun resolveParent(localParentId: String?): String? =
            localParentId?.let { localIdToServerId[it] ?: it }

        fun append(opType: String, entityId: String, parentId: String?, payload: Map<String, Any?>) {
            seq++
            ops.add(
                mutableMapOf(
                    "seq" to seq,
                    "branch_id" to branchId,
                    "op_type" to opType,
                    "entity_id" to entityId,
                    "parent_id" to parentId,
                    "prev_entity_id" to null,
                    "payload" to payload,
                    "author_id" to authorId,
                    "created_at" to System.currentTimeMillis(),
                    "workspace_id" to workspaceId
                )
            )
        }

        for (item in ordered) {
            val isRequest = item.type == ItemType.REQUEST
            if (isRequest) {
                // request：在同一 parent 集合内按 method+endpoint 匹配（绝不比较 id）。
                // 本地 parentId → 本地集合 title → 服务端同 title 集合下的请求索引；
                // 同目录下已存在同 method+url → update，否则 create（不同目录不互相抢占）。
                val localParentTitle = item.parentId?.let { pid -> localById[pid]?.title }
                val serverItem = localParentTitle?.let { title ->
                    serverRequestByParentTitle[title]?.get(computeMatchKey(item.method, item.endpoint))
                }
                if (serverItem == null) {
                    // 服务端同 parent 集合内无同 method+url → create（用本地 id，parent 映射到服务端父 id）
                    append("create_request", item.id, resolveParent(item.parentId), buildCreateRequestPayload(item))
                } else {
                    // 服务端已存在同 method+url → update（用服务端实体真实 id；
                    // 陈旧实体同 method+url 但 id 不同也走这里，不新建、不删）
                    for ((opType, payload) in buildRequestUpdateOps(item, serverItem)) {
                        append(opType, serverItem.id, null, payload)
                    }
                }
            } else {
                // collection（模块/controller 集合）：按 title 匹配
                val serverItem = serverCollectionByTitle[item.title]
                if (serverItem == null) {
                    // 目标目录下无同名集合 → create（用本地 id，parent 映射到服务端父 id）
                    localIdToServerId[item.id] = item.id
                    append(
                        opType = "create_collection",
                        entityId = item.id,
                        parentId = resolveParent(item.parentId),
                        payload = mapOf("title" to item.title, "description" to item.description)
                    )
                } else {
                    // 目标目录下已有同名集合 → 复用（update，用服务端 id），并记录本地→服务端 id 映射
                    localIdToServerId[item.id] = serverItem.id
                    for ((opType, payload) in buildCollectionUpdateOps(item, serverItem)) {
                        append(opType, serverItem.id, null, payload)
                    }
                }
            }
        }
        return ops
    }

    /**
     * 服务端 [serverItems] 中 target 目录树内的 Request item 匹配索引：
     * `parent 集合 title → (method:endpoint → item)`。
     *
     * 与 [buildOps] 的增量 diff 口径一致（request 只在**同一 parent 集合**内按 method+endpoint 匹配，
     * 不同集合下的同 method+endpoint 不互相抢占）。[targetParentId] 为 null（根级）时取全量，
     * 否则仅取该目录子树。匹配仅看 method+endpoint，不看 id。
     */
    fun buildServerRequestIndexByParentTitle(
        serverItems: List<SnapshotItem>,
        targetParentId: String? = null
    ): Map<String, Map<String, SnapshotItem>> {
        val serverById = serverItems.associateBy { it.id }
        val targetItems = serverItems.filter { isUnderTargetParent(it, targetParentId, serverById) }
        val collectionTitleById = targetItems
            .filter { it.type == ItemType.COLLECTION }
            .associate { it.id to it.title }
        return targetItems
            .filter { it.type == ItemType.REQUEST }
            .groupBy { req -> collectionTitleById[req.parentId] ?: "" }
            .mapValues { (_, reqs) ->
                reqs.associateBy { computeMatchKey(it.method, it.endpoint) }
            }
    }

    /** 生成 create_request 的 payload（必带 title/method/endpoint + 其余白名单字段可为空/默认）。 */
    private fun buildCreateRequestPayload(item: SnapshotItem): Map<String, Any?> {
        return mapOf(
            "title" to item.title,
            "method" to item.method,
            "endpoint" to item.endpoint,
            "params" to toJsonStructure(item.params),
            "headers" to toJsonStructure(item.headers),
            "auth" to toJsonStructure(item.auth),
            "body" to toJsonStructure(item.body),
            "pre_request_script" to item.preRequestScript,
            "test_script" to item.testScript,
            "description" to item.description,
            "request_variables" to toJsonStructure(item.requestVariables)
        )
    }

    /** 集合字段 diff → update_* op 列表（title / description）。 */
    private fun buildCollectionUpdateOps(local: SnapshotItem, server: SnapshotItem): List<Pair<String, Map<String, Any?>>> {
        val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
        if (local.title != server.title) updates.add("update_title" to mapOf("title" to local.title))
        if (local.description != server.description) updates.add("update_description" to mapOf("description" to local.description))
        return updates
    }

    /** 请求字段 diff → update_* op 列表（每个变更字段一个 op）。 */
    private fun buildRequestUpdateOps(local: SnapshotItem, server: SnapshotItem): List<Pair<String, Map<String, Any?>>> {
        val updates = mutableListOf<Pair<String, Map<String, Any?>>>()
        if (local.title != server.title) updates.add("update_title" to mapOf("title" to local.title))
        if (local.method != server.method) updates.add("update_method" to mapOf("method" to local.method))
        if (local.endpoint != server.endpoint) updates.add("update_endpoint" to mapOf("endpoint" to local.endpoint))
        if (toJsonStructure(local.params) != toJsonStructure(server.params))
            updates.add("update_params" to mapOf("params" to toJsonStructure(local.params)))
        if (toJsonStructure(local.headers) != toJsonStructure(server.headers))
            updates.add("update_headers" to mapOf("headers" to toJsonStructure(local.headers)))
        if (toJsonStructure(local.body) != toJsonStructure(server.body))
            updates.add("update_body" to mapOf("body" to toJsonStructure(local.body)))
        if (toJsonStructure(local.auth) != toJsonStructure(server.auth))
            updates.add("update_auth" to mapOf("auth" to toJsonStructure(local.auth)))
        if (local.description != server.description) updates.add("update_description" to mapOf("description" to local.description))
        if (toJsonStructure(local.requestVariables) != toJsonStructure(server.requestVariables))
            updates.add("update_request_variables" to mapOf("request_variables" to toJsonStructure(local.requestVariables)))
        return updates
    }

    /**
     * 替换标题中 myhope/文件系统不友好的字符为下划线。
     */
    fun sanitizeTitle(name: String): String {
        return name.replace(Regex("[<>:\"/\\\\|?*\\[\\]]"), "_")
    }

    // ======================== 辅助方法 ========================

    /**
     * 将任意嵌套模型对象（如 [MyHopeParam]/[MyHopeBody]/[MyHopeAuth] 列表）转换为纯 Map/List 结构。
     * 复用 [snapshotGson] 序列化保证与服务端已接受的快照契约一致；
     * 且只保留模型自身字段（不含 id/parent_id/type/prev_entity_id/next_entity_id 等保留字段）。
     */
    private fun toJsonStructure(value: Any?): Any? {
        if (value == null) return null
        return snapshotGson.fromJson(snapshotGson.toJson(value), Any::class.java)
    }

    /**
     * 匹配键：request item 用 "method:endpoint"（method+url 判断新增/更新，不看 id）；
     * collection item 不在本函数匹配（走 title 索引）。
     */
    private fun computeMatchKey(method: String?, endpoint: String?): String = "$method:$endpoint"

    /**
     * 判断 item 是否位于以 [targetParentId] 为根的目标目录树内。
     *
     * - [targetParentId] 为 null（根级目标）→ 恒 true：根级=整个 branch 单元，服务端快照的
     *   整棵树（含嵌套的 controller 集合、request）都属于根级范围，全量参与匹配；
     * - 否则沿 parentId 链向上遍历，能到达 [targetParentId] 即属于该目录树。
     */
    private fun isUnderTargetParent(
        item: SnapshotItem,
        targetParentId: String?,
        serverById: Map<String, SnapshotItem>
    ): Boolean {
        if (targetParentId == null) {
            // 根级：不按顶层过滤，全量匹配（嵌套实体也属于根级之下）
            return true
        }
        var currentParentId = item.parentId
        while (!currentParentId.isNullOrBlank()) {
            if (currentParentId == targetParentId) return true
            currentParentId = serverById[currentParentId]?.parentId
        }
        return false
    }

    /**
     * 从路径模板中提取路径变量名。
     *
     * 例如 `/users/{userId}/posts/{postId}` → `["userId", "postId"]`
     */
    private fun extractPathParams(path: String): List<String> {
        val pattern = Regex("\\{([^}]+)}")
        return pattern.findAll(path).map { it.groupValues[1] }.toList()
    }

    /**
     * 为包含 [@RequestBody] 的端点构建 JSON 请求体模板。
     *
     * 优先使用解析器递归生成的 [EndpointParameter.bodyJsonTemplate]（含类型推导的占位值），
     * 若不可用则根据类型名回退为简单占位模板。
     */
    private fun buildRequestBodyTemplate(endpoint: SpringEndpoint): MyHopeBody {
        val bodyParams = endpoint.parameters.filter { it.source == ParamSource.BODY }

        if (bodyParams.isEmpty()) {
            return MyHopeBody()
        }

        val bodyParam = bodyParams.first()

        // 优先使用 PSI 递归解析的 JSON 模板
        if (bodyParam.bodyJsonTemplate != null) {
            return MyHopeBody(
                contentType = "application/json",
                body = bodyParam.bodyJsonTemplate
            )
        }

        // 回退：基于类型名简单推断
        val typeHint = bodyParam.type
        val bodyContent = when {
            typeHint in listOf("String", "java.lang.String") -> "\"\""
            typeHint in listOf("int", "Integer", "java.lang.Integer") -> "0"
            typeHint in listOf("long", "Long", "java.lang.Long") -> "0"
            typeHint in listOf("boolean", "Boolean", "java.lang.Boolean") -> "false"
            typeHint in listOf("double", "Double", "java.lang.Double") -> "0.0"
            typeHint in listOf("float", "Float", "java.lang.Float") -> "0.0"
            typeHint.startsWith("List<") || typeHint.startsWith("Set<") || typeHint.startsWith("Collection<") -> "[]"
            typeHint.startsWith("Map<") -> "{}"
            else -> "{\n  \"${bodyParam.name}\": {}\n}"
        }

        return MyHopeBody(
            contentType = "application/json",
            body = bodyContent
        )
    }

    /**
     * 为 consumes = multipart/form-data 或 application/x-www-form-urlencoded 的端点
     * 构建 form 请求体。
     *
     * - urlencoded → body = "key=value&k2=v2"（字符串）
     * - multipart   → body = List<Map>，Gson 序列化为 JSON 数组
     *                 `[{key, value, type}]` 格式，符合 MyHope Zod schema
     */
    private fun buildFormBody(formParams: List<EndpointParameter>, contentType: String): MyHopeBody {
        val body: Any = if (contentType == "multipart/form-data") {
            // MyHope 的 multipart body 是 FormDataKeyValue 数组
            formParams.map { ep ->
                val value = ep.defaultValue?.takeIf { it.isNotBlank() } ?: ""
                val type = if (ep.isMultipartFile) "file" else "text"
                mapOf(
                    "key" to ep.name,
                    "value" to value,
                    "type" to type
                )
            }
        } else {
            formParams.joinToString("&") { ep ->
                val value = ep.defaultValue?.takeIf { it.isNotBlank() } ?: ""
                "${ep.name}=$value"
            }
        }
        return MyHopeBody(contentType = contentType, body = body)
    }
}

/**
 * [ItemType] 的 JSON 适配器：以小写字符串（asStr）序列化/反序列化。
 *
 * 序列化输出小写 `collection` / `request`（与 myhope 服务端契约一致）；
 * 反序列化兼容大小写（`"collection"`/`"COLLECTION"` 均映射到对应枚举）。
 * 由 [MyHopeDataConverter] 与 [com.myhope.sync.myhope.MyHopeClient] 共用。
 */
class ItemTypeJsonAdapter : JsonSerializer<ItemType>, JsonDeserializer<ItemType> {
    override fun serialize(
        src: ItemType,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement = JsonPrimitive(src.asStr())

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): ItemType {
        val raw = json.asString
        return ItemType.entries.firstOrNull { it.asStr().equals(raw, ignoreCase = true) }
            ?: ItemType.COLLECTION
    }
}
