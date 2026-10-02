package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTemplateSaveDialogTest {
    @Test
    fun listsExistingImageFoldersAndParents() {
        assertEquals(
            listOf("", "界面", "界面/首页"),
            imageResourceFolders(
                listOf("assets/images/界面/首页/按钮.png", "dictionaries/main.asglyph"),
            ),
        )
    }
}
