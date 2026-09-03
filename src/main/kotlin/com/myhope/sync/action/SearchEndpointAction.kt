package com.myhope.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.myhope.sync.toolwindow.showEndpointSearch

/**
 * 「端点搜索」动作：弹出全局端点搜索。
 *
 * 弹窗打开时不预扫/不缓存，输入后经防抖在后台实时解析当前项目的 Controller 并过滤。
 */
class SearchEndpointAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        showEndpointSearch(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PROJECT) != null
    }
}
