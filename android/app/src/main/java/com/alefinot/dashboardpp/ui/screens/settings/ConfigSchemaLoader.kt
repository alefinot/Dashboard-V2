package com.alefinot.dashboardpp.ui.screens.settings

import android.content.Context

/**
 * The generated schema asset, off disk and into memory.
 *
 * Kept apart from [ConfigSchemaAsset] so the parser stays free of `android.*` and
 * can be tested on the JVM against the asset file directly.
 */
fun loadConfigSchema(context: Context): ConfigSchema? = try {
    context.assets.open(ConfigSchemaAsset.FILE).use { input ->
        ConfigSchemaAsset.load(input.readBytes().toString(Charsets.UTF_8))
    }
} catch (e: Exception) {
    null
}
