package com.einhander.temposcore.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Checks shipped layout/bindings without Android stubs or an emulator. */
class CompactControlContractTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private fun source(path: String): File = listOf(File(path), File("app/$path"))
        .first { it.isFile }
    private fun layout() = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(source("src/main/res/layout/activity_main.xml"))
    private fun children(parent: Element): List<Element> = (0 until parent.childNodes.length)
        .mapNotNull { parent.childNodes.item(it) as? Element }
    private fun id(element: Element) = element.getAttributeNS(android, "id").substringAfter('/')

    @Test fun collapsedActionsFollowMetricsWithExpansionLast() {
        val document = layout()
        val elements = document.getElementsByTagName("*")
        fun find(id: String) = (0 until elements.length).map { elements.item(it) as Element }
            .first { this.id(it) == id }
        assertEquals(listOf("compactControls", "compactMetricsContainer", "compactTestControls"),
            children(find("compactContent")).map { id(it) })
        assertEquals("expandPanelButton", id(children(find("compactPanel")).last()))
        assertEquals("end|center_vertical", find("compactTestControls").getAttributeNS(android, "layout_gravity"))
        for (name in listOf("compactResetButton", "compactTestPlayPauseButton", "compactTestRestartButton",
            "expandPanelButton", "tempoDecreaseButton", "tempoIncreaseButton",
            "compactTempoDecreaseButton", "compactTempoIncreaseButton")) {
            assertEquals(name, "48dp", find(name).getAttributeNS(android, "layout_width"))
            assertEquals(name, "48dp", find(name).getAttributeNS(android, "layout_height"))
        }
    }

    @Test fun bothTrimSurfacesUseSameClicksAndRenderedState() {
        val activity = source("src/main/java/com/einhander/temposcore/MainActivity.kt").readText()
        for (prefix in listOf("tempo", "compactTempo")) {
            assertTrue(activity.contains("binding.${prefix}DecreaseButton.setOnClickListener(decreaseTempo)"))
            assertTrue(activity.contains("binding.${prefix}IncreaseButton.setOnClickListener(increaseTempo)"))
            assertTrue(activity.contains("binding.${prefix}AdjustmentText.text = label"))
        }
        assertTrue(activity.contains("val decreaseTempo = View.OnClickListener { changeTempoAdjustment(-1) }"))
        assertTrue(activity.contains("val increaseTempo = View.OnClickListener { changeTempoAdjustment(1) }"))
        assertTrue(activity.contains("putInt(KEY_TEMPO_ADJUSTMENT, next).apply()"))
        assertTrue(activity.contains("NativeAudioBridge.setExpectedBpm(expectedBpm, next.toDouble())"))
    }
}
