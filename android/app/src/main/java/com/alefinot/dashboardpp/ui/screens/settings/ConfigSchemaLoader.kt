package com.alefinot.dashboardpp.ui.screens.settings

import android.content.Context

fun loadConfigSchema(context: Context): ConfigSchema? = try {
    context.assets.open(ConfigSchemaAsset.FILE).use { input ->
        ConfigSchemaAsset.load(input.readBytes().toString(Charsets.UTF_8))
    }
} catch (e: Exception) {
    null
}
