package io.parchment.ai.network

import com.sun.net.httpserver.HttpServer
import io.parchment.ai.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.*

class ModelTextMediaGuardTest {
    private class Host(private val reply: (Int) -> String) : AutoCloseable {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                paths += exchange.requestURI.path
                val isChat = exchange.requestMethod == "POST"
                val body = (if (isChat) reply(address.port) else "missing").toByteArray()
                exchange.responseHeaders.set("Content-Type", if (isChat) "text/event-stream" else "text/plain")
                exchange.sendResponseHeaders(if (isChat) 200 else 404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val port: Int get() = server.address.port
        override fun close() = server.stop(0)
    }

    private fun stream(text: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    @Test fun modelTextReachesTheProvidersOwnServerButNotOtherPrivateAddresses() {
        Host { "" }.use { intranet ->
            Host { own -> stream("![a](http://127.0.0.1:$own/own.png) ![b](http://127.0.0.1:${intranet.port}/secret.png)") }.use { provider ->
                val profile = ProviderProfile("fixture", "Fixture", ProviderProtocol.CUSTOM, customDialect = CUSTOM_DIALECT_OPENAI_CHAT,
                    baseUrl = "http://127.0.0.1:${provider.port}", defaultModel = "fixture-model", thinkingEffort = "none")
                runBlocking {
                    withTimeout(20_000) {
                        AiProviderEngine.streamGeneration(profile = profile, session = ChatSession("chat", "Fixture", 1L, 1L, profile.id),
                            historyNodes = listOf(MessageNode("user", MessageRole.USER, listOf(MessageVariant("v", content = "Hello"))))).toList()
                    }
                }
                assertTrue("/own.png" in provider.paths, "A local image server must keep working: ${provider.paths}")
                assertEquals(emptyList(), intranet.paths, "Model text must not reach other private addresses")
            }
        }
    }
}
