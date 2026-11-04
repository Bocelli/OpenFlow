package com.openflow.android

import com.openflow.android.ai.AiPipelineClient
import com.openflow.android.data.PromptPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPresetTest {

    @Test
    fun testNaturalPresetHasStrictConstraints() {
        val naturalPrompt = PromptPreset.NATURAL.prompt

        assertTrue(naturalPrompt.contains("ESCLUSIVAMENTE"))
        assertTrue(naturalPrompt.contains("intercalari", ignoreCase = true))
        assertTrue(naturalPrompt.contains("PARAGRAFI", ignoreCase = true))
        assertTrue(naturalPrompt.contains("VIRGOLETTE", ignoreCase = true))
    }

    @Test
    fun testAllPresetsHaveNonEmptyDescriptionsAndPrompts() {
        for (preset in PromptPreset.values()) {
            assertTrue(preset.title.isNotBlank())
            assertTrue(preset.description.isNotBlank())
            if (preset != PromptPreset.RAW) {
                assertTrue(preset.prompt.isNotBlank())
            }
        }
    }

    @Test
    fun testSanitizerRemovesDuplicatesWithCommasAndAccents() {
        // Balbettii con virgole
        val inputWithCommas = "sto, sto facendo questo test"
        val output = AiPipelineClient.sanitizeAndFormatText(inputWithCommas)
        assertEquals("Sto facendo questo test", output)

        // Balbettii con parole accentate (es. però, però)
        val inputWithAccents = "però, però non so se funziona"
        val outputAccents = AiPipelineClient.sanitizeAndFormatText(inputWithAccents)
        assertEquals("Però non so se funziona", outputAccents)

        // Balbettii di frasi da 2 parole
        val inputTwoWords = "sto facendo, sto facendo i test"
        val outputTwoWords = AiPipelineClient.sanitizeAndFormatText(inputTwoWords)
        assertEquals("Sto facendo i test", outputTwoWords)
    }

    @Test
    fun testSanitizerCapitalizesAfterPunctuationAndQuotes() {
        val input = "primo punto. secondo punto! terzo punto? «quarto punto tra virgolette»."
        val output = AiPipelineClient.sanitizeAndFormatText(input)
        assertEquals("Primo punto. Secondo punto! Terzo punto? «Quarto punto tra virgolette».", output)

        val inputNewline = "primo paragrafo.\n\nsecondo paragrafo."
        val outputNewline = AiPipelineClient.sanitizeAndFormatText(inputNewline)
        assertEquals("Primo paragrafo.\n\nSecondo paragrafo.", outputNewline)
    }

    @Test
    fun testSanitizerFixesPunctuationSpacingAndQuotes() {
        val input = "parola , virgola . due punti : « citazione »"
        val output = AiPipelineClient.sanitizeAndFormatText(input)
        assertEquals("Parola, virgola. Due punti: «citazione»", output)
    }

    @Test
    fun testSanitizerFixesPhoneticsAndRemovesSpuriousPrefixes() {
        val inputBroadcast = "Broadcast: sto dicendo a bici per prova"
        val outputBroadcast = AiPipelineClient.sanitizeAndFormatText(inputBroadcast)
        assertEquals("Sto dicendo A B C per prova", outputBroadcast)

        val inputMessage = "Message: ciao Michele"
        val outputMessage = AiPipelineClient.sanitizeAndFormatText(inputMessage)
        assertEquals("Ciao Michele", outputMessage)
    }
}
