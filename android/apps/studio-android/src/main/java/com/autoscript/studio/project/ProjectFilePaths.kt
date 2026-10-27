package com.autoscript.studio

import java.io.File

/** Canonical containment without java.nio.file, which is unavailable on Android 7. */
internal fun projectFileWithinRoot(root: File, relativePath: String): File {
    val canonicalRoot = root.canonicalFile
    val target = File(canonicalRoot, relativePath).canonicalFile
    val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
    require(target.path.startsWith(prefix, ignoreCase = File.separatorChar == '\\')) {
        "项目文件越过项目目录：$relativePath"
    }
    return target
}
