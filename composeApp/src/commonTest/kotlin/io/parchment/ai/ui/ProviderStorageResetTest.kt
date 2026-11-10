package io.parchment.ai.ui

import io.parchment.ai.model.AppStateSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderStorageResetTest {
    private fun settings(marker: String = "") = """{$marker"profiles":[
        {"id":"prof-gemini-interactions","useStatefulChaining":true,"storeServerSide":false},
        {"id":"prof-openai-responses","useStatefulChaining":false,"storeServerSide":true},
        {"id":"my-gateway","useStatefulChaining":true,"storeServerSide":true}
    ]}"""

    private fun stored(raw: String) = Json.parseToJsonElement(migrateLegacyProviderJson(raw)).jsonObject["profiles"]!!.jsonArray.associate {
        val profile = it.jsonObject
        profile["id"]!!.jsonPrimitive.content to (
            profile["useStatefulChaining"]!!.jsonPrimitive.boolean || profile["storeServerSide"]!!.jsonPrimitive.boolean
        )
    }

    @Test fun olderSettingsResetOnlyTheBuiltInProfiles() {
        assertEquals(
            mapOf("prof-gemini-interactions" to false, "prof-openai-responses" to false, "my-gateway" to true),
            stored(settings())
        )
    }

    @Test fun storageTurnedOnAfterTheResetIsKept() {
        assertTrue(stored(settings(""""providerStorageReset":true,""")).getValue("prof-gemini-interactions"))
        val saved = Json { encodeDefaults = true }.encodeToString(AppStateSnapshot())
        assertTrue("\"providerStorageReset\":true" in saved, "Saved settings must carry the marker")
    }
}
