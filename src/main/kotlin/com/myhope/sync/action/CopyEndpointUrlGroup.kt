package com.myhope.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware

/**
 * 「Copy Endpoint URL」右键菜单分组：光标位于 Spring Controller 方法的 HTTP 映射注解上时，
 * 在编辑器右键菜单顶部展示 Copy Full URL / Copy Path Only / Copy as cURL / Copy to JSON。
 * 光标位于 Java 类型/类声明头部时（Copy to JSON 的目标位置）同样展示分组。
 *
 * 子动作由 plugin.xml 的 <action> 子节点声明（标准做法），此处不 override getChildren()，
 * 避免硬编码实例与 XML 声明冲突导致子菜单不渲染。仅用 update() 控制整组可见性，
 * 各子动作自身 update 决定具体显示哪一项（Copy URL 系列仅 Spring 方法上显示，Copy to JSON 仅类型位置显示）。
 */
class CopyEndpointUrlGroup : DefaultActionGroup(), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isCursorOnSpringMethod(e) || isCursorOnJavaType(e)
    }
}
