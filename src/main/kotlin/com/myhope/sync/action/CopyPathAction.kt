package com.myhope.sync.action

/**
 * 「Copy Path Only」：只复制 path（@PathVariable 保留 {xxx} 占位），如
 * /api/users/{id}
 */
class CopyPathAction : EndpointUrlAction() {
    override fun getOutputText(
        info: EndpointInfo,
        url: String,
        urlWithQuery: String,
        curl: String
    ): String = info.fullPath
}
