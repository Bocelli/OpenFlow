package com.openflow.android

import com.openflow.android.data.PromptPreset
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPresetTest {

    @Test
    fun testNaturalPresetHasStrictConstraints() {
        val naturalPrompt = PromptPreset.NATURAL.prompt

        // Verifica che ci siano le istruzioni critiche di Wispr Flow
        assertTrue(naturalPrompt.contains("ESCLUSIVAMENTE"))
        assertTrue(naturalPrompt.contains("intercalari"))
        assertTrue(naturalPrompt.contains("autocorrezioni"))
    }

    @Test
    fun testAllPresetsHaveNonEmptyDescriptionsAndPrompts() {
        for (preset in PromptPreset.values()) {
            assertTrue(preset.title.isNotBlank())
            assertTrue(preset.description.isNotBlank())
            assertTrue(preset.prompt.isNotBlank())
        }
    }
}
