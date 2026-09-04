package com.myhope.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware

/**
 * 「Copy Endpoint URL」右键菜单分组：光标位于 Spring Controller 方法的 HTTP 映射注解上时，
 * 在编辑器右键菜单顶部展示 Copy Full URL / Copy Path Only / Copy as cURL 三个动作。
 *
 * 子动作由 plugin.xml 的 <action> 子节点声明（标准做法），此处不 override getChildren()，
 * 避免硬编码实例与 XML 声明冲突导致子菜单不渲染。仅用 update() 控制整组可见性。
 */
class CopyEndpointUrlGroup : DefaultActionGroup(), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isCursorOnSpringMethod(e)
    }
}
