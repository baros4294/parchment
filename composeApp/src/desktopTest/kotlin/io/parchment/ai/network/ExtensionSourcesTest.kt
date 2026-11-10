package io.parchment.ai.network

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ExtensionSourcesTest {
    private val page = """{"servers":[
      {"server":{"name":"a/hosted","title":"Hosted","description":"Plain","remotes":[{"type":"streamable-http","url":"https://a.example/mcp"}]}},
      {"server":{"name":"b/token","description":"Needs a key","remotes":[{"type":"streamable-http","url":"https://b.example/mcp","headers":[{"name":"Authorization","isRequired":true,"isSecret":true}]}]}},
      {"server":{"name":"c/custom","remotes":[{"type":"streamable-http","url":"https://c.example/mcp","headers":[{"name":"X-Api-Key","isRequired":true}]}]}},
      {"server":{"name":"d/old","remotes":[{"type":"sse","url":"https://d.example/sse"}]}},
      {"server":{"name":"e/plain-http","remotes":[{"type":"streamable-http","url":"http://e.example/mcp"}]}},
      {"server":{"name":"f/package","packages":[{"registryType":"npm","identifier":"x"}]}},
      {"server":{"name":"a/hosted","remotes":[{"type":"streamable-http","url":"https://a.example/older"}]}}
    ],"metadata":{"nextCursor":"next-page","count":7}}"""

    @Test fun onlyServersThatCanBeConnectedAreListed() {
        val parsed = parseRegistryPage(page)
        assertEquals(listOf("a/hosted", "b/token"), parsed.servers.map { it.name })
        assertFalse(parsed.servers[0].needsToken)
        assertTrue(parsed.servers[1].needsToken)
        assertEquals("next-page", parsed.nextCursor)
        assertEquals(RegistryPage(emptyList(), ""), parseRegistryPage("[]"))
    }

    @Test fun githubLinksResolveToAFolder() {
        assertEquals(GitHubFolder("o", "r", "", ""), parseGitHubFolder("https://github.com/o/r"))
        assertEquals(GitHubFolder("o", "r", "main", "skills/pdf"), parseGitHubFolder("https://github.com/o/r/tree/main/skills/pdf/"))
        assertEquals(GitHubFolder("o", "r", "v1", "skills/pdf"), parseGitHubFolder("https://github.com/o/r/blob/v1/skills/pdf/SKILL.md?plain=1"))
        listOf("https://evil.example/o/r", "http://github.com/o/r", "https://github.com/o", "https://github.com/o/r/issues/1",
            "https://github.com/o/r/tree/main/../x", "https://github.com.evil.example/o/r").forEach { assertNull(parseGitHubFolder(it), it) }
    }

    private val skill = "---\nname: demo\ndescription: Does a thing\n---\nBody"
    private val api = "https://api.github.com/repos/o/r/contents/skills/demo?ref=main"

    private fun entry(name: String, type: String = "file", path: String = "skills/demo/$name", url: String = "https://raw.githubusercontent.com/o/r/main/$name") =
        """{"name":"$name","type":"$type","path":"$path","download_url":${if (type == "file") "\"$url\"" else "null"}}"""

    private class Sandbox(val responses: Map<String, ByteArray>, val installed: Boolean = false) {
        val written = linkedMapOf<String, String>()
        suspend fun install(folder: GitHubFolder) = installSkillFromGitHub(folder, { installed },
            { path, text -> written[path] = text; Result.success("ok") },
            { url, _ -> responses[url] ?: error("Unexpected request: $url") })
    }

    private val folder = GitHubFolder("o", "r", "main", "skills/demo")
    private fun raw(name: String) = "https://raw.githubusercontent.com/o/r/main/$name"

    @Test fun aSkillIsCopiedIntoTheWorkspace() = runBlocking<Unit> {
        val sandbox = Sandbox(mapOf(
            api to "[${entry("SKILL.md")},${entry("notes.md")},${entry("logo.png")}]".encodeToByteArray(),
            raw("SKILL.md") to skill.encodeToByteArray(), raw("notes.md") to "Notes".encodeToByteArray(),
            raw("logo.png") to byteArrayOf(0x89.toByte(), 0, 1, 2)
        ))
        assertEquals("Installed demo, without 1 binary file.", sandbox.install(folder).getOrThrow())
        assertEquals(setOf(".parchment/skills/demo/SKILL.md", ".parchment/skills/demo/notes.md"), sandbox.written.keys)
    }

    @Test fun theManifestIsWrittenLastSoAFailedCopyCanBeRetried() = runBlocking<Unit> {
        val responses = mapOf(api to "[${entry("SKILL.md")},${entry("notes.md")}]".encodeToByteArray(),
            raw("SKILL.md") to skill.encodeToByteArray(), raw("notes.md") to "Notes".encodeToByteArray())
        val order = mutableListOf<String>()
        installSkillFromGitHub(folder, { false }, { path, _ -> order += path; Result.success("ok") }, { url, _ -> responses.getValue(url) }).getOrThrow()
        assertEquals(".parchment/skills/demo/SKILL.md", order.last())
    }

    @Test fun nothingIsWrittenUnlessTheWholeSkillIsValid() = runBlocking<Unit> {
        fun listing(vararg entries: String) = mapOf(api to "[${entries.joinToString(",")}]".encodeToByteArray())
        val cases = listOf(
            Sandbox(listing(entry("notes.md")) + (raw("notes.md") to "x".encodeToByteArray())) to "no SKILL.md",
            Sandbox(listing(entry("SKILL.md")) + (raw("SKILL.md") to "No header".encodeToByteArray())) to "valid header",
            Sandbox(listing(entry("SKILL.md")) + (raw("SKILL.md") to "---\nname: demo\n---\nBody".encodeToByteArray())) to "no description",
            Sandbox(listing(entry("SKILL.md")) + (raw("SKILL.md") to "---\nname: ../../evil\ndescription: x\n---\n".encodeToByteArray())) to "unusable name",
            Sandbox(listing(entry("SKILL.md")) + (raw("SKILL.md") to skill.encodeToByteArray()), installed = true) to "already installed",
            Sandbox(listing(entry("..%2Fx"), entry("SKILL.md"))) to "unsafe",
            Sandbox(listing(entry("../../../AGENTS.md"), entry("SKILL.md"))) to "unsafe",
            Sandbox(listing(entry(".."), entry("SKILL.md"))) to "unsafe",
            Sandbox(listing(entry("SKILL.md", url = "https://evil.example/SKILL.md"))) to "unexpected address",
            Sandbox(mapOf(api to """{"name":"file"}""".encodeToByteArray())) to "not a folder"
        )
        for ((sandbox, reason) in cases) {
            val failure = assertFails(reason) { sandbox.install(folder).getOrThrow() }
            assertTrue(reason in failure.message.orEmpty(), "$reason: ${failure.message}")
            assertTrue(sandbox.written.isEmpty(), reason)
        }
    }
}
