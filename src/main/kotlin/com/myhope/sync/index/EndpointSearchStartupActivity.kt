package com.myhope.sync.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.StartupManager

/**
 * 项目打开后异步预热接口搜索索引：提前解析全部 module 的 Controller 缓存，
 * 用户打开搜索窗时直接命中、秒回。
 *
 * 实现为 [ProjectManagerListener]（而非 StartupActivity），并通过
 * [StartupManager.runAfterOpened] 在项目完全打开后再启动，**不参与 IDE 启动进度**，
 * 避免启动时因预热导致界面锁住无法操作。预热线程降为低优先级、后台执行。
 */
class EndpointSearchStartupActivity : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        // 挂 PSI 变更失效监听（按 module 精确失效，改哪模块只清哪模块；轻量，不阻塞）
        EndpointSearchIndex.attachInvalidationListener(project)
        // 等项目完全打开（启动进度结束后）再后台低优先级预热，不影响操作
        StartupManager.getInstance(project).runAfterOpened {
            ApplicationManager.getApplication().executeOnPooledThread(
                Runnable {
                    Thread.currentThread().priority = Thread.NORM_PRIORITY - 2 // 低优先级，不与 IDE 抢资源
                    ApplicationManager.getApplication().runReadAction(
                        Runnable { EndpointSearchIndex.resolveGroups(project, null) }
                    )
                }
            )
        }
    }
}
