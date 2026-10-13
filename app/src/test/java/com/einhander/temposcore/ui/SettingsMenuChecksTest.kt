package com.einhander.temposcore.ui

import com.einhander.temposcore.applySettingsMenuChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMenuChecksTest {
    private val radioGroups = listOf(listOf(1, 2), listOf(4, 5), listOf(7, 8))
    private val toggleIds = listOf(3, 9)

    /** Matches verified Android MenuItemImpl exclusive-check behavior, including false selecting the item. */
    private inner class MenuChecks {
        val checked = (radioGroups.flatten() + toggleIds).associateWith { false }.toMutableMap()
        val writes = ArrayList<Pair<Int, Boolean>>()

        fun setChecked(id: Int, value: Boolean) {
            writes.add(id to value)
            val exclusiveGroup = radioGroups.firstOrNull { id in it }
            if (exclusiveGroup != null) {
                for (peer in exclusiveGroup) checked[peer] = peer == id
            } else checked[id] = value
        }
    }

    @Test fun everyRestoredChoiceCombinationChecksOnlySelectedRadioItemsAndBothToggleStates() {
        for (naming in radioGroups[0]) for (accidental in radioGroups[1]) for (duration in radioGroups[2]) {
            for (testMode in listOf(false, true)) for (pitchColors in listOf(false, true)) {
                val menu = MenuChecks()
                val selected = listOf(naming, accidental, duration)
                val toggles = mapOf(3 to testMode, 9 to pitchColors)
                applySettingsMenuChecks(selected, toggles, menu::setChecked)
                for (group in radioGroups) {
                    assertEquals(selected.filter { it in group }, group.filter { menu.checked[it] == true })
                }
                for ((id, value) in toggles) assertEquals(value, menu.checked[id])
                assertTrue(menu.writes.filter { it.first in radioGroups.flatten() }.all { it.second })
            }
        }
    }

    @Test fun liveMenuSyncUpdatesAllGroupsAndCanTurnBothTogglesOffAgain() {
        val menu = MenuChecks()
        applySettingsMenuChecks(listOf(1, 4, 7), mapOf(3 to true, 9 to true), menu::setChecked)
        applySettingsMenuChecks(listOf(2, 5, 8), mapOf(3 to false, 9 to false), menu::setChecked)
        assertEquals(setOf(2, 5, 8), menu.checked.filterValues { it }.keys)
        applySettingsMenuChecks(listOf(1, 5, 8), mapOf(3 to false, 9 to true), menu::setChecked)
        assertEquals(setOf(1, 5, 8, 9), menu.checked.filterValues { it }.keys)
    }

    @Test fun reopenedMenuMatchesLatestChoiceRatherThanDefaultOrLastRadioOption() {
        val current = MenuChecks()
        applySettingsMenuChecks(listOf(2, 5, 8), mapOf(3 to true, 9 to true), current::setChecked)
        val latestChoices = listOf(1, 4, 7)
        val latestToggles = mapOf(3 to false, 9 to true)
        applySettingsMenuChecks(latestChoices, latestToggles, current::setChecked)
        val reopened = MenuChecks()
        applySettingsMenuChecks(latestChoices, latestToggles, reopened::setChecked)
        assertEquals(current.checked, reopened.checked)
        assertEquals(setOf(1, 4, 7, 9), reopened.checked.filterValues { it }.keys)
    }
}
