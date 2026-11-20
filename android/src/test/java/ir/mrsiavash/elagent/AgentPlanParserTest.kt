package ir.mrsiavash.elagent

import org.junit.Assert.*
import org.junit.Test

class AgentPlanParserTest {
    private fun plan(steps: String, extra: String = "") =
        """{"schema_version":1,"summary":"Test plan","steps":[$steps]$extra}"""

    @Test fun parsesSupportedAppLaunchAndDisplayText() {
        val parsed = AgentPlanParser.parse(
            plan(
                """{"id":"open_chat","operation":"open_app","target":"chatgpt","depends_on":[]}""",
                """,
                """)
        )
        assertEquals(1, parsed.steps.size)
        assertEquals(AgentOperation.OPEN_APP, parsed.steps[0].operation)
        assertEquals("chatgpt", parsed.steps[0].target)
    }

    @Test fun parsesHttpsUrlWithoutEmbeddedCredentials() {
        val parsed = AgentPlanParser.parse(
            plan("""{"id":"visit","operation":"open_url","target":"https://example.com/docs","depends_on":[]}""")
        )
        assertEquals("https://example.com/docs", parsed.steps.single().target)
        val requirement = AgentPlanPolicy.requirement(parsed.steps.single())
        assertEquals(CapabilityId.OPEN_URL, requirement.capability)
        assertEquals(ActionEffect.EXTERNAL_COMMUNICATION, requirement.effect)
    }

    @Test fun allowsClarificationOnlyPlan() {
        val raw = """{"schema_version":1,"summary":"Need more detail","steps":[],"clarification":"Which app should be opened?"}"""
        val parsed = AgentPlanParser.parse(raw)
        assertTrue(parsed.steps.isEmpty())
        assertEquals("Which app should be opened?", parsed.clarification)
    }

    @Test fun rejectsClarificationMixedWithSteps() {
        val raw = """{"schema_version":1,"summary":"Mixed","steps":[{"id":"show","operation":"show_text","text":"hi","depends_on":[]}],"clarification":"Need details"}"""
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(raw) }
    }

    @Test fun rejectsUnsupportedShellOrScriptOperations() {
        val raw = plan("""{"id":"run","operation":"shell","target":"echo unsafe","depends_on":[]}""")
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(raw) }
    }

    @Test fun rejectsArbitraryPackageNames() {
        val raw = plan("""{"id":"launch","operation":"open_app","target":"com.example.other","depends_on":[]}""")
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(raw) }
    }

    @Test fun rejectsNonHttpsAndCredentialBearingUrls() {
        listOf(
            "http://example.com",
            "javascript:alert(1)",
            "file:///sdcard/private.txt",
            "https://user:pass@example.com/"
        ).forEach { url ->
            val raw = plan("""{"id":"visit","operation":"open_url","target":"$url","depends_on":[]}""")
            assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(raw) }
        }
    }

    @Test fun rejectsUnknownFieldsAndDuplicateIdentifiers() {
        val unknown = plan("""{"id":"one","operation":"show_text","text":"hi","depends_on":[],"command":"x"}""")
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(unknown) }
        val duplicate = plan(
            """{"id":"same","operation":"show_text","text":"first","depends_on":[]},{"id":"same","operation":"show_text","text":"second","depends_on":[]}"""
        )
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(duplicate) }
    }

    @Test fun dependenciesMustReferToEarlierSteps() {
        val forward = plan(
            """{"id":"second","operation":"show_text","text":"later","depends_on":["first"]},{"id":"first","operation":"show_text","text":"first","depends_on":[]}"""
        )
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(forward) }
        val valid = plan(
            """{"id":"first","operation":"show_text","text":"first","depends_on":[]},{"id":"second","operation":"show_text","text":"second","depends_on":["first"]}"""
        )
        assertEquals(listOf("first"), AgentPlanParser.parse(valid).steps[1].dependsOn)
    }

    @Test fun rejectsTooManyStepsAndOversizedResponse() {
        val steps = (1..(AgentPlanParser.MAX_STEPS + 1)).joinToString(",") {
            """{"id":"step_$it","operation":"show_text","text":"hello","depends_on":[]}"""
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentPlanParser.parse(plan(steps))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentPlanParser.parse(" ".repeat(AgentPlanParser.MAX_RESPONSE_CHARS + 1))
        }
    }

    @Test fun requiresTextForDisplayAndNoPathForDocumentSelection() {
        val noText = plan("""{"id":"show","operation":"show_text","depends_on":[]}""")
        val filePath = plan("""{"id":"read","operation":"read_selected_document","target":"/sdcard/secret.txt","depends_on":[]}""")
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(noText) }
        assertThrows(IllegalArgumentException::class.java) { AgentPlanParser.parse(filePath) }
    }

    @Test fun rendersThatPlanHasNotExecuted() {
        val parsed = AgentPlanParser.parse(
            plan("""{"id":"show","operation":"show_text","text":"Preview","depends_on":[]}""")
        )
        val rendered = AgentPlanPolicy.render(parsed)
        assertTrue(rendered.contains("هنوز اجرا نشده"))
        assertTrue(rendered.contains("Preview"))
    }
}
