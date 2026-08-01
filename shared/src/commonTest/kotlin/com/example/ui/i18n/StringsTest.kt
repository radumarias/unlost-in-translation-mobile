package com.example.ui.i18n

import com.example.model.Situation
import com.example.model.Tone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StringsTest {

    // --- Locale resolution ---

    @Test
    fun an_unknown_language_falls_back_to_english() {
        assertEquals(Strings(), stringsFor("xx"))
    }

    @Test
    fun a_null_or_blank_language_falls_back_to_english() {
        assertEquals(Strings(), stringsFor(null))
        assertEquals(Strings(), stringsFor(""))
    }

    @Test
    fun a_regional_tag_resolves_on_its_language_alone() {
        // "pt-BR" and "pt" must land on the same catalogue — the device reports
        // the region, the catalogue is keyed by language.
        assertEquals(stringsFor("pt"), stringsFor("pt-BR"))
        assertEquals(stringsFor("en"), stringsFor("en-US"))
    }

    @Test
    fun the_language_code_is_matched_case_insensitively() {
        assertEquals(stringsFor("ro"), stringsFor("RO"))
    }

    @Test
    fun english_is_the_default_catalogue() {
        assertEquals("Translate", stringsFor("en").appName)
    }

    // --- Placeholder substitution ---

    @Test
    fun withArg_substitutes_the_single_placeholder() {
        assertEquals("Auto - French", Strings().autoDetectedLabel.withArg("French"))
    }

    @Test
    fun withArgs_substitutes_both_placeholders_positionally() {
        assertEquals(
            "Playing test sound to Pixel Buds in Spanish...",
            Strings().playingTestSound.withArgs("Pixel Buds", "Spanish"),
        )
    }

    @Test
    fun a_string_with_no_placeholder_is_unchanged_by_substitution() {
        assertEquals("Cancel", Strings().cancel.withArg("ignored"))
    }

    @Test
    fun every_placeholder_string_actually_carries_its_placeholder() {
        // A translation that drops `{0}` would silently lose the language name
        // it was supposed to interpolate.
        val strings = Strings()
        listOf(
            strings.autoDetectedLabel,
            strings.selectPreset,
            strings.typeInLanguage,
            strings.sendLanguageMessage,
            strings.youSaidIn,
            strings.translationIn,
            strings.assignUser1,
            strings.assignUser2,
            strings.playingTranslationToHeadset,
            strings.playingTestSound,
        ).forEach { assertTrue(it.contains("{0}"), "missing {0} in: $it") }
        assertTrue(strings.playingTestSound.contains("{1}"))
    }

    // --- Preset labels ---

    @Test
    fun every_tone_and_situation_preset_is_mapped_to_a_catalogue_field() {
        // In English several labels happen to equal their id ("Casual"), so the
        // only way to prove `preset()` really maps them — rather than falling
        // through to `else -> id` — is to give every field a distinct sentinel
        // and check none of them comes back as the raw id.
        val sentinels = Strings(
            toneAuto = "#toneAuto",
            toneCasual = "#toneCasual",
            toneFormal = "#toneFormal",
            toneBusiness = "#toneBusiness",
            tonePlayful = "#tonePlayful",
            toneEmpathetic = "#toneEmpathetic",
            toneDirect = "#toneDirect",
            situationGeneral = "#situationGeneral",
            situationMedical = "#situationMedical",
            situationDating = "#situationDating",
            situationService = "#situationService",
            situationEmergency = "#situationEmergency",
        )
        val labels = (Tone.presets + Situation.presets).map { preset ->
            val label = sentinels.preset(preset.id)
            assertNotEquals(preset.id, label, "no catalogue entry for ${preset.id}")
            assertTrue(Strings().preset(preset.id).isNotBlank(), "blank label for ${preset.id}")
            label
        }
        // …and that no two presets share one field.
        assertEquals(labels.size, labels.toSet().size, "two presets map to the same field")
    }

    @Test
    fun an_unknown_preset_id_renders_as_itself() {
        assertEquals("Sarcastic", Strings().preset("Sarcastic"))
    }

    @Test
    fun preset_labels_are_taken_from_the_catalogue_not_the_model() {
        val translated = Strings(toneCasual = "Décontracté", situationMedical = "Médical")
        assertEquals("Décontracté", translated.preset("Casual"))
        assertEquals("Médical", translated.preset("Medical"))
    }

    // --- Partial locales ---

    @Test
    fun an_overridden_field_does_not_disturb_the_others() {
        // The point of the defaults: a locale translates what it has and the
        // rest stays English rather than going blank.
        val partial = Strings(cancel = "Anulează")
        assertEquals("Anulează", partial.cancel)
        assertEquals(Strings().send, partial.send)
        assertEquals(Strings().culturalWarning, partial.culturalWarning)
    }
}
