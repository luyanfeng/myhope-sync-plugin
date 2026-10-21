package com.myhope.sync.index

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.myhope.sync.model.ControllerGroup
import com.myhope.sync.psi.SpringControllerParser

/**
 * 项目打开后异步预热接口搜索索引：提前解析全部 module 的 Controller 缓存，
 * 用户打开搜索窗时直接命中、秒回。
 *
 * 实现为 [ProjectManagerListener]（而非 StartupActivity），预热任务在索引就绪后
 * 由 [DumbService.runWhenSmart] 触发，**不参与 IDE 启动进度**，
 * 避免启动时因预热导致界面锁住无法操作。
 *
 * 防卡核心：不再用单个大 `runReadAction` 全量解析，而是按 module 拆分为多个
 * `ReadAction.nonBlocking` 短锁任务（Task.Backgroundable 队列），每个 module 内部
 * 再由 [SpringControllerParser.parseModuleChunked] 按片批量解析，批间让出、可取消，
 * 避免一次持读锁数十秒拖垮 EDT（EDT 等写锁）。
 *
 * 注意：本项目 `AnnotatedElementsSearch` 索引搜索返回 0，Controller 只能靠
 * 降级全文件遍历找到，故预热必须 `allowFallback=true`（靠分片防卡，而非禁降级）。
 */
class EndpointSearchStartupActivity : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        // 挂 PSI 变更失效监听（按 module 精确失效，改哪模块只清哪模块；轻量，不阻塞）
        EndpointSearchIndex.attachInvalidationListener(project)
        // 索引就绪（runWhenSmart）后才构造预热任务，避免启动早期做全项目 PSI 遍历。
        // 预热是后台任务，不参与 IDE 启动进度，也不阻塞 EDT。
        DumbService.getInstance(project).runWhenSmart {
            if (project.isDisposed) return@runWhenSmart
            PreheatTask(project).queue()
        }
    }

    /** 后台分片预热任务：可取消，module 间让出、片内 checkCanceled。 */
    private class PreheatTask(project: Project) : Task.Backgroundable(
        project,
        "MyHope Sync: 预热接口索引",
        true // canBeCancelled
    ) {
        private val log = Logger.getInstance(PreheatTask::class.java)

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = false
            val project = project

            // ---- 打点：记录预热后缓存规模与内存占用（评估是否值得改磁盘 sqlite 缓存）----
            val heapBefore = usedHeapBytes()
            val start = System.currentTimeMillis()

            var moduleCount = 0
            var totalEndpoints = 0
            var totalGroups = 0

            val modules = ModuleManager.getInstance(project).modules
            for ((index, module) in modules.withIndex()) {
                if (project.isDisposed) return
                indicator.checkCanceled()
                moduleCount++
                // 更新进度：整体进度按已完成 module 数占比
                indicator.fraction = (index + 1).toDouble() / modules.size
                indicator.text = "正在解析模块: ${module.name}"
                indicator.text2 = "(${index + 1}/${modules.size})"

                val moduleStart = System.currentTimeMillis()
                // ReadAction.nonBlocking 提交后台任务；wrapProgress+expireWhen 把取消/进度关联到
                // indicator（indicator 不是 Disposable，不能用 expireWith）。当前线程已是后台线程，
                // get()（Future）阻塞等待该片完成，读锁按片短持、批间让出 EDT。
                val groups = ReadAction.nonBlocking<List<ControllerGroup>> {
                    SpringControllerParser(project).parseModuleChunked(
                        module,
                        chunkSize = 50,
                        allowFallback = true, // 本项目必须走全文件遍历（索引搜索返回 0），靠分片防卡
                        onChunk = { EndpointSearchIndex.putChunk(module, it) }
                    )
                }.wrapProgress(indicator)
                    .expireWhen { indicator.isCanceled }
                    .submit(AppExecutorUtil.getAppExecutorService())
                    .get()
                totalGroups += groups.size
                totalEndpoints += groups.sumOf { it.endpoints.size }
                log.info("[MyHopeSync-Preheat] module=${module.name} groups=${groups.size} endpoints=${groups.sumOf { it.endpoints.size }} time=${System.currentTimeMillis() - moduleStart}ms")
            }

            val timeMs = System.currentTimeMillis() - start
            val heapAfter = usedHeapBytes()
            log.info(
                "[MyHopeSync-Preheat] modules=$moduleCount " +
                    "groups=$totalGroups " +
                    "endpoints=$totalEndpoints " +
                    "totalCached=${EndpointSearchIndex.getTotalCachedCount()} " +
                    "| time=${timeMs}ms " +
                    "| heap=${formatBytes(heapAfter)} (before=${formatBytes(heapBefore)} delta=${formatBytes(heapAfter - heapBefore)})"
            )
        }

        override fun onCancel() {
            val project = project
            if (project != null && !project.isDisposed) {
                EndpointSearchIndex.clearPartialPreheat(project)
            }
        }

        private fun usedHeapBytes(): Long {
            val r = Runtime.getRuntime()
            return r.totalMemory() - r.freeMemory()
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format("%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format("%.2f MB", mb)
        }
    }
}
