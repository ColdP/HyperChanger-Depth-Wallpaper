// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper

internal data class HomeDockIconSettings(val enabled: Boolean = false, val offset: Int = 0) {
    fun normalized() = copy(offset = offset.coerceIn(-60, 30))
}
