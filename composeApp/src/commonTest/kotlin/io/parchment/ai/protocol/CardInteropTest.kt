package io.parchment.ai.protocol

import io.parchment.ai.model.CharacterOrScenarioCard
import io.parchment.ai.model.ContextFragment
import io.parchment.ai.model.FragmentActivationMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

class CardInteropTest {
    private val v3 = """{
      "spec": "chara_card_v3", "spec_version": "3.0",
      "data": {
        "name": "Ada", "description": "An engineer", "first_mes": "Hello",
        "nickname": "Countess", "creator": "someone", "character_version": "7",
        "assets": [{"type": "icon", "uri": "ccdefault:", "name": "main", "ext": "png"}],
        "extensions": {"vendor": {"depth": 4}},
        "group_only_greetings": ["Hi all"],
        "future_field": {"kept": true},
        "character_book": {
          "name": "Ada lore", "scan_depth": 9, "extensions": {"book": 1},
          "entries": [
            {"name": "Engine", "comment": "A note", "content": "Analytical", "keys": ["engine"], "insertion_order": 42, "priority": 3,
             "secondary_keys": ["steam"], "use_regex": true, "extensions": {"entry": 1}, "enabled": true, "constant": true},
            {"keys": [], "content": "   ", "insertion_order": 5, "enabled": true},
            {"name": "Lovelace", "content": "Notes", "keys": ["notes"], "insertion_order": 7, "enabled": true}
          ]
        }
      }
    }"""

    private fun exported(card: CharacterOrScenarioCard) =
        CardAndPromptCodec.cardDocument(card, v3 = card.sourceFormat.startsWith("chara_card_v3")).toString()

    private fun data(json: String) = Json.parseToJsonElement(json).jsonObject.getValue("data").jsonObject
    private fun entries(data: JsonObject) = data.getValue("character_book").jsonObject.getValue("entries").jsonArray.map { it.jsonObject }

    @Test fun unknownV3FieldsAndAssetsSurviveAnEditedRoundTrip() {
        val card = CardAndPromptCodec.parseCardJson(v3).copy(name = "Ada L.", personality = "Precise")
        val document = Json.parseToJsonElement(exported(card)).jsonObject
        assertEquals("chara_card_v3", document.getValue("spec").jsonPrimitive.content)
        assertEquals("3.0", document.getValue("spec_version").jsonPrimitive.content)
        val data = document.getValue("data").jsonObject
        assertEquals("Ada L.", data.getValue("name").jsonPrimitive.content)
        assertEquals("Precise", data.getValue("personality").jsonPrimitive.content)
        assertEquals("Countess", data.getValue("nickname").jsonPrimitive.content)
        assertEquals("7", data.getValue("character_version").jsonPrimitive.content)
        assertEquals("ccdefault:", data.getValue("assets").jsonArray[0].jsonObject.getValue("uri").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"vendor":{"depth":4}}"""), data.getValue("extensions"))
        assertEquals(Json.parseToJsonElement("""{"kept":true}"""), data.getValue("future_field"))
        assertEquals(Json.parseToJsonElement("""["Hi all"]"""), data.getValue("group_only_greetings"))
        assertEquals(9, data.getValue("character_book").jsonObject.getValue("scan_depth").jsonPrimitive.int)
    }

    @Test fun loreEntriesKeepTheirUnknownFieldsThroughEditsAndDeletions() {
        val card = CardAndPromptCodec.parseCardJson(v3)
        assertEquals(2, card.embeddedFragments.size)
        val edited = card.copy(embeddedFragments = listOf(card.embeddedFragments[0].copy(content = "Edited")))
        val exported = entries(data(exported(edited)))
        assertEquals(2, exported.size, "The deleted entry is gone; the blank one the app never showed stays")
        val engine = exported[0]
        assertEquals("Edited", engine.getValue("content").jsonPrimitive.content)
        assertEquals(42, engine.getValue("insertion_order").jsonPrimitive.int)
        assertEquals(3, engine.getValue("priority").jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), engine["use_regex"])
        assertEquals("A note", engine.getValue("comment").jsonPrimitive.content, "A note distinct from the name is not overwritten")
        assertEquals(5, exported[1].getValue("insertion_order").jsonPrimitive.int)
        assertTrue(exported.none { it["name"] == JsonPrimitive("Lovelace") })
    }

    @Test fun mutingAConstantEntryDoesNotForgetThatItWasConstant() {
        val card = CardAndPromptCodec.parseCardJson(v3)
        val muted = card.copy(embeddedFragments = card.embeddedFragments.map { it.copy(mode = FragmentActivationMode.MUTED) })
        val engine = entries(data(exported(muted))).first()
        assertEquals(JsonPrimitive(false), engine["enabled"])
        assertEquals(JsonPrimitive(true), engine["constant"])
    }

    @Test fun removingEveryLoreEntryRemovesTheBook() {
        val noBlank = v3.replace("""{"keys": [], "content": "   ", "insertion_order": 5, "enabled": true},""", "")
        val card = CardAndPromptCodec.parseCardJson(noBlank).copy(embeddedFragments = emptyList())
        assertFalse("character_book" in data(exported(card)))
    }

    @Test fun aNewCardHasTheFieldsOtherAppsRequire() {
        val card = CharacterOrScenarioCard(name = "New", embeddedFragments = listOf(ContextFragment(id = "mine", title = "T", content = "C", keywords = listOf("k"), mode = FragmentActivationMode.KEYWORD)))
        val document = Json.parseToJsonElement(exported(card)).jsonObject
        assertEquals("chara_card_v2", document.getValue("spec").jsonPrimitive.content)
        val data = document.getValue("data").jsonObject
        assertEquals("", data.getValue("creator").jsonPrimitive.content)
        assertEquals("", data.getValue("character_version").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), data.getValue("extensions"))
        val book = data.getValue("character_book").jsonObject
        assertEquals(JsonObject(emptyMap()), book.getValue("extensions"))
        val entry = book.getValue("entries").jsonArray[0].jsonObject
        assertEquals(JsonObject(emptyMap()), entry.getValue("extensions"))
        assertEquals(0, entry.getValue("insertion_order").jsonPrimitive.int)
        assertEquals(JsonPrimitive(false), entry["use_regex"])
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun aPngCarriesAV2ChunkAndARealV3Chunk() {
        val png = CardAndPromptCodec.exportCardToPngBytes(CardAndPromptCodec.parseCardJson(v3))
        val chunks = CardAndPromptCodec.extractPngTextChunks(png)
        fun spec(key: String): String {
            val root = Json.parseToJsonElement(Base64.decode(chunks.getValue(key)).decodeToString()).jsonObject
            return root.getValue("spec").jsonPrimitive.content
        }
        assertEquals("chara_card_v2", spec("chara"))
        assertEquals("chara_card_v3", spec("ccv3"))
        val imported = assertIs<CardAndPromptCodec.ImportResult.CardImported>(CardAndPromptCodec.importFromBytes("a.png", png).getOrThrow())
        assertEquals("chara_card_v3", imported.card.sourceFormat)
        assertEquals(2, imported.card.embeddedFragments.size)
    }

    @Test fun objectShapedLoreEntriesMapToTheRightRawEntry() {
        val json = """{"spec":"chara_card_v2","data":{"name":"A","character_book":{"entries":{
            "0":{"name":"One","content":"first","keys":["a"],"priority":1},
            "1":{"name":"Two","content":"second","keys":["b"],"priority":2}}}}}"""
        val card = CardAndPromptCodec.parseCardJson(json)
        val exported = assertIs<JsonArray>(data(exported(card)).getValue("character_book").jsonObject["entries"])
        assertEquals(listOf(1, 2), exported.map { it.jsonObject.getValue("priority").jsonPrimitive.int })
    }

    @Test fun anOversizedCardStillImportsItsFieldsWithoutStoringTheRawCopy() {
        val huge = """{"spec":"chara_card_v3","data":{"name":"Big","first_mes":"Hi","assets":[{"uri":"${"x".repeat(600_000)}"}]}}"""
        val card = CardAndPromptCodec.parseCardJson(huge)
        assertEquals("Big", card.name)
        assertEquals("", card.sourceData)
        assertFalse("assets" in data(exported(card)))
    }

    @Test fun aMalformedStoredCopyCannotBreakExport() {
        val card = CharacterOrScenarioCard(name = "A", sourceData = "{not json", sourceFormat = "chara_card_v3")
        assertEquals("A", data(exported(card)).getValue("name").jsonPrimitive.content)
    }
}
