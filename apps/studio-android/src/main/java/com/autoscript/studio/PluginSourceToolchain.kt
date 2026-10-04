package com.autoscript.studio

import com.autoscript.studio.compiler.SourceContract
import com.autoscript.studio.compiler.VisualLuaSource

/** The UI knows only the Studio toolchain, never a Lua VM or a runtime backend. */
internal val pluginSourceToolchain by lazy {
    VisualLuaSource(BlockCatalog.all.map { SourceContract(it.kind, it.nodeVersion, it.childBlocks, it.title) })
}
