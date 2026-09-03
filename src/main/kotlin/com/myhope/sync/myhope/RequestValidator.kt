package com.myhope.sync.myhope

import com.google.gson.JsonParser
import com.myhope.sync.util.LogUtil

/**
 * 简易 Zod-like JSON 校验器。
 *
 * 在向 MyHope 服务端发送请求前，校验生成的 JSON 是否符合 web 端
 * Zod schema 的核心约束。不要求完全等价，能拦截最常见的格式问题即可。
 *
 * 核心校验项（对应 web 端 Zod schema 的关键约束）：
 * - v 必须为纯数字字符串
 * - body 必须为 JSON 对象（不能是顶层 null）
 * - body.contentType 必须为 null 或合法的 Content-Type 值
 * - body.body 必须与 contentType 匹配（null 对应 null，否则为字符串）
 * - name / method / endpoint 必须为非 null 字符串
 * - params / headers 必须为数组
 * - auth 必须包含 authType 和 authActive
 */
object RequestValidator {

    /** 合法的 Content-Type 列表（与 Zod schema HoppRESTReqBody 对应） */
    private val VALID_CONTENT_TYPES = setOf(
        "application/json",
        "application/ld+json",
        "application/hal+json",
        "application/vnd.api+json",
        "application/xml",
        "text/xml",
        "application/octet-stream",
        "application/x-www-form-urlencoded",
        "multipart/form-data",
        "text/html",
        "text/plain",
        "binary",
    )

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
    }

    /**
     * 校验 MyHope 请求 JSON 字符串。
     *
     * @param requestJson 待校验的请求 JSON
     * @return [ValidationResult] 校验结果
     */
    fun validate(requestJson: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 解析 JSON
        val root = try {
            val parsed = JsonParser.parseString(requestJson)
            if (!parsed.isJsonObject) {
                errors.add("请求数据不是 JSON 对象")
                return ValidationResult(valid = false, errors = errors)
            }
            parsed.asJsonObject
        } catch (e: Exception) {
            errors.add("JSON 解析失败: ${e.message}")
            return ValidationResult(valid = false, errors = errors)
        }

        // ---- 1. v 字段 ----
        val vElement = root.get("v")
        if (vElement == null || vElement.isJsonNull) {
            errors.add("缺少 v 字段")
        } else if (!vElement.isJsonPrimitive || !vElement.asJsonPrimitive.isString) {
            errors.add("v 字段必须为字符串")
        } else {
            val v = vElement.asString
            if (!v.matches(Regex("^\\d+$"))) {
                errors.add("v 字段值 \"$v\" 不是纯数字字符串（Zod 要求 /^\\d+$/）")
            }
        }

        // ---- 2. body 字段 ----
        val bodyElement = root.get("body")
        if (bodyElement == null) {
            warnings.add("缺少 body 字段，Zod 期望 body 为对象（如 {\"contentType\": null, \"body\": null}）")
        } else if (bodyElement.isJsonNull) {
            errors.add("body 字段为顶层 null，Zod 期望 body 是一个对象（如 {\"contentType\": null, \"body\": null}）")
        } else if (!bodyElement.isJsonObject) {
            errors.add("body 字段类型不正确: ${bodyElement.javaClass.simpleName}，期望 JSON 对象")
        } else {
            val bodyObj = bodyElement.asJsonObject

            // 检查 contentType
            val ctElement = bodyObj.get("contentType")
            if (ctElement == null) {
                warnings.add("body.contentType 字段缺失，Zod 期望 null 或合法的 Content-Type 值")
            } else if (!ctElement.isJsonNull) {
                if (ctElement.isJsonPrimitive && ctElement.asJsonPrimitive.isString) {
                    val ct = ctElement.asString
                    if (ct !in VALID_CONTENT_TYPES) {
                        warnings.add("body.contentType 值 \"$ct\" 不在已知的 Content-Type 列表中，" +
                                "Zod 该分支可能校验失败")
                    }
                }
            }

            // 检查 body 子字段与 contentType 的匹配关系
            val innerBody = bodyObj.get("body")
            val ctNonNull = ctElement != null && !ctElement.isJsonNull
            val ct = if (ctNonNull && ctElement.isJsonPrimitive) ctElement.asString else null

            when {
                // 无 body 模式: contentType 为 null, body 为 null
                !ctNonNull && innerBody == null -> {
                    // 正常
                }
                !ctNonNull && innerBody != null && !innerBody.isJsonNull -> {
                    warnings.add("contentType 为 null 但 body 不为 null，" +
                            "Zod 第一个分支 {contentType: null, body: null} 将不匹配")
                }
                ctNonNull && (innerBody == null || innerBody.isJsonNull) -> {
                    // contentType 非 null 但 body 为 null - 可能有问题
                    warnings.add("contentType 为 \"$ct\" 但 body 为 null，" +
                            "Zod 对应分支期望 body 为字符串或数组")
                }
                ct == "multipart/form-data" -> {
                    if (innerBody != null && !innerBody.isJsonNull && !innerBody.isJsonArray) {
                        warnings.add("contentType 为 \"multipart/form-data\" 但 body 不是数组，" +
                                "Zod 期望 body 为 FormDataKeyValue 数组")
                    }
                }
                ct in setOf("application/octet-stream") -> {
                    // 二进制 - 不做深入校验
                }
                else -> {
                    // 文本类 content-type: body 应为字符串
                    if (innerBody != null && !innerBody.isJsonNull &&
                        !(innerBody.isJsonPrimitive && innerBody.asJsonPrimitive.isString)) {
                        warnings.add("contentType 为 \"$ct\" 但 body 不是字符串，" +
                                "Zod 期望 body 为字符串")
                    }
                }
            }
        }

        // ---- 3. name 字段 ----
        val nameElement = root.get("name")
        if (nameElement == null || nameElement.isJsonNull) {
            errors.add("name 字段缺失或为 null")
        } else if (!nameElement.isJsonPrimitive || !nameElement.asJsonPrimitive.isString) {
            errors.add("name 字段必须为字符串")
        }

        // ---- 4. method 字段 ----
        val methodElement = root.get("method")
        if (methodElement == null || methodElement.isJsonNull) {
            errors.add("method 字段缺失或为 null")
        } else if (!methodElement.isJsonPrimitive || !methodElement.asJsonPrimitive.isString) {
            errors.add("method 字段必须为字符串")
        }

        // ---- 5. endpoint 字段 ----
        val endpointElement = root.get("endpoint")
        if (endpointElement == null || endpointElement.isJsonNull) {
            errors.add("endpoint 字段缺失或为 null")
        } else if (!endpointElement.isJsonPrimitive || !endpointElement.asJsonPrimitive.isString) {
            errors.add("endpoint 字段必须为字符串")
        }

        // ---- 6. params 字段 ----
        val paramsElement = root.get("params")
        if (paramsElement != null && !paramsElement.isJsonNull && !paramsElement.isJsonArray) {
            errors.add("params 字段必须为数组")
        }

        // ---- 7. headers 字段 ----
        val headersElement = root.get("headers")
        if (headersElement != null && !headersElement.isJsonNull && !headersElement.isJsonArray) {
            errors.add("headers 字段必须为数组")
        }

        // ---- 8. auth 字段 ----
        val authElement = root.get("auth")
        if (authElement != null && !authElement.isJsonNull) {
            if (authElement.isJsonObject) {
                val authObj = authElement.asJsonObject
                if (!authObj.has("authType")) {
                    warnings.add("auth 对象缺少 authType 字段")
                }
                if (!authObj.has("authActive")) {
                    warnings.add("auth 对象缺少 authActive 字段")
                }
            } else {
                errors.add("auth 字段必须为对象")
            }
        }

        // ---- 9. preRequestScript / testScript 为字符串 ----
        for (field in listOf("preRequestScript", "testScript")) {
            val el = root.get(field)
            if (el != null && !el.isJsonNull) {
                if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) {
                    warnings.add("$field 字段应为字符串")
                }
            }
        }

        // ---- 10. requestVariables 为数组 ----
        val rvElement = root.get("requestVariables")
        if (rvElement != null && !rvElement.isJsonNull && !rvElement.isJsonArray) {
            warnings.add("requestVariables 字段必须为数组")
        }

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * 校验并输出日志，返回是否通过校验。
     */
    fun validateAndLog(requestJson: String, endpointTitle: String): Boolean {
        val result = validate(requestJson)
        if (result.valid && !result.hasWarnings) return true

        LogUtil.stdout { "[HS-Validate] 请求 [$endpointTitle] 校验结果: " +
                if (result.valid) "通过（${result.warnings.size} 个警告）" else "失败（${result.errors.size} 个错误）" }

        if (result.hasErrors) {
            result.errors.forEach { err ->
                LogUtil.stdout { "[HS-Validate]   ❌ 错误: $err" }
            }
        }
        if (result.hasWarnings) {
            result.warnings.forEach { warn ->
                LogUtil.stdout { "[HS-Validate]   ⚠️ 警告: $warn" }
            }
        }

        if (!result.valid) {
            LogUtil.stdout { "[HS-Validate]   原始 JSON: ${requestJson.take(2000)}" }
        }

        return result.valid
    }
}
