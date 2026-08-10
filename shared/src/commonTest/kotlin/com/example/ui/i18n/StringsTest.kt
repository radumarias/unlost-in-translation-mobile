package com.example.ui.i18n

import com.example.model.Language
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
    fun every_language_the_app_can_translate_into_has_its_own_catalogue() {
        // The device locale picks the UI language, and a device set to any of
        // the sixteen this app translates into should not land on English.
        Language.supportedLanguages
            .filter { it.code != "en" }
            .forEach { language ->
                assertNotEquals(
                    Strings(),
                    stringsFor(language.code),
                    "no catalogue for ${language.name} (${language.code})",
                )
            }
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
            strings.linkConnectedWith,
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

    // --- Every shipped locale ---

    @Test
    fun no_shipped_locale_leaves_a_field_at_its_english_default() {
        // A field left English inside an otherwise translated locale is almost
        // always an oversight rather than a decision, and it reads as a bug on
        // screen. The mechanism tolerates it; this test says we do not ship it.
        val english = fieldsOf(Strings())
        // Reported together rather than one locale at a time: fixing these one
        // failure per run would take fifteen runs.
        val offenders = localeCatalogs.mapValues { (_, locale) ->
            val fields = fieldsOf(locale)
            english.filterKeys { it !in SHARED_WITH_ENGLISH }
                .filter { (name, value) -> fields[name] == value }
                .keys.sorted()
        }.filterValues { it.isNotEmpty() }

        assertTrue(offenders.isEmpty(), "left at the English default: $offenders")
    }

    @Test
    fun every_shipped_locale_keeps_the_placeholders_its_field_needs() {
        // Dropping `{0}` in translation silently loses the interpolated name.
        val english = Strings()
        val templates = fieldsOf(english).filterValues { it.contains("{0}") }.keys
        localeCatalogs.forEach { (code, locale) ->
            val fields = fieldsOf(locale)
            templates.forEach { name ->
                assertTrue(
                    fields.getValue(name).contains("{0}"),
                    "$code dropped {0} from $name: ${fields[name]}",
                )
            }
            assertTrue(
                fields.getValue("playingTestSound").contains("{1}"),
                "$code dropped {1} from playingTestSound",
            )
        }
    }

    @Test
    fun no_shipped_locale_uses_a_glyph_the_wasm_canvas_cannot_draw() {
        // The web canvas ships no emoji font and has no arrow glyphs — the same
        // constraint that put `extendedGlyphs` in PlatformCapabilities.
        // Not the ellipsis: U+2026 is in ordinary font coverage and already
        // ships in `typeInLanguage`. Only the arrows and the middle dot are
        // missing on the canvas.
        val banned = listOf("→", "➔", "·")
        localeCatalogs.forEach { (code, locale) ->
            fieldsOf(locale).forEach { (name, value) ->
                banned.forEach { glyph ->
                    assertTrue(!value.contains(glyph), "$code.$name contains '$glyph'")
                }
            }
        }
    }

    @Test
    fun no_shipped_locale_has_a_blank_field() {
        localeCatalogs.forEach { (code, locale) ->
            fieldsOf(locale).forEach { (name, value) ->
                assertTrue(value.isNotBlank(), "$code.$name is blank")
            }
        }
    }

    @Test
    fun an_overridden_field_does_not_disturb_the_others() {
        // The point of the defaults: a locale translates what it has and the
        // rest stays English rather than going blank.
        val partial = Strings(cancel = "Anulează")
        assertEquals("Anulează", partial.cancel)
        assertEquals(Strings().send, partial.send)
        assertEquals(Strings().culturalWarning, partial.culturalWarning)
    }

    private companion object {
        /**
         * Field name to value, read off the data class's own `toString`.
         *
         * Kotlin/Wasm has no reflection, so this is the only way to walk the
         * catalogue generically instead of listing 120 accessors by hand. The
         * split is on a field boundary — `, name=` — not on plain `", "`,
         * because the values are prose full of commas.
         */
        fun fieldsOf(strings: Strings): Map<String, String> =
            strings.toString()
                .removePrefix("Strings(")
                .removeSuffix(")")
                .split(Regex(", (?=[a-zA-Z][A-Za-z0-9]*=)"))
                .associate { field ->
                    val name = field.substringBefore('=')
                    name to field.substring(name.length + 1)
                }

        /**
         * Fields a locale may legitimately leave identical to English, because
         * the word really is the same there — "Situation" in French and German,
         * "Details" in German and Dutch, "Casual" in Spanish and Italian — plus
         * the `Auto - {0}` shape, which stays ASCII in every Latin-script
         * locale. Everything not listed here has to actually be translated.
         */
        val SHARED_WITH_ENGLISH = setOf(
            "autoDetectedLabel", "logoDescription", "errorDescription",
            "culturalContextDescription", "detailsDescription",
            "suggestionDescription", "situationLabel", "helpDescription",
            "toneCasual", "toneFormal", "toneBusiness", "toneDirect",
            "situationGeneral", "situationMedical", "situationDating",
            "situationService", "micA", "micB",
        )
    }
}
