package io.parchment.ai.platform

import io.parchment.ai.ParchmentBuildConfig
import io.parchment.ai.harness.WorkspaceHarness
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Agent web access is independent of API-provider transport, credentials and proxy settings. */
internal object PublicWebTransport {
    private val client by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = validatedAddresses(Dns.SYSTEM.lookup(hostname))
            })
            .build()
    }
    private val cancellationWatcher by lazy {
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "parchment-web-cancellation").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }

    // IANA special-purpose ranges, checked 2026-11-08. Conservative exclusions include
    // documentation, IPv4 transition and IPv6 protocol-assignment ranges, not only RFC1918.
    private val deniedV4 = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
        "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4"
    ).map(::prefix)
    private val deniedV6 = listOf("2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20").map(::prefix)
    private val globalV6 = prefix("2000::/3")
    private data class Prefix(val bytes: ByteArray, val bits: Int)
    private fun prefix(raw: String) = Prefix(InetAddress.getByName(raw.substringBefore('/')).address, raw.substringAfter('/').toInt())
    private fun matches(bytes: ByteArray, prefix: Prefix): Boolean {
        if (bytes.size != prefix.bytes.size) return false
        val full = prefix.bits / 8
        for (index in 0 until full) if (bytes[index] != prefix.bytes[index]) return false
        val rest = prefix.bits % 8
        if (rest == 0) return true
        val mask = 0xff shl (8 - rest)
        return (bytes[full].toInt() and mask) == (prefix.bytes[full].toInt() and mask)
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        return when (bytes.size) {
            4 -> deniedV4.none { matches(bytes, it) }
            16 -> matches(bytes, globalV6) && deniedV6.none { matches(bytes, it) }
            else -> false
        }
    }

    internal fun validatedAddresses(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("Agent web tools can connect only to public internet addresses.")
        }
        // OkHttp connects directly to these validated addresses; there is no second DNS lookup.
        return addresses
    }

    internal fun validateUrl(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "Agent web URLs cannot include credentials." }
        require('%' !in url.host) { "Scoped IP addresses are not allowed." }
        if (':' in url.host || url.host.all { it.isDigit() || it == '.' }) {
            require(isPublicAddress(InetAddress.getByName(url.host))) { "Agent web tools cannot access private or reserved destinations." }
        }
        return url
    }

    private sealed interface Step {
        data class Redirect(val url: HttpUrl) : Step
        data class Text(val text: String) : Step
    }

    fun fetch(url: String, maxChars: Int, isCancelled: (() -> Boolean)?): Result<String> = runCatching {
        val limit = maxChars.coerceIn(500, 100_000)
        val rawLimit = (limit * 4).coerceIn(32_000, 400_000)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var target = validateUrl(url)
        repeat(6) {
            check(isCancelled?.invoke() != true) { "The owning run stopped or web permission was revoked." }
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Web request timed out." }
            val call = client.newCall(Request.Builder().url(target).get()
                .header("User-Agent", "ParchmentAI/${ParchmentBuildConfig.APP_VERSION}")
                .header("Accept", "text/markdown, text/html, application/xhtml+xml, application/json, text/plain;q=0.9, */*;q=0.8")
                .build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            val watcher = if (isCancelled == null) null else cancellationWatcher.scheduleAtFixedRate({
                runCatching { if (isCancelled.invoke()) call.cancel() }
            }, 100, 100, TimeUnit.MILLISECONDS)
            val step = try {
                call.execute().use { response ->
                    check(isCancelled?.invoke() != true) { "The owning run stopped or web permission was revoked." }
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location") ?: error("Redirect has no Location header.")
                        val redirected = target.resolve(location) ?: error("Redirect contains an invalid URL.")
                        Step.Redirect(validateUrl(redirected.toString()))
                    } else {
                        val body = response.body
                        val text = body.charStream().use { reader ->
                            val builder = StringBuilder(rawLimit.coerceAtMost(32_768))
                            val buffer = CharArray(4096)
                            while (builder.length < rawLimit) {
                                check(isCancelled?.invoke() != true) { "The owning run stopped or web permission was revoked." }
                                val count = reader.read(buffer, 0, minOf(buffer.size, rawLimit - builder.length))
                                if (count < 0) break
                                builder.append(buffer, 0, count)
                            }
                            builder.toString()
                        }
                        check(response.isSuccessful) { "HTTP ${response.code}: ${text.take(400)}" }
                        Step.Text(WorkspaceHarness.convertHtmlToReadableText(text, body.contentType()?.toString().orEmpty()).take(limit))
                    }
                }
            } finally { watcher?.cancel(false) }
            when (step) {
                is Step.Text -> return@runCatching step.text
                is Step.Redirect -> target = step.url
            }
        }
        error("Web request exceeded the redirect limit.")
    }
}
