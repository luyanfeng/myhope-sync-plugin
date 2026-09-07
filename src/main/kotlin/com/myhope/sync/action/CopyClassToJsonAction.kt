package com.myhope.sync.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiType
import com.myhope.sync.psi.SpringControllerParser
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 「Copy to JSON」：把光标处的 Java 类型（请求 JavaBean 参数/字段/返回类型/类）递归展开为
 * JSON 模板（真实占位值，与 @RequestBody 同步模板一致），复制到剪贴板。
 *
 * 例如 `@Valid PaperlessRewritePDFInputVO vo` —— 光标在类型名或变量名上 →
 * `{"file":"","orderType":0,"items":[{"sku":"","qty":0}],"amount":0.0}`。
 *
 * 目标类型解析与可见性判断复用 [resolveTargetPsiType] / [isCursorOnJavaType]
 * （类声明头部、类型元素、变量声明名三类位置均支持）。
 */
class CopyClassToJsonAction : AnAction(), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isCursorOnJavaType(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return

        val jsonResult: String? = ReadAction.compute<String?, RuntimeException> {
            val type: PsiType = resolveTargetPsiType(editor, project) ?: return@compute null
            SpringControllerParser(project).resolveJsonTemplate(type)
        }
        val json: String = jsonResult ?: return

        writeToClipboard(json)
    }

    private fun writeToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }
}