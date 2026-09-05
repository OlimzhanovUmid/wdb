package uz.disastrouspumpkin.wdb.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SemanticTreeTest {

    // Mirrors the CHR SemanticTreeResult shape (id / bounds / role / actions / children) observed live,
    // including a full-screen overlay (id 20/21) with no actions that must NOT shadow the button.
    private val tree = """
        {"id":1,"bounds":{"x":0,"y":0,"width":1000,"height":700},"children":[
          {"id":6,"text":"title","bounds":{"x":440,"y":150,"width":100,"height":20}},
          {"id":8,"role":"Button","actions":["onClick"],"text":"tap me","bounds":{"x":400,"y":500,"width":200,"height":60}},
          {"id":20,"bounds":{"x":0,"y":0,"width":1000,"height":700},"children":[
            {"id":21,"bounds":{"x":0,"y":0,"width":1000,"height":700}}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun clickable_button_wins_over_overlay() {
        // Point inside the button — the last-ordered full-screen overlay must not shadow it.
        assertEquals(8, hitTestSemanticTree(tree, 500, 530))
    }

    @Test
    fun non_clickable_areas_are_null() {
        assertNull(hitTestSemanticTree(tree, 470, 160)) // over the (non-clickable) title text
        assertNull(hitTestSemanticTree(tree, 10, 10))   // only overlay/root cover it — none clickable
        assertNull(hitTestSemanticTree(tree, 5000, 5000)) // outside everything
    }

    @Test
    fun bad_json_is_null() {
        assertNull(hitTestSemanticTree("not json", 1, 1))
        assertNull(hitTestSemanticTree("", 1, 1))
    }

    // With a dialog open, CHR emits an ARRAY of owners: [main, dialog]. The dialog (last/topmost)
    // must be parsed and hit-tested, not dropped (regression: array threw -> null -> no tree/clicks).
    private val multiOwner = """
        [
          {"id":1,"bounds":{"x":0,"y":0,"width":1000,"height":700},"children":[
            {"id":8,"role":"Button","actions":["onClick"],"text":"login","bounds":{"x":400,"y":500,"width":200,"height":60}}
          ]},
          {"id":225,"bounds":{"x":0,"y":0,"width":1000,"height":700},"children":[
            {"id":226,"isDialog":true,"bounds":{"x":0,"y":0,"width":1000,"height":700},"children":[
              {"id":263,"role":"Button","text":"Далее","actions":["onClick"],"bounds":{"x":400,"y":300,"width":250,"height":47}}
            ]}
          ]}
        ]
    """.trimIndent()

    @Test
    fun parses_testTag_and_contentDescription() {
        val json = """{"id":8,"role":"Button","testTag":"LoginButton","contentDescription":"Sign in",
            "actions":["onClick"],"bounds":{"x":0,"y":0,"width":10,"height":10}}"""
        val n = parseSemanticTree(json)
        assertNotNull(n)
        assertEquals("LoginButton", n.testTag)
        assertEquals("Sign in", n.contentDescription)
    }

    @Test
    fun dialog_owner_is_parsed_and_hit_tested() {
        val root = parseSemanticTree(multiOwner)
        assertNotNull(root)
        // Synthetic root wraps both owners.
        assertEquals(2, root.children.size)
        // A click on the dialog's button resolves to that button (dialog owner not dropped).
        assertEquals(263, hitTestSemanticTree(multiOwner, 450, 320))
        // The main window's button behind the dialog still resolves where the dialog doesn't cover.
        assertEquals(8, hitTestSemanticTree(multiOwner, 500, 530))
    }
}
