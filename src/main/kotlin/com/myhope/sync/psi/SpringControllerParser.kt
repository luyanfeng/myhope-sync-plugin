package com.myhope.sync.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.FileIndexFacade
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Query
import com.myhope.sync.model.*

/**
 * 基于 IntelliJ PSI API 的 Spring Boot Controller 解析器。
 *
 * 扫描项目中所有标注了 [@RestController] 或 [@Controller] 的类，
 * 提取其中的 HTTP 端点信息（路径、方法、参数等）。
 *
 * 支持多模块/多项目结构——按模块逐一扫描，注解类不可解析时自动
 * 降级为文件遍历扫描，保证能发现所有 Controller。
 */
class SpringControllerParser(private val project: Project) {

    private val log = Logger.getInstance(SpringControllerParser::class.java)

    // Spring 注解的全限定名常量
    companion object {
        private const val ANNOTATION_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
        private const val ANNOTATION_CONTROLLER = "org.springframework.web.bind.annotation.Controller"
        private const val ANNOTATION_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
        private const val ANNOTATION_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping"
        private const val ANNOTATION_POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping"
        private const val ANNOTATION_PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping"
        private const val ANNOTATION_DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping"
        private const val ANNOTATION_PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping"

        private const val ANNOTATION_PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable"
        private const val ANNOTATION_REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam"
        private const val ANNOTATION_REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"
        private const val ANNOTATION_REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader"

    // Swagger / OpenAPI 注解
    private const val ANNOTATION_API = "io.swagger.annotations.Api"
    private const val ANNOTATION_API_OPERATION = "io.swagger.annotations.ApiOperation"
    private const val ANNOTATION_OPERATION_V3 = "io.swagger.v3.oas.annotations.Operation"

        // MultipartFile 类型全限定名
        private val MULTIPART_FILE_TYPES = setOf(
            "MultipartFile",
            "org.springframework.web.multipart.MultipartFile"
        )

        // JSON 骨架展示相关常量
        private val PRIMITIVE_TYPES = setOf(
            "byte", "short", "int", "long", "float", "double", "boolean", "char"
        )
        private val COLLECTION_TYPES = setOf(
            "java.util.Collection", "java.util.List", "java.util.Set",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
            "java.util.TreeSet", "java.util.Vector", "java.util.Queue",
            "java.util.Deque", "java.util.Stack"
        )
        private val MAP_TYPES = setOf(
            "java.util.Map", "java.util.HashMap", "java.util.TreeMap",
            "java.util.LinkedHashMap", "java.util.Hashtable", "java.util.SortedMap",
            "java.util.NavigableMap", "java.util.concurrent.ConcurrentMap",
            "java.util.concurrent.ConcurrentHashMap"
        )
        private const val JSON_SKELETON_MAX_DEPTH = 4
    }

    /**
     * 解析项目中所有 Spring Controller 类，返回按 Controller 分组的结果。
     *
     * 所有 PSI 操作在 [ReadAction.compute] 内执行，确保后台线程安全。
     * 按模块逐一扫描，每个模块尝试注解搜索 → 失败则降级为文件遍历。
     */
    /**
     * 解析项目中所有 Spring Controller 类，返回按 Controller 分组的结果。
     *
     * @param moduleNames 可选的模块名称过滤集。null 表示扫描所有模块。
     *                    非 null 时只扫描名称在集合中的模块。
     */
    fun parseAllControllers(moduleNames: Set<String>? = null): List<ControllerGroup> {
        var result: List<ControllerGroup> = emptyList()
        ApplicationManager.getApplication().runReadAction {
            result = parseAllControllersInReadAction(moduleNames)
        }
        return result
    }

    private fun parseAllControllersInReadAction(moduleNames: Set<String>? = null): List<ControllerGroup> {
        val allModules = ModuleManager.getInstance(project).modules
        val modules = if (moduleNames != null) {
            allModules.filter { it.name in moduleNames }
        } else {
            allModules.toList()
        }
        if (modules.isEmpty()) {
            // 模块按名过滤为空时，回退到项目级扫描（兼容单项目 / 模块名不匹配场景）
            return scanProjectLevel()
        }

        val allGroups = mutableListOf<ControllerGroup>()
        for (module in modules) {
            try {
                val moduleGroups = scanModule(module)
                allGroups.addAll(moduleGroups)
            } catch (e: Exception) {
                log.warn("模块 [${module.name}] Controller 扫描出错: ${e.message}", e)
            }
        }
        return allGroups
    }

    // ======================== 模块级扫描 ========================

    /**
     * 扫描单个 Module 中的 Controller。
     *
     * 策略：
     * 1. 在 module classpath 中查找 [@RestController] 注解类
     * 2. 若找到 → 使用 [AnnotatedElementsSearch] 进行索引级搜索（高效）
     * 3. 若未找到 → 降级为文件遍历搜索（兼容无 Spring 依赖签名的模块）
     */
    private fun scanModule(module: com.intellij.openapi.module.Module): List<ControllerGroup> {
        val moduleScope = GlobalSearchScope.moduleScope(module)
        val moduleName = module.name

        // 尝试注解搜索
        val annotationSearchResult = tryFindByAnnotationSearch(moduleScope)
        if (annotationSearchResult != null) {
            return annotationSearchResult
                .mapNotNull { psiClass ->
                    parseControllerClass(psiClass, moduleName)
                }
        }

        // 降级：文件遍历搜索
        return scanByFileTraversal(moduleScope, moduleName)
    }

    /**
     * 在指定作用域内尝试基于注解的搜索。
     *
     * @return 找到的 [PsiClass] 列表，若注解类不可解析则返回 null
     */
    private fun tryFindByAnnotationSearch(scope: GlobalSearchScope): Set<PsiClass>? {
        val result = linkedSetOf<PsiClass>()

        for (annotationFqn in listOf(ANNOTATION_REST_CONTROLLER, ANNOTATION_CONTROLLER)) {
            val annotationClass = JavaPsiFacade.getInstance(project).findClass(
                annotationFqn,
                scope
            ) ?: continue

            val query: Query<PsiClass> = AnnotatedElementsSearch.searchPsiClasses(
                annotationClass,
                scope
            )

            query.forEach { psiClass ->
                result.add(psiClass)
            }
        }

        // 只要没有找到任何被注解的类就返回 null，触发 scanByFileTraversal 降级。
        // 不要依赖 hasAnyAnnotationClass 判断：注解类在 classpath 中可解析但
        // AnnotatedElementsSearch 因索引不完整返回空集时，仍需降级文件遍历。
        return result.takeIf { it.isNotEmpty() }
    }

    /**
     * 检查 scope 中至少有一个 Spring Controller 注解类可解析。
     */
    private fun hasAnyAnnotationClass(scope: GlobalSearchScope): Boolean {
        return listOf(ANNOTATION_REST_CONTROLLER, ANNOTATION_CONTROLLER).any { fqn ->
            JavaPsiFacade.getInstance(project).findClass(fqn, scope) != null
        }
    }

    /**
     * 通过文件类型索引 + PSI 遍历查找 Controller。
     *
     * 不依赖注解类的可解析性，直接按注解名称字符串匹配。
     */
    private fun scanByFileTraversal(scope: GlobalSearchScope, moduleName: String): List<ControllerGroup> {
        val result = mutableListOf<ControllerGroup>()
        val fileIndex = FileIndexFacade.getInstance(project)

        // 收集作用域内的所有 Java 文件
        val files = FileTypeIndex.getFiles(
            com.intellij.ide.highlighter.JavaFileType.INSTANCE,
            scope
        ).toMutableSet()

        // 尝试也搜索 Kotlin 文件（Kotlin 插件可能未安装，用反射安全获取）
        try {
            val ktFileTypeClass = Class.forName("org.jetbrains.kotlin.psi.KotlinFileType")
            val ktInstance = ktFileTypeClass.getField("INSTANCE").get(null)
            if (ktInstance is com.intellij.openapi.fileTypes.FileType) {
                files.addAll(FileTypeIndex.getFiles(ktInstance, scope))
            }
        } catch (_: Exception) {
            // Kotlin 插件未安装，忽略
        }

        for (vf in files) {
            if (!fileIndex.isInSourceContent(vf)) continue

            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
            if (psiFile !is PsiJavaFile) continue

            // 遍历文件中的顶级类
            for (cls in psiFile.classes) {
                if (isSpringControllerByAnnotationName(cls)) {
                    val group = parseControllerClass(cls, moduleName)
                    if (group != null) result.add(group)
                }
                // 扫描内部类
                PsiTreeUtil.collectElementsOfType(cls, PsiClass::class.java)
                    .filter { it != cls && isSpringControllerByAnnotationName(it) }
                    .forEach { innerClass ->
                        val group = parseControllerClass(innerClass, moduleName)
                        if (group != null) result.add(group)
                    }
            }
        }

        return result
    }

    /**
     * 按注解名称字符串判断是否为 Spring Controller（不依赖注解类解析）。
     */
    private fun isSpringControllerByAnnotationName(psiClass: PsiClass): Boolean {
        return psiClass.annotations.any { ann ->
            ann.qualifiedName == ANNOTATION_REST_CONTROLLER ||
            ann.qualifiedName == ANNOTATION_CONTROLLER
        }
    }

    /**
     * 无模块时回退到项目级扫描。
     */
    private fun scanProjectLevel(): List<ControllerGroup> {
        val projectScope = GlobalSearchScope.projectScope(project)

        // 尝试注解搜索
        val annotationResult = tryFindByAnnotationSearch(projectScope)
        if (annotationResult != null) {
            return annotationResult.mapNotNull { cls ->
                val modName = ModuleUtilCore.findModuleForPsiElement(cls)?.name ?: ""
                parseControllerClass(cls, modName)
            }
        }

        // 降级
        return scanByFileTraversal(projectScope, "")
    }

    /**
     * 解析单个 [PsiClass] 是否为 Spring Controller，并提取其端点。
     *
     * @param moduleName 所属模块名
     * @return 如果该类没有存活端点则返回 null
     */
    fun parseControllerClass(psiClass: PsiClass, moduleName: String = ""): ControllerGroup? {
        return try {
            val classLevelPath = extractClassLevelPath(psiClass)
            val apiTag = extractApiTag(psiClass)

            val endpoints = psiClass.methods
                .flatMap { method -> parseMethodEndpoints(method, classLevelPath) }

            if (endpoints.isEmpty()) return null

            ControllerGroup(
                controllerClassName = psiClass.name ?: "Unknown",
                controllerQualifiedName = psiClass.qualifiedName ?: "Unknown",
                classLevelPath = classLevelPath.ifEmpty { null },
                moduleName = moduleName,
                endpoints = endpoints,
                apiTag = apiTag
            )
        } catch (e: Exception) {
            log.warn("解析 Controller 类 '${psiClass.qualifiedName}' 时出错: ${e.message}", e)
            null
        }
    }

    // ======================== 提取类级别路径 ========================

    /**
     * 从类上提取 [@RequestMapping] 的 value/path 作为路径前缀。
     */
    private fun extractClassLevelPath(psiClass: PsiClass): String {
        val annotation = psiClass.annotations
            .firstOrNull { it.qualifiedName == ANNOTATION_REQUEST_MAPPING }
            ?: return ""

        return getPathFromAnnotation(annotation) ?: ""
    }

    /**
     * 从类上提取 @Api 注解的 value，作为 Controller 分组的标题。
     *
     * 支持 Swagger 1.x/2.x 的 @Api 注解：
     * - @Api("用户管理")                  → value 属性
     * - @Api(value = "用户管理")           → value 属性
     * - @Api(tags = {"用户", "管理"})      → tags 数组首个元素
     *
     * @return 找到的有效标签值，没有任何值则返回 null
     */
    private fun extractApiTag(psiClass: PsiClass): String? {
        val annotation = psiClass.annotations.firstOrNull {
            it.qualifiedName == ANNOTATION_API
        } ?: return null

        // 优先尝试 value 属性（最常见用法）
        val valueAttr = annotation.findAttributeValue("value")
        if (valueAttr != null) {
            val value = extractStringValue(valueAttr)
            if (!value.isNullOrBlank()) return value
        }

        // 其次尝试 tags 属性（数组或单个值）
        val tagsAttr = annotation.findAttributeValue("tags")
        if (tagsAttr != null) {
            when (tagsAttr) {
                is PsiArrayInitializerMemberValue -> {
                    val firstTag = tagsAttr.initializers.firstOrNull()
                    if (firstTag != null) {
                        val tag = extractStringValue(firstTag)
                        if (!tag.isNullOrBlank()) return tag
                    }
                }
                else -> {
                    val tag = extractStringValue(tagsAttr)
                    if (!tag.isNullOrBlank()) return tag
                }
            }
        }

        return null
    }

    // ======================== 解析方法端点 ========================

    /**
     * 解析 [PsiMethod] 上的所有 HTTP 映射注解，生成对应的端点列表。
     *
     * 一个方法上可能有多个注解（极少见），也可能有 @RequestMapping 同时指定多个 method。
     */
    private fun parseMethodEndpoints(method: PsiMethod, classLevelPath: String): List<SpringEndpoint> {
        val endpoints = mutableListOf<SpringEndpoint>()
        val description = extractApiDescription(method)

        for (annotation in method.annotations) {
            val qualifiedName = annotation.qualifiedName ?: continue

            // 解析当前注解对应的 HTTP 方法和路径
            val methodInfo = resolveHttpMapping(annotation, qualifiedName) ?: continue
            val (httpMethods, methodPath) = methodInfo

            val fullPath = buildFullPath(classLevelPath, methodPath)
            val parameters = parseMethodParameters(method)
            val consumes = extractStringArrayAttribute(annotation, "consumes")

            val controllerName = method.containingClass?.name ?: "Unknown"
            val controllerQualifiedName = method.containingClass?.qualifiedName ?: "Unknown"

            for (httpMethod in httpMethods) {
                endpoints.add(
                    SpringEndpoint(
                        controllerClassName = controllerName,
                        controllerClassQualifiedName = controllerQualifiedName,
                        methodName = method.name,
                        httpMethod = httpMethod,
                        path = methodPath,
                        fullPath = fullPath,
                        description = description,
                        parameters = parameters,
                        consumes = consumes
                    )
                )
            }
        }

        return endpoints
    }

    /**
     * 从方法的注解中提取 API 描述（@ApiOperation 或 @Operation）。
     *
     * 支持：
     * - [@ApiOperation("描述")] — 取 value 属性
     * - [@Operation(summary = "描述")] — 取 summary 属性
     *
     * @return 找到的描述文本，未找到或无值时返回 null
     */
    private fun extractApiDescription(method: PsiMethod): String? {
        for (annotation in method.annotations) {
            val qn = annotation.qualifiedName ?: continue
            val attrName = when (qn) {
                ANNOTATION_API_OPERATION -> "value"
                ANNOTATION_OPERATION_V3 -> "summary"
                else -> continue
            }
            val attrValue = annotation.findAttributeValue(attrName)
            if (attrValue != null) {
                val text = extractStringValue(attrValue)
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    /**
     * 将 HTTP 映射注解解析为 (HTTP 方法列表, 路径) 二元组。
     *
     * @return 无法识别时返回 null
     */
    private fun resolveHttpMapping(
        annotation: PsiAnnotation,
        qualifiedName: String
    ): Pair<List<HttpMethod>, String>? {
        val path = getPathFromAnnotation(annotation) ?: ""

        return when (qualifiedName) {
            ANNOTATION_REQUEST_MAPPING -> {
                val methods = getHttpMethodsFromRequestMapping(annotation)
                methods to path
            }
            ANNOTATION_GET_MAPPING -> listOf(HttpMethod.GET) to path
            ANNOTATION_POST_MAPPING -> listOf(HttpMethod.POST) to path
            ANNOTATION_PUT_MAPPING -> listOf(HttpMethod.PUT) to path
            ANNOTATION_DELETE_MAPPING -> listOf(HttpMethod.DELETE) to path
            ANNOTATION_PATCH_MAPPING -> listOf(HttpMethod.PATCH) to path
            else -> null
        }
    }

    // ======================== 路径处理 ========================

    /**
     * 拼接类级别路径和方法级别路径。
     *
     * 规则：
     * - 两边均为空 → "/"
     * - 仅类路径非空 → "/classPath"
     * - 仅方法路径非空 → "/methodPath"
     * - 均非空 → "/classPath/methodPath"
     */
    private fun buildFullPath(classLevelPath: String, methodPath: String): String {
        val classPart = classLevelPath.trim('/')
        val methodPart = methodPath.trim('/')

        return when {
            classPart.isEmpty() && methodPart.isEmpty() -> "/"
            classPart.isEmpty() -> "/$methodPart"
            methodPart.isEmpty() -> "/$classPart"
            else -> "/$classPart/$methodPart"
        }
    }

    // ======================== 方法参数解析 ========================

    /**
     * 解析 [PsiMethod] 的参数列表，提取 Spring 注解信息。
     */
    private fun parseMethodParameters(method: PsiMethod): List<EndpointParameter> {
        return method.parameterList.parameters.mapNotNull { param ->
            try {
                parseSingleParameter(param)
            } catch (e: Exception) {
                log.warn("解析参数 '${param.name}' 时出错: ${e.message}")
                null
            }
        }
    }

    /**
     * 解析单个 [PsiParameter] 的 Spring 注解。
     */
    private fun parseSingleParameter(param: PsiParameter): EndpointParameter? {
        val paramName = param.name
        val paramType = param.type.presentableText

        // 参数注解与 ParamSource 的映射关系
        val annotationMappings = mapOf(
            ANNOTATION_PATH_VARIABLE to ParamSource.PATH,
            ANNOTATION_REQUEST_PARAM to ParamSource.QUERY,
            ANNOTATION_REQUEST_BODY to ParamSource.BODY,
            ANNOTATION_REQUEST_HEADER to ParamSource.HEADER
        )

        // 找到第一个匹配的 Spring 参数注解
        val matchedAnnotation = param.annotations.firstOrNull { ann ->
            ann.qualifiedName in annotationMappings
        }

        if (matchedAnnotation != null) {
            val source = annotationMappings[matchedAnnotation.qualifiedName]!!

            val required = matchedAnnotation.findAttributeValue("required")?.let { attr ->
                (attr as? PsiLiteralExpression)?.value?.toString()?.toBoolean()
            } ?: true

            val defaultValue = matchedAnnotation.findDeclaredAttributeValue("defaultValue")?.let { attr ->
                extractStringValue(attr)
            }

            // 从 @PathVariable 的 value/name 属性提取显式参数名
            val explicitName = if (source == ParamSource.PATH || source == ParamSource.QUERY) {
                matchedAnnotation.findAttributeValue("value")?.let { extractStringValue(it) }
                    ?: matchedAnnotation.findAttributeValue("name")?.let { extractStringValue(it) }
            } else null

            // @RequestBody 时递归解析 JSON 骨架展示字符串 + 同步模板
            val bodyJsonSkeleton = if (source == ParamSource.BODY) {
                resolveBodyJsonSkeleton(param.type)
            } else {
                null
            }
            val bodyJsonTemplate = if (source == ParamSource.BODY) {
                resolveBodyJsonTemplate(param.type)
            } else {
                null
            }

            // 所有参数类型，若是复杂对象则展开字段名（用于 query 参数展示或 body 字段名）
            val objectFields = resolveComplexObjectFieldNames(param.type)

            return EndpointParameter(
                name = explicitName?.takeIf { it.isNotBlank() } ?: paramName,
                type = paramType,
                source = source,
                required = required,
                defaultValue = defaultValue,
                bodyJsonSkeleton = bodyJsonSkeleton,
                bodyJsonTemplate = bodyJsonTemplate,
                objectFields = objectFields,
                isMultipartFile = paramType in MULTIPART_FILE_TYPES
            )
        }

        // 没有匹配的 Spring 注解 → 视为 @RequestParam（默认行为）
        val objectFields = resolveComplexObjectFieldNames(param.type)
        return EndpointParameter(
            name = paramName,
            type = paramType,
            source = ParamSource.QUERY,
            required = true,
            objectFields = objectFields,
            isMultipartFile = paramType in MULTIPART_FILE_TYPES
        )
    }

    /**
     * 递归解析 @RequestBody 参数类型，生成 JSON 骨架展示字符串。
     *
     * 例如 `@RequestBody CreateUserRequest`（字段 name:String, age:int, address:Address, tags:List<String>）→
     * `{"name":"...","age":...,"address":{"street":"...","city":"..."},"tags":["..."]}`
     *
     * 支持嵌套对象、集合（List/Set）、Map、数组、枚举。
     * 若类型无法解析返回 null，调用方回退为旧格式 `{"paramName":...}`。
     *
     * @param depth 当前递归深度，超过 [JSON_SKELETON_MAX_DEPTH] 时截断
     */
    private fun resolveBodyJsonSkeleton(psiType: PsiType, depth: Int = 0): String? {
        if (depth > JSON_SKELETON_MAX_DEPTH) return "\"...\""

        val canonical = psiType.canonicalText

        // 1. 原始类型 / JDK 值类型 → 按类型返回默认值
        if (canonical in PRIMITIVE_TYPES || canonical.startsWith("java.lang.") ||
            canonical.startsWith("java.math.") || canonical.startsWith("java.time.") ||
            canonical == "java.util.Date" || canonical == "java.util.UUID"
        ) {
            return typeToSimpleSkeleton(canonical)
        }

        // 2. 数组 → [...]
        if (psiType is PsiArrayType) {
            val el = resolveBodyJsonSkeleton(psiType.componentType, depth + 1) ?: "\"\""
            return "[$el]"
        }

        val classType = psiType as? PsiClassType ?: return null
        val resolved = classType.resolve() ?: return null
        val qn = resolved.qualifiedName ?: ""

        // 3. 枚举 → ""
        if (resolved.isEnum) return "\"\""

        // 4. 集合/列表 → [...]
        if (qn in COLLECTION_TYPES) {
            val elType = classType.parameters.firstOrNull()
            val el = if (elType != null) resolveBodyJsonSkeleton(elType, depth + 1) else null
            val elDisplay = el ?: "\"\""
            return "[$elDisplay]"
        }

        // 5. Map → {}
        if (qn in MAP_TYPES) {
            return "{}"
        }

        // 6. 复杂对象 → 递归展开非静态字段（含继承字段，子类同名优先）
        val psiFields = resolved.allFields
            .asSequence()
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .filter { !it.name.isNullOrBlank() }
            .distinctBy { it.name }
            .toList()

        if (psiFields.isEmpty()) return "{}"

        val fieldEntries = psiFields.joinToString(",") { field ->
            val skeleton = resolveBodyJsonSkeleton(field.type, depth + 1) ?: "\"\""
            "\"${field.name}\":$skeleton"
        }
        return "{$fieldEntries}"
    }

    /**
     * 根据类型的 canonical 文本返回简单类型对应的 JSON 骨架占位符。
     * String/char → `""`，数值 → `1`，布尔 → `true`。
     */
    private fun typeToSimpleSkeleton(canonical: String): String {
        return when {
            canonical == "java.lang.String" || canonical == "java.lang.Character" || canonical == "char" -> "\"\""
            canonical == "boolean" || canonical == "java.lang.Boolean" -> "true"
            canonical == "float" || canonical == "double" || canonical == "java.lang.Float" || canonical == "java.lang.Double" -> "1.0"
            canonical == "long" || canonical == "java.lang.Long" ||
            canonical == "int" || canonical == "java.lang.Integer" ||
            canonical == "short" || canonical == "java.lang.Short" ||
            canonical == "byte" || canonical == "java.lang.Byte" -> "1"
            canonical.startsWith("java.math.BigDecimal") || canonical.startsWith("java.math.BigInteger") -> "1"
            else -> "\"\"" // Date、LocalDate、UUID 等统一为空串
        }
    }

    /**
     * 递归解析 @RequestBody 参数类型，生成 JSON 同步模板（真实占位值）。
     *
     * 与 [resolveBodyJsonSkeleton] 结构相同，但叶子节点使用可用的默认值：
     * - String → `"string"`，int/long → `0`，double/float → `0.0`，boolean → `false`
     * - 数组/集合 → `[]`，Map → `{}`
     * - 嵌套对象 → 递归展开字段
     *
     * 用于 MyHope 同步时生成请求体模板。
     */
    private fun resolveBodyJsonTemplate(psiType: PsiType, depth: Int = 0): String? {
        if (depth > JSON_SKELETON_MAX_DEPTH) return "\"…\""

        val canonical = psiType.canonicalText

        // 1. 简单类型 → 真实占位值
        if (canonical in PRIMITIVE_TYPES || canonical.startsWith("java.lang.") ||
            canonical.startsWith("java.math.") || canonical.startsWith("java.time.") ||
            canonical == "java.util.Date" || canonical == "java.util.UUID"
        ) {
            return typeToTemplateValue(canonical)
        }

        // 2. 数组 → []
        if (psiType is PsiArrayType) return "[]"

        val classType = psiType as? PsiClassType ?: return null
        val resolved = classType.resolve() ?: return null
        val qn = resolved.qualifiedName ?: ""

        // 3. 枚举 → "ENUM"
        if (resolved.isEnum) return "\"ENUM\""

        // 4. 集合/列表 → []
        if (qn in COLLECTION_TYPES) return "[]"

        // 5. Map → {}
        if (qn in MAP_TYPES) return "{}"

        // 6. 复杂对象 → 递归展开非静态字段（含继承字段，子类同名优先）
        val psiFields = resolved.allFields
            .asSequence()
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .filter { !it.name.isNullOrBlank() }
            .distinctBy { it.name }
            .toList()

        if (psiFields.isEmpty()) return "{}"

        val fieldEntries = psiFields.joinToString(",") { field ->
            val template = resolveBodyJsonTemplate(field.type, depth + 1) ?: "\"…\""
            "\"${field.name}\":$template"
        }
        return "{$fieldEntries}"
    }

    /**
     * 简单类型的 JSON 模板占位值。
     */
    private fun typeToTemplateValue(canonical: String): String {
        return when {
            canonical == "java.lang.String" || canonical == "java.lang.Character" || canonical == "char" -> "\"\""
            canonical == "boolean" || canonical == "java.lang.Boolean" -> "false"
            canonical == "float" || canonical == "double" || canonical == "java.lang.Float" || canonical == "java.lang.Double" -> "0.0"
            canonical == "long" || canonical == "java.lang.Long" ||
            canonical == "int" || canonical == "java.lang.Integer" ||
            canonical == "short" || canonical == "java.lang.Short" ||
            canonical == "byte" || canonical == "java.lang.Byte" -> "0"
            canonical.startsWith("java.math.BigDecimal") || canonical.startsWith("java.math.BigInteger") -> "0"
            canonical == "java.util.UUID" -> "\"550e8400-e29b-41d4-a716-446655440000\""
            else -> "\"…\""
        }
    }

    /**
     * 检测参数类型是否为复杂对象（非原始类型、非集合/Map、非枚举、有可展开字段），
     * 若是则递归展开其非静态字段名列表（含继承字段，子类同名优先），
     * 嵌套对象使用点号表示法：`field.subField`。
     *
     * 例如 `UserQuery{name, age, address{street, city}, tags}` →
     * `["name", "age", "address.street", "address.city", "tags"]`
     *
     * 用于非 @RequestBody 的复杂对象参数展示为展开的 query 参数，如 `query: name=&age=&address.street=&address.city=&tags=`。
     *
     * @param depth 递归深度，超过 3 层时截断防止循环引用
     * @param prefix 当前层级的路径前缀（父字段名 + "."）
     */
    private fun resolveComplexObjectFieldNames(
        psiType: PsiType,
        depth: Int = 0,
        prefix: String = ""
    ): List<String> {
        if (depth > 3) return emptyList()

        val canonical = psiType.canonicalText

        // 简单类型 / 数组 → 不是复杂对象
        if (isJdkValueType(canonical) || psiType is PsiArrayType) return emptyList()

        val classType = psiType as? PsiClassType ?: return emptyList()
        val resolved = classType.resolve() ?: return emptyList()
        val qn = resolved.qualifiedName ?: ""

        // 枚举 / 集合 / Map → 不是复杂对象
        if (resolved.isEnum || qn in COLLECTION_TYPES || qn in MAP_TYPES) return emptyList()

        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (field in resolved.allFields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue
            if (field.name.isNullOrBlank()) continue
            val fieldName = field.name
            if (!seen.add(fieldName)) continue // 同名取首个（子类优先）

            val qualifiedName = if (prefix.isEmpty()) fieldName else "$prefix.$fieldName"

            // 子字段若是可展开的复杂对象 → 递归
            if (isExpandableObjectType(field.type)) {
                result.addAll(
                    resolveComplexObjectFieldNames(field.type, depth + 1, qualifiedName)
                )
            } else {
                // 简单类型 / 数组 / 集合 / Map / 枚举 → 直接作为叶子节点
                result.add(qualifiedName)
            }
        }

        return result
    }

    /**
     * 判断是否为 JDK 值类型（原始类型、String、BigDecimal、日期等）。
     */
    private fun isJdkValueType(canonical: String): Boolean {
        return canonical in PRIMITIVE_TYPES ||
                canonical.startsWith("java.lang.") ||
                canonical.startsWith("java.math.") ||
                canonical.startsWith("java.time.") ||
                canonical == "java.util.Date" ||
                canonical == "java.util.UUID"
    }

    /**
     * 判断 PSI 类型是否为需要递归展开的复杂对象。
     * 满足：非 JDK 值类型、非数组、非枚举、非集合、非 Map、有非静态字段。
     */
    private fun isExpandableObjectType(psiType: PsiType): Boolean {
        val canonical = psiType.canonicalText
        if (isJdkValueType(canonical) || psiType is PsiArrayType) return false
        val classType = psiType as? PsiClassType ?: return false
        val resolved = classType.resolve() ?: return false
        val qn = resolved.qualifiedName ?: ""
        if (resolved.isEnum || qn in COLLECTION_TYPES || qn in MAP_TYPES) return false
        // 至少有一个非静态字段才视为可展开
        return resolved.allFields.any { field ->
            !field.hasModifierProperty(PsiModifier.STATIC) && !field.name.isNullOrBlank()
        }
    }

    // ======================== 注解属性值提取 ========================

    /**
     * 从 [@RequestMapping] 的 method 属性中提取 [HttpMethod] 列表。
     *
     * 支持：
     * - `method = RequestMethod.GET`（单一值）
     * - `method = {RequestMethod.GET, RequestMethod.POST}`（数组）
     * - 未指定 method → 默认 [HttpMethod.GET]
     */
    private fun getHttpMethodsFromRequestMapping(annotation: PsiAnnotation): List<HttpMethod> {
        val methodAttr = annotation.findAttributeValue("method") ?: return listOf(HttpMethod.GET)

        return when (methodAttr) {
            is PsiArrayInitializerMemberValue -> {
                methodAttr.initializers.mapNotNull { elem ->
                    parseRequestMethodEnum(elem)
                }
            }
            else -> {
                listOfNotNull(parseRequestMethodEnum(methodAttr))
            }
        }
    }

    /**
     * 将 [PsiAnnotationMemberValue] 解析为 Spring [RequestMethod] 枚举值对应的 [HttpMethod]。
     *
     * 例如 `RequestMethod.GET` → [HttpMethod.GET]
     */
    private fun parseRequestMethodEnum(value: PsiAnnotationMemberValue): HttpMethod? {
        val reference = value as? PsiReferenceExpression ?: return null
        val resolved = reference.resolve()
        val enumConstant = resolved as? PsiEnumConstant ?: return null
        val enumName = enumConstant.name

        return try {
            HttpMethod.valueOf(enumName)
        } catch (e: IllegalArgumentException) {
            log.warn("无法识别的 HTTP 方法枚举值: $enumName")
            null
        }
    }

    /**
     * 从注解中提取路径（value 或 path 属性）。
     *
     * @RequestMapping 同时接受 value 和 path，且可互换。
     * 优先尝试 value，再尝试 path。
     */
    private fun getPathFromAnnotation(annotation: PsiAnnotation): String? {
        for (attr in listOf("value", "path")) {
            val attrValue = annotation.findAttributeValue(attr) ?: continue
            val path = extractStringValue(attrValue)
            if (path != null) return path
        }
        return null
    }

    /**
     * 从注解中提取字符串数组属性（如 consumes、produces）。
     *
     * 支持：
     * - `consumes = "application/json"`（单一值）
     * - `consumes = {"application/json", "application/xml"}`（数组）
     */
    private fun extractStringArrayAttribute(annotation: PsiAnnotation, attributeName: String): List<String> {
        val attrValue = annotation.findAttributeValue(attributeName) ?: return emptyList()

        return when (attrValue) {
            is PsiArrayInitializerMemberValue -> {
                attrValue.initializers.mapNotNull { extractStringValue(it) }
            }
            else -> {
                listOfNotNull(extractStringValue(attrValue))
            }
        }
    }

    /**
     * 从 [PsiAnnotationMemberValue] 中提取字符串值。
     *
     * 处理以下情况：
     * - [PsiLiteralExpression]（字符串字面量）
     * - [PsiReferenceExpression]（编译期常量引用，如 `MediaType.APPLICATION_JSON_VALUE`）
     */
    private fun extractStringValue(value: PsiAnnotationMemberValue): String? {
        return when (value) {
            is PsiLiteralExpression -> value.value?.toString()
            is PsiReferenceExpression -> {
                val resolved = value.resolve()
                if (resolved is PsiField && resolved.hasInitializer()) {
                    val init = resolved.initializer
                    when (init) {
                        is PsiLiteralExpression -> init.value?.toString()
                        else -> init?.text?.trim('"')
                    }
                } else null
            }
            else -> null
        }
    }

}
