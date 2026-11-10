package io.parchment.ai.ui

import io.parchment.ai.model.AppStateSnapshot
import io.parchment.ai.model.ChatSession
import io.parchment.ai.model.PreferencesConfig
import io.parchment.ai.model.withImportedPermissions
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileSettingsTest {
    private val chat = ChatSession("c", "t", 1L, 1L, "p")

    @Test fun profileNameFillsChatsWithoutTheirOwn() {
        val profile = PreferencesConfig(profileName = "Mira")
        assertEquals("Mira", chat.withProfileName(profile).userName)
        assertEquals("Captain", chat.copy(userName = "Captain").withProfileName(profile).userName)
        assertEquals("User", chat.withProfileName(PreferencesConfig()).userName)
    }

    @Test fun importedSettingsCannotPointThePictureAtAnotherFile() {
        val imported = AppStateSnapshot(preferences = PreferencesConfig(profileName = "Mira", profilePicturePath = "C:/secret/photo.png"))
        val safe = imported.withImportedPermissions().preferences
        assertEquals("", safe.profilePicturePath)
        assertEquals("Mira", safe.profileName)
    }
}
