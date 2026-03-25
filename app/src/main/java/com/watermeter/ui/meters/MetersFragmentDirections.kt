package com.watermeter.ui.meters

import androidx.navigation.NavDirections
import com.watermeter.R

/**
 * SafeArgs-style navigation directions for MetersFragment.
 * (Replaces auto-generated class when using Navigation SafeArgs plugin)
 */
object MetersFragmentDirections {
    fun actionMetersFragmentToHistoryFragment(meterId: Long): NavDirections =
        object : NavDirections {
            override val actionId = R.id.action_metersFragment_to_historyFragment
            override val arguments = androidx.core.os.bundleOf("meterId" to meterId)
        }
}
