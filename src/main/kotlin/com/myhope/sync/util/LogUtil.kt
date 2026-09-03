package com.myhope.sync.util

import com.intellij.openapi.project.Project
import com.myhope.sync.model.LogLevel
import com.myhope.sync.settings.AppSettings
import com.myhope.sync.settings.getEffectiveSettings
import com.intellij.openapi.diagnostic.Logger

/**
 * 调试日志工具 — 根据日志级别控制日志输出（全局默认 + 项目级覆盖）。
 *
 * 需要判断级别的函数（[debug]/[info]/[warn]/[stdout]/[stackTrace]）提供带 project 参数的重载：
 * project 非空时取项目级有效级别（[getEffectiveSettings].logLevel），否则回落全局 [AppSettings.logLevel]。
 * 不带 project 的版本回落全局级别，供无项目上下文的调用（如网络/校验层）使用。
 * [error] 不受级别控制，始终输出，无 project 重载。
 *
 * 用法：
 *   LogUtil.info(log) { "消息" }                    // 回落全局级别
 *   LogUtil.info(log, project) { "消息" }           // 项目级有效级别
 *   LogUtil.stdout("debug 输出")                    // 对应 System.err.println 的替换
 */
object LogUtil {

    private val currentLevel: LogLevel
        get() = LogLevel.fromId(AppSettings.getInstance().logLevel)

    private fun levelFor(project: Project?): LogLevel =
        if (project != null) LogLevel.fromId(getEffectiveSettings(project).logLevel) else currentLevel

    fun debug(log: Logger, msg: () -> String) {
        if (currentLevel.allows(LogLevel.DEBUG)) log.debug(msg())
    }

    fun debug(log: Logger, project: Project?, msg: () -> String) {
        if (levelFor(project).allows(LogLevel.DEBUG)) log.debug(msg())
    }

    fun info(log: Logger, msg: () -> String) {
        if (currentLevel.allows(LogLevel.INFO)) log.info(msg())
    }

    fun info(log: Logger, project: Project?, msg: () -> String) {
        if (levelFor(project).allows(LogLevel.INFO)) log.info(msg())
    }

    fun warn(log: Logger, msg: () -> String) {
        if (currentLevel.allows(LogLevel.WARN)) log.warn(msg())
    }

    fun warn(log: Logger, project: Project?, msg: () -> String) {
        if (levelFor(project).allows(LogLevel.WARN)) log.warn(msg())
    }

    fun warn(log: Logger, t: Throwable, msg: () -> String) {
        if (currentLevel.allows(LogLevel.WARN)) log.warn(msg(), t)
    }

    fun warn(log: Logger, t: Throwable, project: Project?, msg: () -> String) {
        if (levelFor(project).allows(LogLevel.WARN)) log.warn(msg(), t)
    }

    fun error(log: Logger, msg: () -> String) {
        // ERROR 始终输出
        log.error(msg())
    }

    fun error(log: Logger, t: Throwable, msg: () -> String) {
        log.error(msg(), t)
    }

    /** 替代 System.err.println 的调试输出 */
    fun stdout(msg: () -> String) {
        if (currentLevel.allows(LogLevel.DEBUG)) System.err.println(msg())
    }

    /** 替代 System.err.println 的调试输出（项目级有效级别，project 非空时使用） */
    fun stdout(project: Project?, msg: () -> String) {
        if (levelFor(project).allows(LogLevel.DEBUG)) System.err.println(msg())
    }

    /** 打印完整的异常堆栈（受日志级别控制） */
    fun stackTrace(e: Throwable) {
        if (currentLevel.allows(LogLevel.ERROR)) {
            e.printStackTrace(System.err)
        }
    }

    /** 打印完整的异常堆栈（受日志级别控制，project 非空时使用项目级有效级别） */
    fun stackTrace(project: Project?, e: Throwable) {
        if (levelFor(project).allows(LogLevel.ERROR)) {
            e.printStackTrace(System.err)
        }
    }
}
