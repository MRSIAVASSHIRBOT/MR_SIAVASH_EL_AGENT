package ir.mrsiavash.elagent

import org.junit.Assert.*
import org.junit.Test

class AgentPlanExecutionTest {
    private fun step(
        id: String,
        operation: AgentOperation,
        dependencies: List<String> = emptyList(),
        target: String? = null,
        text: String? = null
    ) = AgentPlanStep(id, operation, dependencies, target, text)

    private fun twoStepPlan() = AgentPlan(
        summary = "Two steps",
        steps = listOf(
            step("open", AgentOperation.OPEN_URL, target = "https://example.com"),
            step("display", AgentOperation.SHOW_TEXT, dependencies = listOf("open"), text = "Done")
        )
    )

    @Test fun actionIdsAreNamespacedToExecutionSession() {
        val first = AgentPlanExecutionSession(twoStepPlan(), "session-0001")
        val second = AgentPlanExecutionSession(twoStepPlan(), "session-0002")
        assertEquals("session-0001:open", first.actionIdFor("open"))
        assertNotEquals(first.actionIdFor("open"), second.actionIdFor("open"))
    }

    @Test fun onlyFirstOrderedStepCanRun() {
        val session = AgentPlanExecutionSession(twoStepPlan(), "session-0001")
        assertEquals("open", session.nextRunnableStep()?.id)
        assertThrows(IllegalArgumentException::class.java) { session.markCompleted("display") }
    }

    @Test fun dispatchedExternalActionBlocksDependentsUntilOwnerAcknowledges() {
        val session = AgentPlanExecutionSession(twoStepPlan(), "session-0001")
        session.markDispatched("open")
        assertEquals(PlanStepState.DISPATCHED, session.record("open").state)
        assertNull(session.nextRunnableStep())
        assertThrows(IllegalArgumentException::class.java) { session.markCompleted("open") }
        session.markCompleted("open", ownerVerified = true)
        assertEquals("display", session.nextRunnableStep()?.id)
    }

    @Test fun fileActionRequiresPickerAndThenResourceEvidence() {
        val plan = AgentPlan("Read a file", listOf(step("read", AgentOperation.READ_SELECTED_DOCUMENT)))
        val session = AgentPlanExecutionSession(plan, "session-0001")
        session.markWaitingForResource("read")
        assertEquals(PlanStepState.WAITING_FOR_RESOURCE, session.record("read").state)
        assertThrows(IllegalArgumentException::class.java) { session.markCompleted("read") }
        session.markCompleted("read", resourceVerified = true, detail = "File previewed locally")
        assertTrue(session.isComplete())
    }

    @Test fun failedStepStopsTheWholePlanWithoutReplay() {
        val session = AgentPlanExecutionSession(twoStepPlan(), "session-0001")
        session.markFailed("open", "Intent dispatch failed")
        assertNull(session.nextRunnableStep())
        assertFalse(session.isComplete())
        assertThrows(IllegalStateException::class.java) { session.markCompleted("open") }
    }

    @Test fun localDisplayCanCompleteOnlyWhenItIsNext() {
        val plan = AgentPlan("Display only", listOf(step("show", AgentOperation.SHOW_TEXT, text = "hello")))
        val session = AgentPlanExecutionSession(plan, "session-0001")
        session.markCompleted("show")
        assertTrue(session.isComplete())
    }

    @Test fun pendingLaterTextStepCannotBeCompletedOutOfOrder() {
        val session = AgentPlanExecutionSession(twoStepPlan(), "session-0001")
        assertThrows(IllegalArgumentException::class.java) { session.markCompleted("display") }
        assertEquals(PlanStepState.PENDING, session.record("display").state)
    }

    @Test fun cannotMarkExternalActionCompleteWithoutOwnerVerification() {
        val session = AgentPlanExecutionSession(twoStepPlan(), "session-0001")
        session.markDispatched("open")
        assertThrows(IllegalArgumentException::class.java) {
            session.markCompleted("open", ownerVerified = false)
        }
    }

    @Test fun rejectsArbitraryAppAliasesAndUnsafeUrlEvenWhenTypedDirectly() {
        val arbitraryApp = step("launch", AgentOperation.OPEN_APP, target = "com.attacker.app")
        val unsafeUrl = step("navigate", AgentOperation.OPEN_URL, target = "javascript:alert(1)")
        val appDecision = AgentPlanExecutionPolicy.evaluate(
            arbitraryApp, PlanPolicyContext("session:launch", true, true)
        )
        val urlDecision = AgentPlanExecutionPolicy.evaluate(
            unsafeUrl, PlanPolicyContext("session:navigate", true, true)
        )
        assertFalse(appDecision.allowed)
        assertFalse(urlDecision.allowed)
    }

    @Test fun rejectsExecutionPlanWithForwardDependency() {
        val invalid = AgentPlan(
            "Invalid ordering",
            listOf(
                step("second", AgentOperation.SHOW_TEXT, dependencies = listOf("first"), text = "later"),
                step("first", AgentOperation.SHOW_TEXT, text = "first")
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentPlanExecutionSession(invalid, "session-0001")
        }
    }

    @Test fun policyRequiresForegroundAndExactOwnerConfirmation() {
        val urlStep = step("visit", AgentOperation.OPEN_URL, target = "https://example.com")
        val background = AgentPlanExecutionPolicy.evaluate(
            urlStep, PlanPolicyContext("session:visit", false, true)
        )
        val unconfirmed = AgentPlanExecutionPolicy.evaluate(
            urlStep, PlanPolicyContext("session:visit", true, false)
        )
        val allowed = AgentPlanExecutionPolicy.evaluate(
            urlStep, PlanPolicyContext("session:visit", true, true)
        )
        assertFalse(background.allowed)
        assertTrue(unconfirmed.requiresConfirmation)
        assertTrue(allowed.allowed)
    }

    @Test fun pickerAuthorizationDoesNotPretendAResourceWasRead() {
        val fileStep = step("read", AgentOperation.READ_SELECTED_DOCUMENT)
        val openPicker = AgentPlanExecutionPolicy.evaluate(
            fileStep, PlanPolicyContext("session:read", true, true, resourceSelected = false)
        )
        val readSelected = AgentPlanExecutionPolicy.evaluate(
            fileStep, PlanPolicyContext("session:read", true, true, resourceSelected = true)
        )
        assertTrue(openPicker.allowed)
        assertTrue(readSelected.allowed)
    }

    @Test fun displayStepStillRequiresForegroundOwnerInitiation() {
        val display = step("show", AgentOperation.SHOW_TEXT, text = "hello")
        val denied = AgentPlanExecutionPolicy.evaluate(
            display, PlanPolicyContext("session:show", true, false)
        )
        val allowed = AgentPlanExecutionPolicy.evaluate(
            display, PlanPolicyContext("session:show", true, true)
        )
        assertFalse(denied.allowed)
        assertTrue(allowed.allowed)
    }
}
