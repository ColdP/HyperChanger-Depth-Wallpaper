// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.btm_m.depthwallpaper

internal fun readRoProductMarketName(): String {
    val value = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java, String::class.java)
            .invoke(null, "ro.product.marketname", "") as? String
    }.getOrNull().orEmpty().trim()
    return value.ifBlank { android.os.Build.MODEL.orEmpty().ifBlank { "Android" } }
}
