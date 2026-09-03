package com.myhope.sync.model

/**
 * HTTP 方法类型
 */
enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE
}

/**
 * 参数来源
 */
enum class ParamSource {
    PATH, QUERY, BODY, HEADER
}

/**
 * 端点参数
 */
data class EndpointParameter(
    val name: String,
    val type: String,
    val source: ParamSource,
    val required: Boolean,
    val defaultValue: String? = null,
    val description: String? = null,
    val bodyJsonSkeleton: String? = null, // @RequestBody 类型递归解析后的 JSON 骨架展示字符串，如 {"name":"...","age":...}
    val bodyJsonTemplate: String? = null, // @RequestBody 同步用的 JSON 模板（真实占位值），如 {"name":"string","age":0}
    val objectFields: List<String> = emptyList(), // 复杂对象（非 @RequestBody）展开的字段名，用于 query 参数展示
    val isMultipartFile: Boolean = false // 参数类型是否为 MultipartFile
)

/**
 * 从 Spring Controller 解析出的端点
 */
data class SpringEndpoint(
    val controllerClassName: String,
    val controllerClassQualifiedName: String,
    val methodName: String,
    val httpMethod: HttpMethod,
    val path: String,
    val fullPath: String,
    val description: String? = null,
    val parameters: List<EndpointParameter> = emptyList(),
    val consumes: List<String> = emptyList()
)

/**
 * 按 Controller 分组的端点集合
 */
data class ControllerGroup(
    val controllerClassName: String,
    val controllerQualifiedName: String,
    val classLevelPath: String?,
    val moduleName: String,
    val endpoints: List<SpringEndpoint>,
    val apiTag: String? = null
)
