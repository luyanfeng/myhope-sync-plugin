package com.myhope.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware

/**
 * 「Copy Endpoint URL」右键菜单分组：光标位于 Spring Controller 方法的 HTTP 映射注解上时，
 * 在编辑器右键菜单顶部展示 Copy Full URL / Copy Path Only / Copy as cURL 三个动作。
 */
class CopyEndpointUrlGroup : DefaultActionGroup(), DumbAware {

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        arrayOf(CopyFullUrlAction(), CopyPathAction(), CopyCurlAction())

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isCursorOnSpringMethod(e)
    }
}
