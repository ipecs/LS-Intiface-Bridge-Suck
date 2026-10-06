package kr.glora.lsintifacebridge

/** Semicolon framing also works when one command spans several WebSocket frames. */
class LovenseProtocol(private val command: (String, Int?) -> String) {
    private var pending = ""
    fun receive(text: String): String {
        pending += text
        if (pending.length > 4096) {
            pending = ""
            return "ERR;"
        }
        val replies = StringBuilder()
        while (';' in pending) {
            val token = pending.substringBefore(';').trim()
            pending = pending.substringAfter(';')
            if (token.isEmpty()) continue
            val name = token.substringBefore(':').lowercase()
            val value = token.substringAfter(':', "").trim().toIntOrNull()
            replies.append(command(name, value))
        }
        return replies.toString()
    }
}
