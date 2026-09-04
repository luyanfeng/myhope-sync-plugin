package com.myhope.sync.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.PsiTreeChangeListener
import com.myhope.sync.model.ControllerGroup
import com.myhope.sync.psi.SpringControllerParser
import java.util.concurrent.ConcurrentHashMap

/**
 * 接口搜索索引：按 module 粒度缓存已解析的 [ControllerGroup]，避免每次搜索都全量重扫 PSI。
 *
 * - 缓存 key 用 [Module] 实例（跨 project 的 Module 实例不同，同名 module 不串缓存）。
 * - 失效：监听 [PsiManager.PSI_CHANGED_TOPIC]，任一 PSI 变更反查受影响文件所属 module，
 *   只 remove 该 module 的缓存项（其余 module 缓存保留，预热不回退）。这正是"改哪个文件只失效哪个模块"。
 * - 重建：未命中时前台现场 `parseModule`（用户搜的接口基本是未刚改的模块，直接命中缓存秒回；
 *   撞上刚改的模块时按需重扫该模块，其它模块不受影响）。
 * - 预热：由 [EndpointSearchStartupActivity] 在 IDEA 启动后异步执行，不影响其它线程/功能。
 */
object EndpointSearchIndex {

    private val cache = ConcurrentHashMap<Module, List<ControllerGroup>>()

    /** 指定 module 的 Controller 分组（命中缓存直接返回；未命中现场解析该 module）。 */
    fun get(project: Project, module: Module): List<ControllerGroup> {
        cache[module]?.let { return it }
        // PSI 索引访问（JavaFullClassNameIndex 等）必须在 read action 内进行；
        // 未命中时在 read action 内解析，命中走内层 return 不访问 PSI。
        val groups = ApplicationManager.getApplication().runReadAction(Computable {
            try {
                SpringControllerParser(project).parseModule(module)
            } catch (e: Exception) {
                emptyList()
            }
        })
        cache[module] = groups
        return groups
    }

    /**
     * 解析目标 module 集合的 Controller 分组（优先命中缓存）。
     * [moduleNames] 为 null 表示全部 module；非 null 时只取名称命中的 module。
     * 调用方需自行保证在读操作内调用（本方法内部不包 read action）。
     */
    fun resolveGroups(project: Project, moduleNames: Set<String>?): List<ControllerGroup> {
        val modules = ModuleManager.getInstance(project).modules
        val target = if (moduleNames != null) modules.filter { it.name in moduleNames } else modules.toList()
        return target.flatMap { get(project, it) }
    }

    /**
     * 为当前 project 订阅 PSI 变更，精确失效受影响 module 的缓存。
     * 监听随 project 释放自动断开（以 project 为 parentDisposable）。
     */
    fun attachInvalidationListener(project: Project) {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeListener {
            override fun childAdded(event: PsiTreeChangeEvent) = invalidate(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = invalidate(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = invalidate(event)
            override fun childrenChanged(event: PsiTreeChangeEvent) = invalidate(event)
            override fun propertyChanged(event: PsiTreeChangeEvent) = invalidate(event)
            override fun childMoved(event: PsiTreeChangeEvent) = invalidate(event)
            override fun beforeChildAddition(event: PsiTreeChangeEvent) = Unit
            override fun beforeChildRemoval(event: PsiTreeChangeEvent) = Unit
            override fun beforeChildReplacement(event: PsiTreeChangeEvent) = Unit
            override fun beforeChildMovement(event: PsiTreeChangeEvent) = Unit
            override fun beforeChildrenChange(event: PsiTreeChangeEvent) = Unit
            override fun beforePropertyChange(event: PsiTreeChangeEvent) = Unit
        }, project)
    }

    /** 从 PSI 变更事件反查受影响文件所属 module，只失效该 module 的缓存项。 */
    private fun invalidate(event: PsiTreeChangeEvent) {
        // 回调在写动作（PSI 变更）内执行，只做 VFS 反查 + map remove，均为轻量操作且无需读锁，
        // 避免在写锁内再取读锁造成死锁风险。
        val psiFile = event.file
            ?: event.parent?.containingFile
            ?: event.child?.containingFile
            ?: return
        val project = psiFile.project ?: return
        val virtualFile = psiFile.virtualFile ?: return
        // ProjectFileIndex 基于 VFS，非 PSI 语义，可在写线程直接调用
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(virtualFile) ?: return
        cache.remove(module)
    }

    /** 清空全部缓存（如需要强制刷新时调用）。 */
    fun clear() = cache.clear()
}
