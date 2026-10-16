package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageTemplateSaveDialogTest {
    @Test fun showsOnlyImmediateChildrenIncludingEmptyDirectories() {
        val folders = listOf("", "账号", "界面", "界面/首页", "界面/首页/按钮", "界面/空目录")
        assertEquals(listOf("界面", "账号"), imageChildFolders(folders, ""))
        assertEquals(listOf("界面/空目录", "界面/首页"), imageChildFolders(folders, "界面"))
        assertEquals(emptyList<String>(), imageChildFolders(folders, "界面/空目录"))
    }
    @Test fun newFolderIsCreatedRelativeToSelectedLocation() {
        assertEquals("界面/按钮", newImageFolderPath("界面", " 按钮 ", listOf("界面")))
        assertEquals("账号", newImageFolderPath("", "账号", emptyList()))
    }
    @Test fun duplicateFolderIsRejectedOnlyInCurrentParent() {
        assertThrows(IllegalArgumentException::class.java) { newImageFolderPath("界面", "按钮", listOf("界面/按钮")) }
        assertEquals("账号/按钮", newImageFolderPath("账号", "按钮", listOf("界面/按钮")))
    }
    @Test fun folderCannotEscapeRootOrExceedOrganizationBudget() {
        listOf("", ".", "..", "a/b", "a\\b", "x".repeat(41), "a\u0000b").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { newImageFolderPath("", name, emptyList()) }
        }
        assertThrows(IllegalArgumentException::class.java) { newImageFolderPath("a/b/c/d", "e", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { newImageFolderPath("../a", "b", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            newImageFolderPath(List(3) { "a".repeat(40) }.joinToString("/"), "b", emptyList())
        }
    }
    @Test fun pngExtensionIsAddedOnceAndNameIsPreserved() {
        assertEquals("首页按钮.png", imageSaveFileName(" 首页按钮 "))
        assertEquals("首页按钮.png", imageSaveFileName("首页按钮.PNG"))
        assertEquals("btn.v2_1.png", imageSaveFileName("btn.v2_1.png"))
        assertEquals("截图", imageSaveStem("截图.png"))
    }
    @Test fun imageNameCannotBeSilentlyRewrittenOrInterpretedAsPath() {
        listOf("", ".png", "../a", "a/b", "a\\b", "a b", ".hidden", "a-", "x".repeat(81)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { imageSaveFileName(name) }
        }
        assertEquals("${"x".repeat(80)}.png", imageSaveFileName("x".repeat(80)))
    }
    @Test
    fun listsExistingImageFoldersAndParents() {
        assertEquals(
            listOf("", "界面", "界面/首页"),
            imageResourceFolders(
                listOf("assets/images/界面/首页/按钮.png", "dictionaries/main.asglyph"),
            ),
        )
    }

    @Test fun duplicateSaveDetectionUsesExactFolderAndName() {
        val images = listOf("assets/images/button.png", "assets/images/page/button.png", "assets/audio/button.png")
        assertTrue(imageSaveNameExists("", "button.png", images))
        assertTrue(imageSaveNameExists("page", "button.png", images))
        assertFalse(imageSaveNameExists("other", "button.png", images))
        assertFalse(imageSaveNameExists("page", "button2.png", images))
        assertFalse(imageSaveNameExists("", "unknown.png", images))
    }
}
