package com.myhope.sync.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.StartupManager
import java.lang.management.ManagementFactory

/**
 * 项目打开后异步预热接口搜索索引：提前解析全部 module 的 Controller 缓存，
 * 用户打开搜索窗时直接命中、秒回。
 *
 * 实现为 [ProjectManagerListener]（而非 StartupActivity），并通过
 * [StartupManager.runAfterOpened] 在项目完全打开后再启动，**不参与 IDE 启动进度**，
 * 避免启动时因预热导致界面锁住无法操作。预热线程降为低优先级、后台执行。
 */
class EndpointSearchStartupActivity : ProjectManagerListener {

    private val log = Logger.getInstance(EndpointSearchStartupActivity::class.java)

    // 粗算每个对象的堆字节（量级参考，非精确值），用于估算缓存规模
    private val BYTES_PER_GROUP = 128        // ControllerGroup + List 容器
    private val BYTES_PER_ENDPOINT = 384     // SpringEndpoint + path/描述等字段
    private val BYTES_PER_PARAMETER = 192    // EndpointParameter

    private fun heapUsedBytes(): Long =
        ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

    override fun projectOpened(project: Project) {
        // 挂 PSI 变更失效监听（按 module 精确失效，改哪模块只清哪模块；轻量，不阻塞）
        EndpointSearchIndex.attachInvalidationListener(project)
        // 等项目完全打开（启动进度结束后）再后台预热，不影响操作。
        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().executeOnPooledThread(
                Runnable {
                    // 等待 IDE 索引构建完成（smart mode），确保后续全文件降级遍历/PSI resolve
                    // 在索引就绪时执行，避免启动早期做全项目 PSI 遍历持读锁拖垮 UI。
                    // waitForSmartMode 阻塞调用，放在 pooled 线程内，不阻塞 EDT。
                    DumbService.getInstance(project).waitForSmartMode()
                    // ---- 调试打点开始：统计预热后缓存内存占用（评估是否值得改磁盘 sqlite 缓存）----
                    val heapBefore = heapUsedBytes()
                    ApplicationManager.getApplication().runReadAction(
                        Runnable {
                            // 预热等待索引就绪后做"完整解析"（含降级遍历 allowFallback=true），
                            // 否则本项目的 Controller 只能靠全文件遍历找到，索引搜索会返回空。
                            EndpointSearchIndex.resolveGroups(project, null, allowFallback = true)
                        }
                    )
                    val heapAfter = heapUsedBytes()
                    val stats = EndpointSearchIndex.stats()
                    // 估算对象图字节（不含 JDK/String 内部缓存等不可控部分，量级参考）
                    val estimatedBytes =
                        (stats.controllerGroupCount.toLong() * BYTES_PER_GROUP +
                            stats.endpointCount.toLong() * BYTES_PER_ENDPOINT +
                            stats.parameterCount.toLong() * BYTES_PER_PARAMETER).toLong()
                    log.info(
                        "[MyHopeSync-Preheat] module=${stats.moduleCount} " +
                            "controllerGroup=${stats.controllerGroupCount} " +
                            "endpoint=${stats.endpointCount} " +
                            "parameter=${stats.parameterCount} " +
                            "| heap before=${formatBytes(heapBefore)} " +
                            "after=${formatBytes(heapAfter)} " +
                            "delta=${formatBytes(heapAfter - heapBefore)} " +
                            "| estimatedCacheBytes=${formatBytes(estimatedBytes)}"
                    )
                    // ---- 调试打点结束 ----
                }
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.2f MB", mb)
    }
}
