package io.parchment.ai.platform

import java.net.InetAddress
import kotlin.test.*

class PublicWebTransportTest {
    @Test fun mediaDownloadsRefusePrivateAddressesAndOversizedBodies() {
        assertTrue(PublicWebTransport.fetchBytes("http://127.0.0.1:1/a.png", 1000, null).isFailure)
        assertTrue(PublicWebTransport.fetchBytes("file:///etc/passwd", 1000, null).isFailure)
        val body = ByteArray(200_000) { it.toByte() }
        assertContentEquals(body, PublicWebTransport.readBounded(body.inputStream(), body.size, null))
        assertFails { PublicWebTransport.readBounded(body.inputStream(), body.size - 1, null) }
        assertFails { PublicWebTransport.readBounded(body.inputStream(), body.size) { true } }
    }

    @Test fun privateReservedAndTransitionDestinationsAreRejected() {
        val blocked = listOf(
            "0.0.0.0", "10.1.2.3", "100.64.2.3", "127.0.0.1", "169.254.169.254", "172.31.255.255",
            "192.0.0.170", "192.0.2.1", "192.168.0.1", "198.18.0.1", "198.51.100.2", "203.0.113.3",
            "224.0.0.1", "255.255.255.255", "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1",
            "64:ff9b::7f00:1", "2001::1", "2001:2::1", "2001:db8::1", "2002:7f00:1::1", "3fff::1"
        )
        blocked.forEach { assertFalse(PublicWebTransport.isPublicAddress(InetAddress.getByName(it)), it) }
        listOf("1.1.1.1", "8.8.8.8", "93.184.216.34", "2606:4700:4700::1111", "2001:4860:4860::8888").forEach {
            assertTrue(PublicWebTransport.isPublicAddress(InetAddress.getByName(it)), it)
        }
    }

    @Test fun mixedDnsAnswersCannotBypassTheConnectionGuard() {
        val public = InetAddress.getByName("93.184.216.34")
        val private = InetAddress.getByName("127.0.0.1")
        assertFails { PublicWebTransport.validatedAddresses(listOf(public, private)) }
        assertFails { PublicWebTransport.validatedAddresses(emptyList()) }
        val validated = listOf(public)
        assertSame(validated, PublicWebTransport.validatedAddresses(validated))
    }

    @Test fun redirectsCredentialsAndUnsupportedSchemesAreValidatedBeforeUse() {
        val original = PublicWebTransport.validateUrl("https://example.com/docs")
        assertEquals("example.com", original.host)
        val destination = original.resolve("//127.0.0.1:8080/secrets")!!
        assertFails { PublicWebTransport.validateUrl(destination.toString()) }
        assertFails { PublicWebTransport.validateUrl("https://user:password@example.com") }
        assertFails { PublicWebTransport.validateUrl("file:///private/file") }
        assertFails { PublicWebTransport.validateUrl("ftp://example.com/file") }
        assertFails { PublicWebTransport.validateUrl("http://[::1]/") }
    }

    @Test fun revokedAndPrivateFetchesFailBeforeConnecting() {
        val private = PublicWebTransport.fetch("http://127.0.0.1:1", 1000, null)
        assertTrue(private.isFailure)
        assertContains(private.exceptionOrNull()?.message.orEmpty(), "private or reserved")
        val cancelled = PublicWebTransport.fetch("https://never-resolve.invalid", 1000) { true }
        assertTrue(cancelled.isFailure)
        assertContains(cancelled.exceptionOrNull()?.message.orEmpty(), "owning run")
    }
}
