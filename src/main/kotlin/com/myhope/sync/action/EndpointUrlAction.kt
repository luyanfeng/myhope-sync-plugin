package com.myhope.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Spring 注解全限定名（与 psi/SpringControllerParser.kt 的 companion object 中同名常量一致；
// 那里是 private 且不同包不可见，故此处独立声明，值保持同步）
private const val ANNOTATION_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
private const val ANNOTATION_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping"
private const val ANNOTATION_POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping"
private const val ANNOTATION_PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping"
private const val ANNOTATION_DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping"
private const val ANNOTATION_PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping"
private const val ANNOTATION_PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable"
private const val ANNOTATION_REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam"
private const val ANNOTATION_REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"

/** 可作为 HTTP 端点的方法级映射注解集合（用于判断光标是否处于 Spring 方法上）。 */
private val MAPPING_ANNOTATION_FQNS = setOf(
    ANNOTATION_REQUEST_MAPPING,
    ANNOTATION_GET_MAPPING,
    ANNOTATION_POST_MAPPING,
    ANNOTATION_PUT_MAPPING,
    ANNOTATION_DELETE_MAPPING,
    ANNOTATION_PATCH_MAPPING
)

private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 8080

/** 单个查询参数信息。 */
data class QueryParam(
    val name: String,
    val type: String,
    val required: Boolean,
    val defaultValue: String?
)

/** 从方法上提取出的端点信息。 */
data class EndpointInfo(
    val fullPath: String,
    val httpMethod: String,
    val pathVariables: List<Pair<String, String>>,
    val queryParams: List<QueryParam>,
    val hasRequestBody: Boolean
)

/**
 * 判断光标是否位于带 Spring HTTP 映射注解的 [PsiMethod] 上。
 *
 * 供各 Copy 动作的 update 与 [CopyEndpointUrlGroup] 的 update 复用。
 */
internal fun isCursorOnSpringMethod(e: AnActionEvent): Boolean {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return false
    val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return false
    if (e.getData(CommonDataKeys.PROJECT) == null) return false

    return ReadAction.compute<Boolean, RuntimeException> {
        val element = psiFile.findElementAt(editor.caretModel.offset) ?: return@compute false
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@compute false
        method.annotations.any { ann -> ann.qualifiedName in MAPPING_ANNOTATION_FQNS }
    }
}

/**
 * 「复制接口 URL」动作基类：提供轻量的 PsiMethod → EndpointInfo 解析（不复用/不触发
 * SpringControllerParser.parseControllerClass 的重型 JSON 骨架解析），并负责从光标处
 * 所在模块的 resources 下 application.yml/.yaml/.properties 解析 server.port（默认 8080）。
 *
 * 子类仅需覆写 [getOutputText] 决定复制的内容。
 */
abstract class EndpointUrlAction : AnAction(), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isCursorOnSpringMethod(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val data = ReadAction.compute<ResolvedOutput?, RuntimeException> {
            val element = psiFile.findElementAt(editor.caretModel.offset) ?: return@compute null
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@compute null
            val info = extractEndpointInfo(method) ?: return@compute null
            val module = ModuleUtilCore.findModuleForPsiElement(method)
            val port = if (module != null) resolvePort(project, module) else DEFAULT_PORT

            val url = "http://$DEFAULT_HOST:$port${info.fullPath}"
            val urlWithQuery = url + appendQueryParams(info.queryParams)
            val curl = buildCurlCommand(info, urlWithQuery)
            ResolvedOutput(info, url, urlWithQuery, curl)
        } ?: return

        writeToClipboard(getOutputText(data.info, data.url, data.urlWithQuery, data.curl))
    }

    /**
     * 子类决定复制的目标内容。
     *
     * @param url          仅 path 的完整地址（path 变量保留 {xxx} 占位），如 http://127.0.0.1:8080/api/users/{id}
     * @param urlWithQuery url + 查询串（含 required 查询参数）
     * @param curl         生成的 curl 命令
     */
    protected abstract fun getOutputText(
        info: EndpointInfo,
        url: String,
        urlWithQuery: String,
        curl: String
    ): String

    // ======================== 端点信息提取（轻量，不触发重型解析） ========================

    /**
     * 从方法上提取端点信息：
     * 1. 方法级映射注解（@GetMapping 等 / @RequestMapping + method 属性）→ HTTP 方法与路径
     * 2. 类级 @RequestMapping → path 前缀
     * 3. 参数：@PathVariable / @RequestParam / @RequestBody
     *
     * @return 方法上不存在任何 HTTP 映射注解时返回 null
     */
    protected fun extractEndpointInfo(method: PsiMethod): EndpointInfo? {
        var httpMethod: String? = null
        var methodPath: String? = null

        for (annotation in method.annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue
            val resolved = resolveHttpMapping(annotation, qualifiedName) ?: continue
            httpMethod = resolved.httpMethod
            methodPath = resolved.path
            break
        }

        val finalMethod = httpMethod ?: return null
        val finalPath = methodPath ?: ""

        val psiClass = method.containingClass ?: return null
        val classLevelPath = extractClassLevelPath(psiClass)
        val fullPath = normalizePath(classLevelPath, finalPath)

        val (pathVariables, queryParams, hasRequestBody) = collectParameters(method)
        return EndpointInfo(
            fullPath = fullPath,
            httpMethod = finalMethod,
            pathVariables = pathVariables,
            queryParams = queryParams,
            hasRequestBody = hasRequestBody
        )
    }

    private data class ResolvedMapping(val httpMethod: String, val path: String)

    /**
     * 将方法级映射注解解析为 (HTTP 方法, 路径)。
     * @RequestMapping 未声明 method 时默认 GET。
     */
    private fun resolveHttpMapping(annotation: PsiAnnotation, qualifiedName: String): ResolvedMapping? {
        val path = getPathFromAnnotation(annotation) ?: ""
        val httpMethod: String? = when (qualifiedName) {
            ANNOTATION_REQUEST_MAPPING -> extractHttpMethodFromRequestMapping(annotation)
            ANNOTATION_GET_MAPPING -> "GET"
            ANNOTATION_POST_MAPPING -> "POST"
            ANNOTATION_PUT_MAPPING -> "PUT"
            ANNOTATION_DELETE_MAPPING -> "DELETE"
            ANNOTATION_PATCH_MAPPING -> "PATCH"
            else -> null
        }
        val method = httpMethod ?: return null
        return ResolvedMapping(method, path)
    }

    private fun extractHttpMethodFromRequestMapping(annotation: PsiAnnotation): String? {
        val methodAttr = annotation.findAttributeValue("method") ?: return "GET"
        val candidates: Sequence<PsiAnnotationMemberValue> = when (methodAttr) {
            is PsiArrayInitializerMemberValue -> methodAttr.initializers.asSequence()
            else -> sequenceOf(methodAttr)
        }
        for (candidate in candidates) {
            val reference = candidate as? PsiReferenceExpression ?: continue
            val resolved = reference.resolve()
            if (resolved is PsiEnumConstant) return resolved.name
            // 无法 resolve 时退回从文本尾部截取枚举名
            val name = reference.text.substringAfterLast('.')
            if (name in HTTP_METHOD_NAMES) return name
        }
        return null
    }

    private fun extractClassLevelPath(psiClass: PsiClass): String {
        val annotation = psiClass.annotations
            .firstOrNull { it.qualifiedName == ANNOTATION_REQUEST_MAPPING }
            ?: return ""
        return getPathFromAnnotation(annotation) ?: ""
    }

    // ======================== 方法参数解析 ========================

    private fun collectParameters(
        method: PsiMethod
    ): Triple<List<Pair<String, String>>, List<QueryParam>, Boolean> {
        val pathVariables = mutableListOf<Pair<String, String>>()
        val queryParams = mutableListOf<QueryParam>()
        var hasRequestBody = false

        for (param in method.parameterList.parameters) {
            var matched = false
            for (annotation in param.annotations) {
                when (annotation.qualifiedName) {
                    ANNOTATION_PATH_VARIABLE -> {
                        val name = annotationName(annotation) ?: param.name ?: ""
                        pathVariables.add(name to param.type.presentableText)
                        matched = true
                    }
                    ANNOTATION_REQUEST_PARAM -> {
                        val name = annotationName(annotation) ?: param.name ?: ""
                        queryParams.add(
                            QueryParam(
                                name = name,
                                type = param.type.presentableText,
                                required = readBooleanAttribute(annotation, "required", default = true),
                                defaultValue = readDeclaredStringAttribute(annotation, "defaultValue")
                            )
                        )
                        matched = true
                    }
                    ANNOTATION_REQUEST_BODY -> {
                        hasRequestBody = true
                        matched = true
                    }
                }
                if (matched) break
            }
            // 无 Spring 参数注解的简单类型参数 → 按 Spring 默认语义视为必填 query 参数
            if (!matched && isSimpleType(param)) {
                queryParams.add(
                    QueryParam(name = param.name ?: "", type = param.type.presentableText, required = true, defaultValue = null)
                )
            }
        }
        return Triple(pathVariables, queryParams, hasRequestBody)
    }

    /** @PathVariable / @RequestParam 的 value/name 属性显式参数名。 */
    private fun annotationName(annotation: PsiAnnotation): String? {
        for (attr in listOf("value", "name")) {
            val attrValue = annotation.findAttributeValue(attr) ?: continue
            val value = extractStringValue(attrValue)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun readBooleanAttribute(annotation: PsiAnnotation, attributeName: String, default: Boolean): Boolean {
        val attrValue = annotation.findAttributeValue(attributeName) ?: return default
        val literal = attrValue as? PsiLiteralExpression ?: return default
        return (literal.value as? Boolean) ?: default
    }

    private fun readDeclaredStringAttribute(annotation: PsiAnnotation, attributeName: String): String? {
        val attrValue = annotation.findDeclaredAttributeValue(attributeName) ?: return null
        return extractStringValue(attrValue)
    }

    /** 简单类型（原始类型 / java.* JDK 值类型）视为可直接绑定 query 参数的标量。 */
    private fun isSimpleType(param: PsiParameter): Boolean {
        val type = param.type
        if (type is PsiPrimitiveType) return true
        val classType = type as? PsiClassType ?: return false
        val resolved = classType.resolve() ?: return false
        val qualifiedName = resolved.qualifiedName ?: return false
        if (qualifiedName.startsWith("java.lang.") ||
            qualifiedName.startsWith("java.math.") ||
            qualifiedName.startsWith("java.time.")
        ) {
            return true
        }
        return qualifiedName == "java.util.Date" || qualifiedName == "java.util.UUID"
    }

    // ======================== URL / curl 构造 ========================

    /** 拼接类级 path 与方法级 path，统一以单个 / 开头、中间单 / 分隔。 */
    private fun normalizePath(classLevelPath: String, methodPath: String): String {
        val classPart = classLevelPath.trim('/')
        val methodPart = methodPath.trim('/')
        return when {
            classPart.isEmpty() && methodPart.isEmpty() -> "/"
            classPart.isEmpty() -> "/$methodPart"
            methodPart.isEmpty() -> "/$classPart"
            else -> "/$classPart/$methodPart"
        }
    }

    /**
     * 生成查询串（含开头的 ?）：
     * - required=false 的参数跳过
     * - 有 defaultValue → name=defaultValue
     * - 无 defaultValue 的 required 参数 → name={name} 占位符
     */
    private fun appendQueryParams(queryParams: List<QueryParam>): String {
        val parts = mutableListOf<String>()
        for (param in queryParams) {
            if (!param.required) continue
            val value = param.defaultValue ?: "{${param.name}}"
            parts.add("${param.name}=$value")
        }
        if (parts.isEmpty()) return ""
        return "?" + parts.joinToString("&")
    }

    /** 生成 curl 命令；@RequestBody 时追加 JSON Content-Type 头与空 body 占位。 */
    private fun buildCurlCommand(info: EndpointInfo, urlWithQuery: String): String {
        val builder = StringBuilder("curl -X ${info.httpMethod} '")
        builder.append(urlWithQuery).append('\'')
        if (info.hasRequestBody) {
            builder.append(" -H 'Content-Type: application/json' -d '{}'")
        }
        return builder.toString()
    }

    private fun writeToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    // ======================== 端口解析 ========================

    /**
     * 读取模块 resources 下 application.yml/.yaml/.properties 的 server.port，
     * 读不到（或无 resources 目录）时返回 [DEFAULT_PORT]。
     */
    protected fun resolvePort(project: Project, module: Module): Int {
        val sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots(false)
        val resourcesRoot = sourceRoots.firstOrNull { it.name == "resources" && it.path.contains("/main/") }
            ?: sourceRoots.firstOrNull { it.name == "resources" }
            ?: return DEFAULT_PORT
        return readPortFromResources(resourcesRoot) ?: DEFAULT_PORT
    }

    /**
     * 在 ReadAction 内读取 resources 根目录下的配置文件内容并按优先级解析端口。
     * 仅支持简单按行正则解析，不引入 YAML 解析库。
     */
    protected fun readPortFromResources(resourcesRoot: VirtualFile): Int? {
        val fileNames = listOf("application.yml", "application.yaml", "application.properties")
        val contents = ReadAction.compute<Map<String, String?>, RuntimeException> {
            fileNames.associateWith { fileName ->
                val child = resourcesRoot.findChild(fileName)
                if (child != null && !child.isDirectory) {
                    String(child.contentsToByteArray(), Charsets.UTF_8)
                } else {
                    null
                }
            }
        }

        for (fileName in fileNames) {
            val content = contents[fileName] ?: continue
            val port = if (fileName.endsWith(".properties")) {
                parsePortFromProperties(content)
            } else {
                parsePortFromYaml(content)
            }
            if (port != null) return port
        }
        return null
    }

    private fun parsePortFromProperties(content: String): Int? {
        val regex = Regex("""^server\.port\s*[=:]\s*(\d+)""")
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue
            val match = regex.find(trimmed) ?: continue
            val port = match.groupValues[1].toIntOrNull() ?: continue
            if (port > 0) return port
        }
        return null
    }

    /**
     * 按行解析 YAML：仅当顶层出现 `server:` 块时，取其缩进子级首个 `port: <数字>`。
     * 避免误命中其他块（如 management.server）下的 port。
     */
    private fun parsePortFromYaml(content: String): Int? {
        val portRegex = Regex("""^port\s*:\s*(\d+)""")
        var inServerBlock = false

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val firstChar = line.firstOrNull() ?: continue
            val isTopLevel = firstChar != ' ' && firstChar != '\t'
            if (isTopLevel) {
                inServerBlock = trimmed == "server:" || trimmed.startsWith("server:")
                continue
            }
            if (!inServerBlock) continue

            val match = portRegex.find(trimmed) ?: continue
            val port = match.groupValues[1].toIntOrNull() ?: continue
            if (port > 0) return port
        }
        return null
    }

    // ======================== 注解属性字符串提取 ========================

    /** 从注解中提取 path（value / path 属性），数组形式取首个。 */
    private fun getPathFromAnnotation(annotation: PsiAnnotation): String? {
        for (attr in listOf("value", "path")) {
            val attrValue = annotation.findAttributeValue(attr) ?: continue
            val path = when (attrValue) {
                is PsiArrayInitializerMemberValue ->
                    attrValue.initializers.firstOrNull()?.let { extractStringValue(it) }
                else -> extractStringValue(attrValue)
            }
            if (!path.isNullOrBlank()) return path
        }
        return null
    }

    /**
     * 从注解成员值中提取字符串：
     * - PsiLiteralExpression（字符串字面量）
     * - PsiReferenceExpression（编译期常量引用，解析到带初始化器的字段）
     */
    private fun extractStringValue(value: PsiAnnotationMemberValue): String? {
        return when (value) {
            is PsiLiteralExpression -> value.value?.toString()
            is PsiReferenceExpression -> {
                val resolved = value.resolve()
                if (resolved is PsiField && resolved.hasInitializer()) {
                    val initializer = resolved.initializer
                    when (initializer) {
                        is PsiLiteralExpression -> initializer.value?.toString()
                        else -> initializer?.text?.trim('"')
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** actionPerformed 计算出的中间产物。 */
    private data class ResolvedOutput(
        val info: EndpointInfo,
        val url: String,
        val urlWithQuery: String,
        val curl: String
    )

    private companion object {
        val HTTP_METHOD_NAMES = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE")
    }
}
