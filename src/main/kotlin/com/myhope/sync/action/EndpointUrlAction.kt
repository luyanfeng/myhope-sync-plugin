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
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.myhope.sync.model.ParamSource
import com.myhope.sync.psi.SpringControllerParser
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// Spring 方法级映射注解全限定名（与 psi/SpringControllerParser.kt 的 companion object 中同名常量一致；
// 那里是 private 且不同包不可见，故此处独立声明，值保持同步）。
// 仅用于 isCursorOnSpringMethod 的可见性判断（是否存在映射注解），不参与路径/参数解析。
private const val ANNOTATION_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
private const val ANNOTATION_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping"
private const val ANNOTATION_POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping"
private const val ANNOTATION_PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping"
private const val ANNOTATION_DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping"
private const val ANNOTATION_PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping"

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
    val bodyTemplates: List<String> = emptyList(), // @RequestBody 的 JSON 模板（真实占位值），如 {"name":"","age":0}
    val headers: List<Pair<String, String>> = emptyList() // @RequestHeader 的 (header 名, 默认值/占位)
) {
    /** 是否存在 @RequestBody 参数：由 [bodyTemplates] 推算。 */
    val hasRequestBody: Boolean get() = bodyTemplates.isNotEmpty()
}

/**
 * 判断光标是否位于带 Spring HTTP 映射注解的 [PsiMethod] 上。
 *
 * 供各 Copy 动作的 update 与 [CopyEndpointUrlGroup] 的 update 复用。
 */
internal fun isCursorOnSpringMethod(e: AnActionEvent): Boolean {
    val editor = e.getData(CommonDataKeys.EDITOR)
    val project = e.project
    // PSI_FILE 在编辑器右键菜单上下文里有时为 null，用 editor.virtualFile 兜底
    val psiFile: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
        ?: run {
            val vf = editor?.virtualFile ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (project != null && vf != null) PsiManager.getInstance(project).findFile(vf) else null
        }
    if (editor == null || psiFile == null || project == null) return false

    return ReadAction.compute<Boolean, RuntimeException> {
        val element = psiFile.findElementAt(editor.caretModel.offset)
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        method?.annotations?.any { ann -> ann.qualifiedName in MAPPING_ANNOTATION_FQNS } ?: false
    }
}

/**
 * 「复制接口 URL」动作基类：调用 [SpringControllerParser.parseControllerClass] 复用与接口列表
 * 完全一致的端点/参数解析（含 @RequestBody 的 JSON 模板与 @RequestHeader），并从光标处所在模块
 * 的 resources 下 application.yml/.yaml/.properties 解析 server.port（默认 8080）。
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

    // ======================== 端点信息提取（复用 SpringControllerParser） ========================

    /**
     * 从方法上提取端点信息。
     *
     * 复用 [SpringControllerParser.parseControllerClass]（public，解析整个类的所有方法，含
     * @RequestBody 的 bodyJsonSkeleton/bodyJsonTemplate 与 @RequestHeader），再按方法名在
     * [com.myhope.sync.model.ControllerGroup.endpoints] 中定位光标所在 [PsiMethod] 对应的端点，
     * 直接消费 [com.myhope.sync.model.SpringEndpoint] 与 [com.myhope.sync.model.EndpointParameter]，
     * 与插件接口列表的参数处理完全对齐。
     *
     * @return 方法所在类非 Controller / 解析失败 / 方法无映射注解时返回 null
     */
    protected fun extractEndpointInfo(method: PsiMethod): EndpointInfo? {
        val psiClass = method.containingClass ?: return null
        val project = method.project
        val group = SpringControllerParser(project).parseControllerClass(psiClass) ?: return null
        // 单个方法可能有多个映射注解（多 endpoint），取匹配的第一个即可
        val endpoint = group.endpoints.firstOrNull { it.methodName == method.name } ?: return null
        return EndpointInfo(
            fullPath = endpoint.fullPath, // 直接用 parser 算好的（含类级前缀）
            httpMethod = endpoint.httpMethod.name, // HttpMethod → "GET" 等字符串
            pathVariables = endpoint.parameters.filter { it.source == ParamSource.PATH }.map { it.name to it.name },
            queryParams = endpoint.parameters.filter { it.source == ParamSource.QUERY }.map { q ->
                QueryParam(name = q.name, type = q.type, required = q.required, defaultValue = q.defaultValue)
            },
            // @RequestBody 的真实占位 JSON 模板，如 {"name":"string","age":0}
            bodyTemplates = endpoint.parameters.filter { it.source == ParamSource.BODY }
                .mapNotNull { it.bodyJsonTemplate },
            // @RequestHeader 的 (header 名, 默认值/占位)
            headers = endpoint.parameters.filter { it.source == ParamSource.HEADER }
                .map { h -> h.name to (h.defaultValue ?: "") }
        )
    }

    // ======================== URL / curl 构造 ========================

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

    /**
     * 生成 curl 命令：
     * - 有 @RequestBody（bodyTemplates 非空）→ 追加 `-H 'Content-Type: application/json' -d '<JSON 模板>'`
     *   （多个 body 罕见，取第一个）
     * - 有 @RequestHeader → 每个 header 追加 `-H 'name: value'`（value 为空串也加，让用户填）
     */
    private fun buildCurlCommand(info: EndpointInfo, urlWithQuery: String): String {
        val builder = StringBuilder("curl -X ${info.httpMethod} '")
        builder.append(urlWithQuery).append('\'')
        info.bodyTemplates.firstOrNull()?.let { template ->
            builder.append(" -H 'Content-Type: application/json' -d '")
                .append(template)
                .append('\'')
        }
        for ((name, value) in info.headers) {
            builder.append(" -H '")
                .append(name)
                .append(": ")
                .append(value)
                .append('\'')
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

    /** actionPerformed 计算出的中间产物。 */
    private data class ResolvedOutput(
        val info: EndpointInfo,
        val url: String,
        val urlWithQuery: String,
        val curl: String
    )
}
