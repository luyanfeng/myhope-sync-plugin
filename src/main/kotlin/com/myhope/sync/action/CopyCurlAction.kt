package com.myhope.sync.action

/**
 * 「Copy as cURL」：生成完整 curl 命令，如
 * curl -X GET 'http://127.0.0.1:8080/api/users?name={name}'
 * curl -X POST 'http://127.0.0.1:8080/api/users' -H 'Content-Type: application/json' -d '{}'
 */
class CopyCurlAction : EndpointUrlAction() {
    override fun getOutputText(
        info: EndpointInfo,
        url: String,
        urlWithQuery: String,
        curl: String
    ): String = curl
}
