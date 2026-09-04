package com.myhope.sync.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * IDEA 启动（project 打开）后异步预热接口搜索索引：
 * 提前解析全部 module 的 Controller 缓存，用户打开搜索窗时直接命中、秒回。
 *
 * [StartupActivity.Background] 在后台执行，不阻塞 UI 线程，也不影响其它线程/功能。
 */
class EndpointSearchStartupActivity : StartupActivity.Background {

    override fun runActivity(project: Project) {
        // 挂 PSI 变更失效监听（按 module 精确失效，改哪模块只清哪模块）
        EndpointSearchIndex.attachInvalidationListener(project)
        // 后台异步预热全部 module
        ApplicationManager.getApplication().executeOnPooledThread(
            Runnable {
                ApplicationManager.getApplication().runReadAction(
                    Runnable { EndpointSearchIndex.resolveGroups(project, null) }
                )
            }
        )
    }
}
