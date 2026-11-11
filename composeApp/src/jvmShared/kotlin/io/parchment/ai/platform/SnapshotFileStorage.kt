package io.parchment.ai.platform

import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.DefaultModelConfig
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.ThemeConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/** Reads only a chat's id: the rest of the JSON is syntax-checked and skipped, not built into objects. */
@Serializable
private class ChatIdentity(val id: String = "")

/** Shared Android/desktop snapshot validation, recovery and atomic persistence. */
internal class SnapshotFileStorage(
    private val directory: () -> File,
    private val extraLegacySettings: () -> List<File> = { emptyList() }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val unsafeSessionCharacters = Regex("[^a-zA-Z0-9._-]")

    private fun decodeSettings(raw: String): AppStateSnapshot? = runCatching {
        val migrated = raw.replace("\"BYTEDANCE_ARK\"", "\"OPENAI_RESPONSES\"")
        val root = json.parseToJsonElement(migrated) as? JsonObject ?: return@runCatching null
        if (root.keys.none { it == "profiles" || it == "assistants" || it == "sessions" }) return@runCatching null
        json.decodeFromString<AppStateSnapshot>(migrated)
    }.getOrNull()

    private fun validChat(raw: String, sessionId: String): Boolean = runCatching {
        json.decodeFromString<ChatSession>(raw).id == sessionId
    }.getOrDefault(false)

    private fun read(file: File): String? = runCatching {
        if (file.isFile) AtomicFileStorage.readText(file) else null
    }.getOrNull()

    private fun isInitialSettings(state: AppStateSnapshot): Boolean =
        state.profiles.none { it.apiKey.isNotBlank() || it.defaultModel.isNotBlank() } &&
            state.assistants.none { it.id != "asst-default" || it.systemPrompt.isNotBlank() } &&
            state.sessions.none { it.messages.isNotEmpty() } &&
            state.savedCards.isEmpty() && state.systemPromptPresets.isEmpty() && state.globalWorldBook.isEmpty() &&
            state.preferences == PreferencesConfig() && state.defaultModels == DefaultModelConfig() &&
            state.theme == ThemeConfig() && state.activeSessionId in setOf("", "sess-initial")

    private fun settingsCandidates(): List<File> {
        val root = directory()
        val backups = File(root, "backups")
        return listOf(
            File(root, "parchment-settings.json"), File(root, "parchment-settings.json.tmp"),
            File(backups, "parchment-settings.prev.json"), File(backups, "parchment-settings.user-backup.json"),
            File(root, "parchment-settings.prev.json"), File(root, "parchment-settings.user-backup.json"),
            File(root, "parchment-state.json"), File(root, "parchment-state.json.tmp"),
            File(root, "parchment-state.prev.json"), File(root, "parchment-state.user-backup.json")
        ) + extraLegacySettings()
    }

    @Synchronized
    fun loadSettings(): String? {
        val candidates = settingsCandidates()
        var partial: String? = null
        for (candidate in candidates) {
            val raw = read(candidate) ?: continue
            if (raw.isBlank()) continue
            if (partial == null) partial = raw
            val decoded = decodeSettings(raw) ?: continue
            if (isInitialSettings(decoded)) {
                for (backup in candidates.filter { it.name.endsWith("user-backup.json") }) {
                    val backupRaw = read(backup) ?: continue
                    val backupState = decodeSettings(backupRaw) ?: continue
                    if (!isInitialSettings(backupState)) return backupRaw
                }
            }
            return raw
        }
        // Let shared partial-JSON recovery inspect the original without replacing it with defaults.
        return partial
    }

    @Synchronized
    fun saveSettings(raw: String) {
        val incoming = decodeSettings(raw) ?: throw IOException("Settings snapshot is invalid.")
        val root = directory()
        val target = File(root, "parchment-settings.json")
        val backups = File(root, "backups")
        val existingRaw = read(target)
        if (target.isFile && existingRaw == null) throw IOException("Unable to read the existing settings snapshot.")
        val existing = existingRaw?.let(::decodeSettings)
        if (isInitialSettings(incoming) && settingsCandidates().any { candidate ->
                val content = read(candidate)
                content != null && content.isNotBlank() && (decodeSettings(content)?.let { !isInitialSettings(it) } ?: true)
            }) {
            throw IOException("Existing settings need recovery; refusing to replace them with initial defaults.")
        }
        if (existingRaw != null && existing == null) {
            val corrupt = File(backups, "parchment-settings.corrupt.json")
            if (!corrupt.exists()) AtomicFileStorage.copy(target, corrupt)
        }
        AtomicFileStorage.writeText(
            target, raw, previousBackup = File(backups, "parchment-settings.prev.json").takeIf { existing != null }
        )
        if (!isInitialSettings(incoming)) AtomicFileStorage.copy(target, File(backups, "parchment-settings.user-backup.json"))
        // Legacy embedded state stays available until every chat has been migrated by the shared writer.
        for (name in listOf("parchment-settings.prev.json", "parchment-settings.user-backup.json")) {
            val source = File(root, name)
            val destination = File(backups, name)
            if (source.isFile && !destination.exists()) AtomicFileStorage.copy(source, destination)
            if (destination.isFile) deleteFile(source)
        }
    }

    private fun chatFolder(sessionId: String): File {
        val root = File(directory(), "chats").canonicalFile
        val safeId = sessionId.trim().replace(unsafeSessionCharacters, "_")
        require(safeId.isNotEmpty() && safeId != "." && safeId != "..") { "Invalid chat identifier." }
        val folder = File(root, safeId)
        check(folder.canonicalFile.toPath().startsWith(root.toPath()) && folder.canonicalFile == folder.absoluteFile) {
            "Chat directory must remain inside application storage."
        }
        return folder
    }

    fun chatDirectory(sessionId: String): File = chatFolder(sessionId)

    @Synchronized
    fun loadChat(sessionId: String): String? {
        if (sessionId.isBlank()) return null
        val folder = chatFolder(sessionId)
        val target = File(folder, "chat.json")
        val legacy = File(folder.parentFile, folder.name + ".json")
        val legacyTemp = File(folder.parentFile, folder.name + ".json.tmp")
        val current = read(target)?.takeIf { validChat(it, sessionId) }
        val legacyRaw = read(legacy)?.takeIf { validChat(it, sessionId) }
        if (legacyRaw != null && (current == null || legacy.lastModified() > target.lastModified())) {
            // Failure retains the migration source and still makes its complete content readable.
            runCatching { saveChat(sessionId, legacyRaw) }
            return legacyRaw
        }
        if (current != null) return current
        for (candidate in listOf(File(folder, "chat.json.tmp"), File(folder, "chat.prev.json"), legacyTemp)) {
            val raw = read(candidate)?.takeIf { validChat(it, sessionId) } ?: continue
            return raw
        }
        return null
    }

    @Synchronized
    fun loadAllChats(): List<String> = chats().toList()

    /**
     * Every stored chat, read one at a time so a caller that decodes and drops each one never holds them all.
     * Finding a folder's real id decodes only the id; loadChat still validates the full chat.
     */
    fun chats(): Sequence<String> = sequence {
        val chats = File(directory(), "chats")
        val ids = chats.listFiles().orEmpty().mapNotNull { entry ->
            when {
                entry.isDirectory -> entry.name
                entry.name.endsWith(".json.tmp") -> entry.name.removeSuffix(".json.tmp")
                entry.name.endsWith(".json") -> entry.name.removeSuffix(".json")
                else -> null
            }
        }.distinct()
        for (id in ids) {
            val loaded = runCatching {
                val folder = File(chats, id)
                val candidates = listOf(File(folder, "chat.json"), File(folder, "chat.json.tmp"), File(folder, "chat.prev.json"),
                    File(chats, "$id.json"), File(chats, "$id.json.tmp"))
                val actualId = candidates.firstNotNullOfOrNull { candidate ->
                    read(candidate)?.let { raw -> runCatching { json.decodeFromString<ChatIdentity>(raw).id }.getOrNull() }
                        ?.takeIf { it.isNotBlank() && it.trim().replace(unsafeSessionCharacters, "_") == id }
                } ?: return@runCatching null
                loadChat(actualId)
            }.getOrNull()
            if (loaded != null) yield(loaded)
        }
    }

    @Synchronized
    fun saveChat(sessionId: String, raw: String) {
        require(validChat(raw, sessionId)) { "Chat snapshot does not match its identifier." }
        val folder = chatFolder(sessionId)
        val target = File(folder, "chat.json")
        val existing = read(target)
        if (target.isFile && existing == null) throw IOException("Unable to read the existing chat snapshot.")
        val existingId = existing?.let { runCatching { json.decodeFromString<ChatSession>(it).id }.getOrNull() }
        if (existingId != null && existingId != sessionId) throw IOException("Chat identifier collides with an existing storage path.")
        val validExisting = existing != null && validChat(existing, sessionId)
        if (existing != null && !validExisting) {
            val corrupt = File(folder, "chat.corrupt.json")
            if (!corrupt.exists()) AtomicFileStorage.copy(target, corrupt)
        }
        AtomicFileStorage.writeText(target, raw, previousBackup = File(folder, "chat.prev.json").takeIf { validExisting })
        deleteFile(File(folder.parentFile, folder.name + ".json"))
        deleteFile(File(folder.parentFile, folder.name + ".json.tmp"))
    }

    @Synchronized
    fun deleteChat(sessionId: String) {
        if (sessionId.isBlank()) return
        val folder = chatFolder(sessionId)
        AtomicFileStorage.deleteTree(folder.toPath())
        deleteFile(File(folder.parentFile, folder.name + ".json"))
        deleteFile(File(folder.parentFile, folder.name + ".json.tmp"))
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Unable to remove obsolete snapshot: " + file.name)
    }
}
