package com.myhope.sync.action

/**
 * 「Copy Full URL」：复制完整 HTTP 地址（含查询串），如
 * http://127.0.0.1:8080/api/users?name={name}&id=1
 */
class CopyFullUrlAction : EndpointUrlAction() {
    override fun getOutputText(
        info: EndpointInfo,
        url: String,
        urlWithQuery: String,
        curl: String
    ): String = urlWithQuery
}
