package com.watermeter.ui.meters

import androidx.core.os.bundleOf
import androidx.navigation.NavDirections
import com.watermeter.R

/**
 * SafeArgs-style navigation directions for MetersFragment.
 * meterId передаётся как Int (argType="integer" в nav_graph).
 * Long→Int безопасно: Room auto-increment в пределах 30 счётчиков не превысит Int.MAX_VALUE.
 */
object MetersFragmentDirections {
    fun actionMetersFragmentToHistoryFragment(meterId: Long): NavDirections =
        object : NavDirections {
            override val actionId = R.id.action_metersFragment_to_historyFragment
            override val arguments = bundleOf("meterId" to meterId.toInt())
        }
}
