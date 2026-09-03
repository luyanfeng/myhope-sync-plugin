package com.myhope.sync.settings

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 项目级持久化设置（存于项目 .idea/myhope-sync-project.xml）。
 *
 * 本组件同时兼作"项目级覆盖层"：账号级字段（[serverUrl]/[apiToken]/[instanceId]/
 * [accessInstanceId]/[userAppId]/[myhopeUserId]/[myhopeUserName]/[language]/[logLevel]）
 * 默认为空，空表示"未覆盖"——业务经 [getEffectiveSettings] 回落全局 [AppSettings] 默认值；
 * 非空（显式覆盖）→ 本项目内以本组件的值为准。
 *
 * 除覆盖层外，本组件还承载同步目标（workspace/branch/目标集合/子目录）、同步状态、
 * 扫描缓存、选中项目、表格列设置——按项目隔离，打开新项目时各自独立，互不串用。
 *
 * 不含任何旧数据迁移逻辑（老版本全局 xml 中的项目级字段由 IDE 持久化框架自动忽略，
 * 本组件首次打开取默认值）。不要在[ProjectAppSettings]中实现 getOrCreate* 逻辑——那是
 * [AppSettings]（全局单例）的职责。
 */
@State(
    name = "com.myhope.sync.settings.ProjectAppSettings",
    storages = [Storage("myhope-sync-project.xml")]
)
class ProjectAppSettings(private val project: Project) : PersistentStateComponent<ProjectAppSettings.State> {

    data class State(
        var syncStatusData: String = "",        // JSON: endpointKey → "localHash,srvHash"
        var selectedProjectsData: String = "",  // JSON 数组：用户选中的项目名列表
        var cachedScanJson: String = "",        // JSON: 上次刷新扫描的 groups 序列化数据
        var workspaceId: String = "",           // 目标工作空间
        var branchId: String = "",              // 目标分支
        var branchName: String = "",            // 目标分支名（按名匹配/创建）
        var createSubDirectory: Boolean = true, // 同步时是否按模块名生成子目录
        var targetCollectionId: String = "",    // 上次选择的同步目标集合 id（根级为空）
        var autoPublishPr: Boolean = false,     // 同步成功后是否自动创建 PR（auto-publish）
        var prTargetBranchId: String = "",      // PR 目标分支 id（为空则不自动创建 PR）
        var hiddenColumnIds: String = "",       // 逗号分隔的列索引，如 "4,6"
        var columnWidths: String = "",          // "colIdx:width,..."，如 "3:280,4:160"
        // 注：同步水位 seq 不再本地持久化（oplogBaseSeq 已删除）——每次同步实时从服务端 getOplogHead 获取，保证多实例一致

        // —— 项目级覆盖层（账号级字段，空 = 未覆盖，业务读取时回落全局 AppSettings）——
        var serverUrl: String = "",             // 覆盖全局 serverUrl
        var apiToken: String = "",              // 覆盖全局 apiToken
        var instanceId: String = "",            // 覆盖全局 instanceId（一般不在设置面板显式配置，全局单例生成）
        var accessInstanceId: String = "",      // 覆盖全局 accessInstanceId
        var userAppId: String = "",             // 覆盖全局 userAppId
        var myhopeUserId: String = "",          // 覆盖全局 myhopeUserId
        var myhopeUserName: String = "",        // 覆盖全局 myhopeUserName
        var language: String = "",              // 覆盖全局 language
        var logLevel: String = ""               // 覆盖全局 logLevel
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var syncStatusData: String
        get() = myState.syncStatusData
        set(value) { myState.syncStatusData = value }

    var selectedProjectsData: String
        get() = myState.selectedProjectsData
        set(value) { myState.selectedProjectsData = value }

    var cachedScanJson: String
        get() = myState.cachedScanJson
        set(value) { myState.cachedScanJson = value }

    var workspaceId: String
        get() = myState.workspaceId
        set(value) { myState.workspaceId = value }

    var branchId: String
        get() = myState.branchId
        set(value) { myState.branchId = value }

    var branchName: String
        get() = myState.branchName
        set(value) { myState.branchName = value }

    var createSubDirectory: Boolean
        get() = myState.createSubDirectory
        set(value) { myState.createSubDirectory = value }

    var targetCollectionId: String
        get() = myState.targetCollectionId
        set(value) { myState.targetCollectionId = value }

    var autoPublishPr: Boolean
        get() = myState.autoPublishPr
        set(value) { myState.autoPublishPr = value }

    var prTargetBranchId: String
        get() = myState.prTargetBranchId
        set(value) { myState.prTargetBranchId = value }

    var hiddenColumnIds: String
        get() = myState.hiddenColumnIds
        set(value) { myState.hiddenColumnIds = value }

    var columnWidths: String
        get() = myState.columnWidths
        set(value) { myState.columnWidths = value }

    // —— 项目级覆盖层属性（读写 myState.xxx，空 = 未覆盖）——
    var serverUrl: String
        get() = myState.serverUrl
        set(value) { myState.serverUrl = value }

    var apiToken: String
        get() = myState.apiToken
        set(value) { myState.apiToken = value }

    var instanceId: String
        get() = myState.instanceId
        set(value) { myState.instanceId = value }

    var accessInstanceId: String
        get() = myState.accessInstanceId
        set(value) { myState.accessInstanceId = value }

    var userAppId: String
        get() = myState.userAppId
        set(value) { myState.userAppId = value }

    var myhopeUserId: String
        get() = myState.myhopeUserId
        set(value) { myState.myhopeUserId = value }

    var myhopeUserName: String
        get() = myState.myhopeUserName
        set(value) { myState.myhopeUserName = value }

    var language: String
        get() = myState.language
        set(value) { myState.language = value }

    var logLevel: String
        get() = myState.logLevel
        set(value) { myState.logLevel = value }

    companion object {
        fun getInstance(project: Project): ProjectAppSettings =
            project.getService(ProjectAppSettings::class.java)

        private val syncGson = GsonBuilder().create()

        /**
         * 解析持久化的同步状态映射：endpointKey → "localHash,srvHash"。
         * 兼容旧格式（Int values），自动转换为新格式。
         */
        fun deserializeSyncMap(data: String): Map<String, String> {
            if (data.isBlank()) return emptyMap()
            // 尝试新格式（String 值，如 "12345,67890"）
            val newType = object : TypeToken<Map<String, String>>() {}.type
            val newResult = try {
                syncGson.fromJson<Map<String, String>>(data, newType)
            } catch (_: Exception) { null }
            if (newResult != null) return newResult
            // 兼容旧格式（Int 值，如 12345），转换为 "value,0"
            return try {
                val oldType = object : TypeToken<Map<String, Int>>() {}.type
                val old = syncGson.fromJson<Map<String, Int>>(data, oldType) ?: return emptyMap()
                old.mapValues { "${it.value},0" }
            } catch (_: Exception) { emptyMap() }
        }

        /**
         * 序列化同步状态映射为 JSON 字符串。
         */
        fun serializeSyncMap(map: Map<String, String>): String = syncGson.toJson(map)

        /** 逗号分隔的 project 名 → Set */
        fun deserializeProjects(data: String): Set<String> {
            if (data.isBlank()) return emptySet()
            return data.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }

        /** Set<String> → 逗号分隔 */
        fun serializeProjects(projects: Set<String>): String =
            projects.joinToString(",")
    }

    /** 获取同步状态映射（快捷方式） */
    fun getSyncStatusMap(): Map<String, String> = deserializeSyncMap(syncStatusData)

    /** 设置同步状态映射（快捷方式） */
    fun setSyncStatusMap(map: Map<String, String>) { syncStatusData = serializeSyncMap(map) }

    /** 获取持久化的选中项目集合 */
    fun getSelectedProjects(): Set<String> = deserializeProjects(selectedProjectsData)

    /** 保存选中项目集合 */
    fun setSelectedProjects(projects: Set<String>) {
        selectedProjectsData = serializeProjects(projects)
    }

    /** 获取缓存的扫描数据（反序列化为 List<ControllerGroup>） */
    fun getCachedScanGroups(): List<com.myhope.sync.model.ControllerGroup>? {
        if (cachedScanJson.isBlank()) return null
        return try {
            val type = object : TypeToken<List<com.myhope.sync.model.ControllerGroup>>() {}.type
            syncGson.fromJson<List<com.myhope.sync.model.ControllerGroup>>(cachedScanJson, type)
        } catch (_: Exception) { null }
    }

    /** 缓存扫描数据 */
    fun setCachedScanGroups(groups: List<com.myhope.sync.model.ControllerGroup>) {
        cachedScanJson = syncGson.toJson(groups)
    }

    /** 将逗号分隔的字符串解析为列索引集合 */
    fun getHiddenColumnSet(): Set<Int> {
        return hiddenColumnIds.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..7 }
            .toSet()
    }

    /** 保存隐藏列索引集合 */
    fun setHiddenColumns(columns: Set<Int>) {
        hiddenColumnIds = columns.filter { it in 0..7 }.sorted().joinToString(",")
        // 列 0(#) 和 1(复选框) 不允许隐藏，自动过滤
    }

    /** 解析持久化的列宽度映射 */
    fun getColumnWidthMap(): Map<Int, Int> {
        if (columnWidths.isBlank()) return emptyMap()
        return columnWidths.split(",").mapNotNull { entry ->
            val parts = entry.trim().split(":")
            if (parts.size == 2) {
                val col = parts[0].toIntOrNull()
                val w = parts[1].toIntOrNull()
                if (col != null && w != null && col in 2..7) col to w else null
            } else null
        }.toMap()
    }

    /** 持久化列宽度映射 */
    fun setColumnWidths(widths: Map<Int, Int>) {
        columnWidths = widths.entries
            .filter { it.key in 2..7 }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
    }
}