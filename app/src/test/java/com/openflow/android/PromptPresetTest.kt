package com.openflow.android

import com.openflow.android.ai.AiPipelineClient
import com.openflow.android.data.PreferencesManager
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
        assertTrue(naturalPrompt.contains("RIPENSAMENTI") || naturalPrompt.contains("autocorrezioni"))
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
}
