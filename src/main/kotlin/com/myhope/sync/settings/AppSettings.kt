package com.myhope.sync.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

@State(
    name = "com.myhope.sync.settings.AppSettings",
    storages = [Storage("myhope-sync-settings.xml")]
)
class AppSettings : PersistentStateComponent<AppSettings.State> {

    data class State(
        var serverUrl: String = "http://localhost:3462",
        var language: String = "en",       // "en" | "zh"
        var logLevel: String = "INFO", // 调试日志级别
        var apiToken: String = "",             // X-API-Token
        var instanceId: String = "",           // X-MyHope-Instance-ID（自动生成，mh_xxx）
        var accessInstanceId: String = "",     // 绑定后返回
        var userAppId: String = "",            // 绑定后返回
        var myhopeUserId: String = "",         // 绑定用用户标识
        var myhopeUserName: String = ""        // 绑定用显示名
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var serverUrl: String
        get() = myState.serverUrl
        set(value) { myState.serverUrl = value }

    var language: String
        get() = myState.language
        set(value) { myState.language = value }

    var logLevel: String
        get() = myState.logLevel
        set(value) { myState.logLevel = value }

    var apiToken: String
        get() = myState.apiToken
        set(value) { myState.apiToken = value }

    var instanceId: String
        get() = myState.instanceId
        set(value) { myState.instanceId = value }

    var accessInstanceId: String
        get() = myState.accessInstanceId
        set(value) { myState.accessInstanceId = value }

    var userAppId: String
        get() = myState.userAppId
        set(value) { myState.userAppId = value }

    var myhopeUserId: String
        get() = myState.myhopeUserId
        set(value) { myState.myhopeUserId = value }

    var myhopeUserName: String
        get() = myState.myhopeUserName
        set(value) { myState.myhopeUserName = value }

    companion object {
        fun getInstance(): AppSettings {
            return ApplicationManager.getApplication().getService(AppSettings::class.java)
        }
    }

    /** 获取或自动生成实例 ID（mh_ + 随机，首次生成后持久化） */
    fun getOrCreateInstanceId(): String {
        if (myState.instanceId.isBlank()) {
            myState.instanceId = "mh_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        }
        return myState.instanceId
    }

    /** 获取或自动生成 MyHope 用户标识（首次生成 idea_ 前缀并持久化） */
    fun getOrCreateMyHopeUserId(): String {
        if (myState.myhopeUserId.isBlank()) {
            myState.myhopeUserId = "idea_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            myState.myhopeUserName = System.getProperty("user.name") ?: "IDEA Plugin"
        }
        return myState.myhopeUserId
    }
}