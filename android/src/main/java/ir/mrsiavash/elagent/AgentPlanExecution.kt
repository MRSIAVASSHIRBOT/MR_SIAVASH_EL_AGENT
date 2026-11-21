package ir.mrsiavash.elagent

import java.util.UUID

enum class PlanStepState {
    PENDING,
    WAITING_FOR_RESOURCE,
    DISPATCHED,
    COMPLETED,
    FAILED
}

data class PlanStepRecord(
    val stepId: String,
    val actionId: String,
    val state: PlanStepState,
    val detail: String
)

/**
 * In-memory, fail-closed execution ledger for one reviewed plan.
 *
 * External intents are only marked DISPATCHED; the owner must return and explicitly
 * acknowledge the result before dependent steps become runnable. An interrupted process
 * therefore loses the session rather than replaying external side effects.
 */
class AgentPlanExecutionSession(
    val plan: AgentPlan,
    val sessionId: String = UUID.randomUUID().toString()
) {
    private val records = linkedMapOf<String, PlanStepRecord>()

    init {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "Execution session ID is invalid." }
        require(plan.steps.size in 1..AgentPlanParser.MAX_STEPS) {
            "Execution plan step count is outside the supported range."
        }
        val earlierIds = linkedSetOf<String>()
        plan.steps.forEach { step ->
            require(step.id.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && step.id !in earlierIds) {
                "Execution plan contains an invalid or duplicate step ID."
            }
            require(step.dependsOn.all { it in earlierIds }) {
                "Execution plan dependencies must refer only to earlier steps."
            }
            require(AgentPlanPolicy.isSafeForExecution(step)) {
                "Execution plan contains a step outside the Android execution allowlist."
            }
            earlierIds.add(step.id)
            records[step.id] = PlanStepRecord(
                stepId = step.id,
                actionId = actionIdFor(step.id),
                state = PlanStepState.PENDING,
                detail = "منتظر تأیید شما"
            )
        }
    }

    fun actionIdFor(stepId: String): String {
        require(stepId in records || plan.steps.any { it.id == stepId }) { "Unknown plan step." }
        return "$sessionId:$stepId"
    }

    fun record(stepId: String): PlanStepRecord =
        records[stepId] ?: throw IllegalArgumentException("Unknown plan step.")

    fun records(): List<PlanStepRecord> = records.values.toList()

    /** Only the first unfinished step can run; failed or unacknowledged work blocks the plan. */
    fun nextRunnableStep(): AgentPlanStep? {
        if (records.values.any { it.state == PlanStepState.FAILED }) return null
        val next = plan.steps.firstOrNull { records.getValue(it.id).state != PlanStepState.COMPLETED }
            ?: return null
        if (records.getValue(next.id).state != PlanStepState.PENDING) return null
        if (next.dependsOn.any { records[it]?.state != PlanStepState.COMPLETED }) return null
        return next
    }

    fun markWaitingForResource(stepId: String) {
        val step = requireNextRunnable(stepId)
        require(step.operation == AgentOperation.READ_SELECTED_DOCUMENT) {
            "Only selected-document steps can wait for a resource."
        }
        update(stepId, PlanStepState.WAITING_FOR_RESOURCE, "منتظر انتخاب فایل توسط شما")
    }

    fun markDispatched(stepId: String) {
        val step = requireNextRunnable(stepId)
        require(step.operation == AgentOperation.OPEN_APP || step.operation == AgentOperation.OPEN_URL) {
            "Only external-intent steps can be dispatched."
        }
        update(
            stepId,
            PlanStepState.DISPATCHED,
            "درخواست به Android سپرده شد؛ نتیجه هنوز توسط شما تأیید نشده است"
        )
    }

    fun markCompleted(
        stepId: String,
        ownerVerified: Boolean = false,
        resourceVerified: Boolean = false,
        detail: String = "انجام شد"
    ) {
        val step = plan.steps.firstOrNull { it.id == stepId }
            ?: throw IllegalArgumentException("Unknown plan step.")
        val current = record(stepId).state
        when (current) {
            PlanStepState.PENDING -> {
                requireNextRunnable(stepId)
                require(step.operation == AgentOperation.SHOW_TEXT) {
                    "Only local text display can complete directly from pending state."
                }
            }
            PlanStepState.WAITING_FOR_RESOURCE -> {
                requireEarlierStepsCompleted(stepId)
                require(step.operation == AgentOperation.READ_SELECTED_DOCUMENT) {
                    "Only selected-document work can complete after resource selection."
                }
                require(resourceVerified) {
                    "A selected-document step requires verified Android read-grant evidence."
                }
            }
            PlanStepState.DISPATCHED -> {
                requireEarlierStepsCompleted(stepId)
                require(ownerVerified) {
                    "An external action requires explicit owner verification before completion."
                }
            }
            PlanStepState.COMPLETED, PlanStepState.FAILED ->
                throw IllegalStateException("This step is already in a terminal state.")
        }
        update(stepId, PlanStepState.COMPLETED, detail)
    }

    fun markFailed(stepId: String, detail: String) {
        val state = record(stepId).state
        if (state == PlanStepState.PENDING) requireNextRunnable(stepId)
        else requireEarlierStepsCompleted(stepId)
        require(state == PlanStepState.PENDING ||
            state == PlanStepState.WAITING_FOR_RESOURCE ||
            state == PlanStepState.DISPATCHED) {
            "Only an active or pending step can be marked failed."
        }
        update(stepId, PlanStepState.FAILED, detail.take(MAX_DETAIL_CHARS))
    }

    fun isComplete(): Boolean = records.values.all { it.state == PlanStepState.COMPLETED }

    private fun requireNextRunnable(stepId: String): AgentPlanStep {
        val next = nextRunnableStep()
            ?: throw IllegalStateException("The plan is blocked until the previous step is verified.")
        require(next.id == stepId) { "Only the next ordered plan step can be executed." }
        return next
    }

    private fun requireEarlierStepsCompleted(stepId: String) {
        val index = plan.steps.indexOfFirst { it.id == stepId }
        require(index >= 0) { "Unknown plan step." }
        require(plan.steps.take(index).all { records.getValue(it.id).state == PlanStepState.COMPLETED }) {
            "Previous plan steps must be completed first."
        }
    }

    private fun update(stepId: String, state: PlanStepState, detail: String) {
        val old = record(stepId)
        records[stepId] = old.copy(state = state, detail = detail.take(MAX_DETAIL_CHARS))
    }

    companion object {
        const val MAX_DETAIL_CHARS = 300
    }
}

data class PlanPolicyContext(
    val actionId: String,
    val appInForeground: Boolean,
    val ownerConfirmed: Boolean,
    val resourceSelected: Boolean = false
)

/** Maps one owner-reviewed plan step into the central Android capability policy. */
object AgentPlanExecutionPolicy {
    fun evaluate(step: AgentPlanStep, context: PlanPolicyContext): PolicyDecision {
        if (!AgentPlanPolicy.isSafeForExecution(step)) {
            return PolicyDecision(false, false, "The typed action is outside the Android execution allowlist.")
        }
        if (context.actionId.isBlank()) {
            return PolicyDecision(false, false, "A stable action identifier is required.")
        }
        if (!context.appInForeground) {
            return PolicyDecision(false, false, "The app must be in the foreground.")
        }
        if (!context.ownerConfirmed) {
            return PolicyDecision(false, true, "The owner must confirm this exact action.")
        }

        if (step.operation == AgentOperation.READ_SELECTED_DOCUMENT && !context.resourceSelected) {
            // This authorizes only opening the system picker, not reading any content.
            return PolicyDecision(true, false, "Owner approved opening the Android document picker.")
        }

        val requirement = AgentPlanPolicy.requirement(step)
        val capability = requirement.capability
            ?: return PolicyDecision(true, false, "Local display action approved.")
        val selected = if (step.operation == AgentOperation.READ_SELECTED_DOCUMENT && context.resourceSelected) {
            setOf(CapabilityId.SELECTED_FILE_READ)
        } else {
            emptySet()
        }
        val actionContext = ActionContext(
            actionId = context.actionId,
            appInForeground = context.appInForeground,
            userSelectedResources = selected,
            confirmedActionIds = setOf(context.actionId)
        )
        return CapabilityCatalog.evaluate(capability, requirement.effect ?: ActionEffect.READ_ONLY, actionContext)
    }
}
