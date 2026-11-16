package com.github.avbox.osc.ui.navbar

import com.github.avbox.osc.ui.WindowWidthClass
import com.github.avbox.osc.ui.theme.GLASS_BACKDROP_BAND_MARGIN_DP

object NavMetrics {

    // (v1.6.4 rebuild trigger: the first CI run died on a transient pip
    // download error in the Chaquopy task, before any code compiled.)
    const val MARGIN_DP = 12

    const val BAR_CROSS_DP = 64

    const val SLOT_MAIN_DP = 56

    const val FLOATING_OVERLAY_DP = BAR_CROSS_DP + MARGIN_DP

    const val SURFACE_RAIL_WIDTH_DP = 80

    const val BAND_EXTENT_DP = FLOATING_OVERLAY_DP + GLASS_BACKDROP_BAND_MARGIN_DP

    fun axisFor(widthClass: WindowWidthClass): NavAxis =
        if (widthClass == WindowWidthClass.Compact) NavAxis.Horizontal else NavAxis.Vertical

    fun actionSlotFor(tabCount: Int): Int = tabCount / 2

    fun slotIndexOfTab(tab: Int, actionSlots: Set<Int>): Int {
        if (tab < 0) return tab
        var slot = tab
        while (true) {
            val shifted = tab + actionSlots.count { it <= slot }
            if (shifted == slot) return slot
            slot = shifted
        }
    }

    fun tabIndexOfSlot(slot: Int, actionSlots: Set<Int>): Int =
        slot - actionSlots.count { it < slot }

    fun reserveDp(glassEnabled: Boolean, axis: NavAxis): Int = when {
        axis == NavAxis.Vertical -> if (glassEnabled) FLOATING_OVERLAY_DP else SURFACE_RAIL_WIDTH_DP
        glassEnabled -> FLOATING_OVERLAY_DP
        else -> 0
    }
}
