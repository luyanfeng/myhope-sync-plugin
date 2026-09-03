package com.myhope.sync.util

import com.intellij.openapi.project.Project
import com.myhope.sync.settings.AppSettings
import com.myhope.sync.settings.getEffectiveSettings
import java.text.MessageFormat
import java.util.*

/**
 * 国际化工具 — 根据语言设置切换中英文。
 *
 * `message(project, key, ...)`：project 非空时取**项目级有效语言**（[getEffectiveSettings]），
 * 否则回落全局 [AppSettings.language]。`message(key, ...)` 等价于 `message(null, key, ...)`，
 * 供无 project 上下文的调用（如最先/Application 级）使用。
 *
 * 资源文件位置：src/main/resources/messages/MyHopeSyncBundle[_zh].properties
 */
object I18n {

    private val enBundle by lazy { load("messages/MyHopeSyncBundle.properties") }
    private val zhBundle by lazy { load("messages/MyHopeSyncBundle_zh.properties") }

    private fun load(path: String): Map<String, String> {
        val props = Properties()
        val stream = I18n::class.java.classLoader.getResourceAsStream(path)
            ?: error("Missing resource: $path")
        props.load(stream.reader(Charsets.UTF_8))
        val map = linkedMapOf<String, String>()
        for (key in props.stringPropertyNames()) {
            map[key] = props.getProperty(key)
        }
        return map
    }

    private fun current(project: Project?): Map<String, String> {
        val lang = if (project != null) getEffectiveSettings(project).language else AppSettings.getInstance().language
        return if (lang == "zh") zhBundle else enBundle
    }

    /** 获取翻译文本（按项目有效语言），支持 {0} {1}… 占位符。找不到 key 时返回 "!key!" */
    fun message(project: Project?, key: String, vararg args: Any?): String {
        val pattern = current(project)[key] ?: return "!$key!"
        return if (args.isEmpty()) pattern else MessageFormat.format(pattern, *args)
    }

    /** 无 project 上下文版本，回落全局语言。等价于 [message(null, key, *args)]。 */
    fun message(key: String, vararg args: Any?): String = message(null, key, *args)

}
