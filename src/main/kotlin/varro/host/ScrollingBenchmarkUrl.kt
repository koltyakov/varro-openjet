package varro.host

import java.net.URI

/** The diagnostic view loads a locally built fixture, without the chat host bridge. */
object ScrollingBenchmarkUrl {
    const val DEFAULT = "http://127.0.0.1:4186/?scenario=huge-content-transcript&singleTall=1"

    fun validate(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "[::1]", "::1")) {
            "Use an HTTP fixture URL on 127.0.0.1 or [::1]."
        }
        require(uri.port in 1..65535 && uri.rawUserInfo == null && uri.rawFragment == null) {
            "The fixture URL must include a port and no credentials or fragment."
        }
        return uri.toASCIIString()
    }
}
