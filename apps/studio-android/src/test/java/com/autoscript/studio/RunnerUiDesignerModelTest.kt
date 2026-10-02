package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RunnerUiDesignerModelTest {
    private fun field(id: String, x: Int, y: Int, width: Int = 80, height: Int = 64, parent: String? = null, container: Boolean = false) = RunnerUiFieldDraft(
        id, id, if (container) RunnerUiControlKind.CONTAINER else RunnerUiControlKind.TEXT, false, "",
        ui = com.autoscript.script.ui.UiPresentation(control = if (container) "CONTAINER" else "INPUT", x = x, y = y, width = width, height = height, parentId = parent),
    )

    @Test fun `new controls fill three columns without extending page`() {
        val draft = (1..8).fold(RunnerUiDesignerDraft()) { value, _ -> value.add(RunnerUiControlKind.TEXT) }
        assertEquals(960, draft.pages.single().height)
        assertEquals(draft.fields[0].ui.y, draft.fields[1].ui.y)
        assertEquals(draft.fields[0].ui.y, draft.fields[2].ui.y)
        assertEquals(40, draft.fields[0].ui.height)
        assertEquals(20, draft.fields[0].ui.fontPx)
        assertTrue(draft.fields[1].ui.x > draft.fields[0].ui.x)
        draft.fields.forEachIndexed { i, f -> draft.fields.drop(i + 1).forEach { other ->
            assertTrue(f.ui.x + f.ui.width <= other.ui.x || other.ui.x + other.ui.width <= f.ui.x || f.ui.y + f.ui.height <= other.ui.y || other.ui.y + other.ui.height <= f.ui.y)
        } }
        val tiny = (1..6).fold(RunnerUiDesignerDraft(pages = listOf(com.autoscript.script.ui.UiPage(width = 100, height = 100)))) { value, _ -> value.add(RunnerUiControlKind.TEXT) }
        assertThrows(IllegalStateException::class.java) { tiny.add(RunnerUiControlKind.CONTAINER) }
        assertEquals(100, tiny.pages.single().height)
    }

    @Test fun `all control kinds fit one page with compact defaults`() {
        val draft = RunnerUiControlKind.entries.fold(RunnerUiDesignerDraft()) { value, kind -> value.add(kind) }
        assertEquals(14, draft.fields.size)
        assertTrue(draft.fields.all { it.ui.height == 40 })
        assertTrue(draft.fields.all { it.ui.x + it.ui.width <= 720 && it.ui.y + it.ui.height <= 960 })
        assertEquals(draft, runnerUiDesignerDraft(draft.toRunnerUiJson()))
    }

    @Test fun `compact defaults do not resize existing controls`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("old", 0, 0, height = 96)))
        val loaded = runnerUiDesignerDraft(draft.toRunnerUiJson())
        assertEquals(96, loaded.fields.single().ui.height)
        val added = loaded.add(RunnerUiControlKind.RADIO)
        assertEquals(96, added.fields.first().ui.height)
        assertEquals(40, added.fields.last().ui.height)
    }

    @Test fun `shrink all retains identities interactions and nested geometry`() {
        val root = field("root", 40, 40, 240, 160, container = true)
        val child = field("child", 16, 16, 80, 40, parent = "root").copy(ui = field("child", 16, 16, 80, 40, parent = "root").ui.copy(binding = "global:name", events = mapOf("click" to "run")))
        val draft = RunnerUiDesignerDraft(fields = listOf(root, child, field("tiny", 0, 0, 20, 20)))
        val next = draft.shrinkAll()
        assertEquals(42 to 42, next.absolutePosition(next.fields[1]))
        assertEquals(180, next.fields[0].ui.width)
        assertEquals(30, next.fields[1].ui.height)
        assertEquals(20, next.fields[2].ui.width)
        assertEquals(child.ui.binding, next.fields[1].ui.binding)
        assertEquals(child.ui.events, next.fields[1].ui.events)
        assertEquals(draft.pages, next.pages)
        assertEquals(draft.fields.map { it.id }, next.fields.map { it.id })
        assertEquals(240, draft.fields[0].ui.width)
    }

    @Test fun `group movement clamps together and does not move descendants twice`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("root", 20,20,container = true), field("child",10,12,parent = "root",container = true), field("grandchild",5,5,parent = "child"), field("other",100,100)))
        val moved = draft.translate(setOf("root","child","grandchild","other"), -100,-10)
        assertEquals(0,moved.fields[0].ui.x)
        assertEquals(80,moved.fields[3].ui.x)
        assertEquals(10,moved.fields[1].ui.x)
        assertEquals(5,moved.fields[2].ui.x)
        assertEquals(8,draft.translate(setOf("child"),-1,0,snap = true).fields[1].ui.x)
    }

    @Test fun `alignment distribution and layering keep stable IDs`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("a",10,20), field("b",140,40), field("c",310,60)))
        val ids = draft.fields.map { it.id }.toSet()
        assertTrue(draft.align(ids,"bottom").fields.all { it.ui.y + it.ui.height == 124 })
        assertEquals(listOf(10,160,310),draft.distribute(ids,true).fields.map { it.ui.x })
        assertEquals(listOf("b","c","a"),draft.layer(setOf("a"),true).fields.map { it.id })
        val compact = draft.compactPage("main")
        assertEquals(ids,compact.fields.map { it.id }.toSet())
        assertEquals(compact.fields[0].ui.y,compact.fields[1].ui.y)
        assertEquals(960,compact.pages.single().height)
    }

    @Test fun `container changes retain absolute positions and reject cycles`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("child",80,90),field("container",40,40,container = true)))
        val nested = draft.reparent("child","container","main")
        assertEquals(80 to 90,nested.absolutePosition(nested.fields[0]))
        assertEquals(40,nested.fields[0].ui.x)
        assertEquals(listOf("container","child"),nested.paintOrder("main").map { it.id })
        val detached = nested.reparent("child",null,"main")
        assertEquals(80,detached.fields[0].ui.x)
        assertThrows(IllegalArgumentException::class.java) { nested.reparent("container","child","main") }
        val huge = RunnerUiDesignerDraft(fields = listOf(field("large",0,0,height = 960)))
        assertThrows(IllegalArgumentException::class.java) { huge.compactPage("main") }
        assertEquals(0,huge.fields[0].ui.y)
    }
    @Test
    fun `empty designer removes runner ui`() {
        assertNull(RunnerUiDesignerDraft().toRunnerUiJson())
    }

    @Test fun `single control snaps to six page anchors and center`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("a", 20, 30, 80, 40)))
        val ids = setOf("a")
        assertEquals(0, draft.position(ids, "left").fields.single().ui.x)
        assertEquals(320, draft.position(ids, "centerX").fields.single().ui.x)
        assertEquals(640, draft.position(ids, "right").fields.single().ui.x)
        assertEquals(0, draft.position(ids, "top").fields.single().ui.y)
        assertEquals(460, draft.position(ids, "centerY").fields.single().ui.y)
        assertEquals(920, draft.position(ids, "bottom").fields.single().ui.y)
        assertEquals(320 to 460, draft.position(ids, "center").absolutePosition(draft.position(ids, "center").fields.single()))
        assertEquals(30, draft.position(ids, "left").fields.single().ui.y)
    }

    @Test fun `group placement retains spacing and container children`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("root", 20, 40, 200, 120, container = true),
            field("child", 10, 12, 40, 40, parent = "root"), field("other", 300, 80, 80, 40)))
        val positioned = draft.position(setOf("root", "child", "other"), "center")
        assertEquals(180, positioned.fields[0].ui.x)
        assertEquals(420, positioned.fields[0].ui.y)
        assertEquals(460, positioned.fields[2].ui.x)
        assertEquals(460, positioned.fields[2].ui.y)
        assertEquals(draft.fields[1], positioned.fields[1])
        val childCentered = draft.position(setOf("child"), "center").fields[1]
        assertEquals(80 to 40, childCentered.ui.x to childCentered.ui.y)
    }

    @Test fun `group placement rejects oversized and unrelated selections without mutation`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("a", 0, 0, 800), field("root", 20, 20, container = true), field("child", 10, 10, parent = "root")))
        assertThrows(IllegalArgumentException::class.java) { draft.position(setOf("a"), "centerX") }
        assertThrows(IllegalArgumentException::class.java) { draft.position(setOf("a", "child"), "left") }
        assertEquals(0, draft.fields.first().ui.x)
        assertEquals(draft, draft.position(emptySet(), "center"))
    }

    @Test fun `all six multi alignments work with different control sizes`() {
        val draft = RunnerUiDesignerDraft(fields = listOf(field("a", 10, 20, 80, 40), field("b", 120, 90, 40, 60)))
        val ids = setOf("a", "b")
        assertEquals(listOf(10, 10), draft.align(ids, "left").fields.map { it.ui.x })
        assertEquals(listOf(160, 160), draft.align(ids, "right").fields.map { it.ui.x + it.ui.width })
        assertEquals(listOf(170, 170), draft.align(ids, "centerX").fields.map { 2 * it.ui.x + it.ui.width })
        assertEquals(listOf(20, 20), draft.align(ids, "top").fields.map { it.ui.y })
        assertEquals(listOf(150, 150), draft.align(ids, "bottom").fields.map { it.ui.y + it.ui.height })
        assertEquals(listOf(170, 170), draft.align(ids, "centerY").fields.map { 2 * it.ui.y + it.ui.height })
    }

    @Test
    fun `designer round trips supported controls`() {
        val draft = RunnerUiDesignerDraft(description = "启动配置")
            .add(RunnerUiControlKind.TEXT)
            .add(RunnerUiControlKind.BOOLEAN)
            .add(RunnerUiControlKind.CHOICE)

        val json = requireNotNull(draft.toRunnerUiJson())
        val restored = runnerUiDesignerDraft(json)

        assertEquals("启动配置", restored.description)
        assertEquals(draft.fields.map { it.kind }, restored.fields.map { it.kind })
        assertEquals(3, restored.fields.size)
    }

    @Test
    fun `move keeps stable field identity`() {
        val draft = RunnerUiDesignerDraft()
            .add(RunnerUiControlKind.TEXT)
            .add(RunnerUiControlKind.INTEGER)

        val moved = draft.move(1, -1)

        assertEquals(RunnerUiControlKind.INTEGER, moved.fields.first().kind)
        assertTrue(moved.fields.map { it.id }.toSet() == draft.fields.map { it.id }.toSet())
    }
}
