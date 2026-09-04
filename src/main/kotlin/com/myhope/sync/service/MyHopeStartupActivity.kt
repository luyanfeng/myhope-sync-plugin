package com.myhope.sync.service

import com.intellij.notification.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.myhope.sync.myhope.MyHopeClient
import com.myhope.sync.settings.EffectiveSettings
import com.myhope.sync.settings.getEffectiveSettings
import com.myhope.sync.settings.writeBackBinding
import com.myhope.sync.util.I18n
import java.net.URI

/**
 * IDEA 启动时校验 MyHope 绑定 / token 有效性。
 *
 * 使用**有效配置**（[getEffectiveSettings]：全局默认模板 + 项目级覆盖）判断 serverUrl/apiToken
 * 及绑定状态；instanceId 保持全局单例（由 AppSettings.getOrCreateInstanceId 生成，经有效配置读取）。
 * 绑定回写统一经 [writeBackBinding] 分层写回（禁止直接写全局 settings 字段）。
 *
 * myhope 认证为静态 X-API-Token（无 refresh_token、无 JWT 过期刷新机制），
 * 因此启动时不再做 token 刷新，改为校验绑定状态：
 *
 * - 有效 serverUrl 或 apiToken 空白 → 静默返回，不打扰。
 * - 已配置 → 后台异步调 [MyHopeClient.tokenInfo]：
 *   - 成功（HTTP 200，服务端已校验绑定）→ 若返回 accessInstanceId / userAppId 非空则回写绑定（避免同步时重复绑定）；静默成功。
 *   - 失败（未绑定 / 被顶掉 / 401）→ 区分「服务端不可达」和「token 无效」：
 *     - 仅当用户已绑定过（有效 accessInstanceId 非空）且 tokenInfo 失败才提示；
 *     - 从未绑定 → 静默跳过（避免首次安装弹通知打扰）。
 */
class MyHopeStartupActivity : StartupActivity.Background {

    override fun runActivity(project: Project) {
        val effective = getEffectiveSettings(project)
        if (effective.serverUrl.isBlank()) return
        if (effective.apiToken.isBlank()) return  // 未配置 token，不打扰
        // 后台异步校验 token 有效性
        Thread(
            Runnable { verifyTokenInBackground(effective, project) },
            "MyHope-token-check"
        ).apply { isDaemon = true; start() }
    }

    /**
     * 后台校验 token：成功回写绑定信息；失败区分"绑定被顶/未绑定"与"网络故障"。
     *
     * 禁止自动抢回绑定：曾绑定过且服务端可达但 token 无效 → 弹"绑定可能已被顶掉"通知，
     * 由用户点击「重新绑定」按钮后才执行 bindMyHope（见 [rebindBinding]）。
     */
    private fun verifyTokenInBackground(effective: EffectiveSettings, project: Project) {
        val client = MyHopeClient(
            serverUrl = effective.serverUrl,
            apiToken = effective.apiToken,
            instanceId = effective.instanceId,
            myhopeUserId = effective.myhopeUserId,
            myhopeUserName = effective.myhopeUserName
        )
        try {
            var valid = false
            val tokenInfoResult = client.tokenInfo()
            if (tokenInfoResult.isSuccess) {
                // HTTP 200 即绑定有效（服务端已校验 token+instance）；勿依赖本地 TokenInfo.valid（恒 false）
                valid = true
                val info = tokenInfoResult.getOrThrow()
                // 回写绑定信息，避免同步时重复绑定（分层：仅写回 acc/app）
                writeBackBinding(project, info.accessInstanceId, info.userAppId)
            }
            if (!valid) {
                notifyInvalidToken(effective, project)
            }
        } catch (_: Exception) {
            notifyInvalidToken(effective, project)
        } finally {
            client.close()
        }
    }

    // ========================================================================
    //  通知 — 区分服务端不可达 vs Token 无效
    // ========================================================================

    /**
     * token 无效处理：仅当用户已绑定过（有效 accessInstanceId 非空）才提示；
     * 从未绑定 → 静默跳过，避免首次安装打扰。
     * 曾绑定过且服务端可达 → 弹"绑定可能已被顶掉"通知（带「重新绑定」按钮，不自动抢回）；
     * 服务端不可达 → 走网络故障提示（无按钮）。
     */
    private fun notifyInvalidToken(effective: EffectiveSettings, project: Project) {
        val everBound = effective.accessInstanceId.isNotBlank() || effective.userAppId.isNotBlank()
        if (!everBound) return

        if (isServerReachable(effective.serverUrl)) {
            // 服务端可达但 token 无效 → 绑定可能被其他实例顶掉，交由用户决定是否重新绑定
            notifyBindingLost(project)
        } else {
            // 服务端不可达 → 网络问题
            notifyServerUnreachable(project, effective.serverUrl)
        }
    }

    /** 弹"绑定已被顶掉"通知，携带【重新绑定】按钮（点击才重新绑定，不自动抢回）。 */
    private fun notifyBindingLost(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification(
                    "MyHope Sync",
                    I18n.message(project, "notification.bindingLost.title"),
                    I18n.message(project, "notification.bindingLost.content"),
                    NotificationType.WARNING
                ).addAction(NotificationAction.createSimple(
                    I18n.message(project, "notification.bindingLost.action.rebind")
                ) { rebindBinding(project) }),
                project
            )
        }
    }

    /**
     * 用户在通知上点击【重新绑定】：后台线程重建 client 并 bindMyHope，
     * 成功 → writeBackBinding + 成功通知；失败 → 错误通知（含异常消息）。
     */
    private fun rebindBinding(project: Project) {
        Thread(Runnable {
            val effective = getEffectiveSettings(project)
            val client = MyHopeClient(
                serverUrl = effective.serverUrl,
                apiToken = effective.apiToken,
                instanceId = effective.instanceId,
                myhopeUserId = effective.myhopeUserId,
                myhopeUserName = effective.myhopeUserName
            )
            try {
                val result = client.bindMyHope()
                if (result.isSuccess) {
                    val b = result.getOrNull()
                    if (b != null && b.ok &&
                        !b.accessInstanceId.isNullOrBlank() && !b.userAppId.isNullOrBlank()
                    ) {
                        writeBackBinding(project, b.accessInstanceId, b.userAppId)
                        ApplicationManager.getApplication().invokeLater {
                            Notifications.Bus.notify(
                                Notification(
                                    "MyHope Sync",
                                    I18n.message(project, "notification.rebindSuccess.title"),
                                    I18n.message(project, "notification.rebindSuccess.content"),
                                    NotificationType.INFORMATION
                                ),
                                project
                            )
                        }
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            Notifications.Bus.notify(
                                Notification(
                                    "MyHope Sync",
                                    I18n.message(project, "notification.bindingLost.title"),
                                    I18n.message(
                                        project, "notification.rebindFailed.content",
                                        result.exceptionOrNull()?.message ?: "服务端未返回 access_instance_id/user_app_id"
                                    ),
                                    NotificationType.ERROR
                                ),
                                project
                            )
                        }
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        Notifications.Bus.notify(
                            Notification(
                                "MyHope Sync",
                                I18n.message(project, "notification.bindingLost.title"),
                                I18n.message(
                                    project, "notification.rebindFailed.content",
                                    result.exceptionOrNull()?.message ?: "绑定失败"
                                ),
                                NotificationType.ERROR
                            ),
                            project
                        )
                    }
                }
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    Notifications.Bus.notify(
                        Notification(
                            "MyHope Sync",
                            I18n.message(project, "notification.bindingLost.title"),
                            I18n.message(project, "notification.rebindFailed.content", e.message ?: e.javaClass.simpleName),
                            NotificationType.ERROR
                        ),
                        project
                    )
                }
            } finally {
                client.close()
            }
        }, "MyHope-rebind").apply { isDaemon = true; start() }
    }

    private fun notifyServerUnreachable(project: Project, serverUrl: String) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification(
                    "MyHope Sync",
                    I18n.message(project, "notification.serverUnreachable.title"),
                    I18n.message(project, "notification.serverUnreachable.content", serverUrl),
                    NotificationType.WARNING
                ),
                project
            )
        }
    }

    // ========================================================================
    //  连通性检测
    // ========================================================================

    /**
     * 检测 MyHope 服务端是否可达（不依赖 HTTP 响应状态码，只检查 TCP 连接）。
     */
    private fun isServerReachable(serverUrl: String): Boolean {
        return try {
            val uri = URI.create(serverUrl)
            val host = uri.host ?: return false
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme == "https" -> 443
                else -> 80
            }
            val socket = java.net.Socket()
            try {
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                true
            } finally {
                socket.close()
            }
        } catch (_: Exception) {
            false
        }
    }
}