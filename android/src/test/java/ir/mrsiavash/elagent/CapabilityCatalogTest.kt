package ir.mrsiavash.elagent

import org.junit.Assert.*
import org.junit.Test

class CapabilityCatalogTest {
    private fun context(
        actionId: String = "action-1",
        foreground: Boolean = true,
        grants: Set<CapabilityId> = emptySet(),
        selected: Set<CapabilityId> = emptySet(),
        accessibility: Boolean = false,
        ownerTaskId: String? = null,
        authorizedTasks: Set<String> = emptySet(),
        confirmations: Set<String> = emptySet()
    ) = ActionContext(
        actionId = actionId,
        appInForeground = foreground,
        grantedCapabilities = grants,
        userSelectedResources = selected,
        accessibilityServiceEnabled = accessibility,
        ownerTaskId = ownerTaskId,
        ownerAuthorizedTaskIds = authorizedTasks,
        confirmedActionIds = confirmations
    )

    @Test fun exposesBroadCatalogWithoutGrantingCapabilities() {
        assertEquals(22, CapabilityCatalog.all().size)
        assertFalse(CapabilityCatalog.evaluate(CapabilityId.SCREEN_CAPTURE, ActionEffect.READ_ONLY, context()).allowed)
    }

    @Test fun selectedFileReadRequiresUserSelectedResource() {
        val denied = CapabilityCatalog.evaluate(CapabilityId.SELECTED_FILE_READ, ActionEffect.READ_ONLY, context())
        val allowed = CapabilityCatalog.evaluate(
            CapabilityId.SELECTED_FILE_READ, ActionEffect.READ_ONLY,
            context(selected = setOf(CapabilityId.SELECTED_FILE_READ))
        )
        assertFalse(denied.allowed)
        assertTrue(allowed.allowed)
    }

    @Test fun permissionGrantIsBoundToExactCapability() {
        val wrongGrant = CapabilityCatalog.evaluate(
            CapabilityId.CAMERA_CAPTURE, ActionEffect.READ_ONLY,
            context(grants = setOf(CapabilityId.MICROPHONE_CAPTURE))
        )
        val rightGrant = CapabilityCatalog.evaluate(
            CapabilityId.CAMERA_CAPTURE, ActionEffect.READ_ONLY,
            context(grants = setOf(CapabilityId.CAMERA_CAPTURE))
        )
        assertFalse(wrongGrant.allowed)
        assertTrue(rightGrant.allowed)
    }

    @Test fun screenCaptureRequiresItsOwnSystemGrant() {
        val denied = CapabilityCatalog.evaluate(CapabilityId.SCREEN_CAPTURE, ActionEffect.READ_ONLY, context())
        val allowed = CapabilityCatalog.evaluate(
            CapabilityId.SCREEN_CAPTURE, ActionEffect.READ_ONLY,
            context(grants = setOf(CapabilityId.SCREEN_CAPTURE))
        )
        assertFalse(denied.allowed)
        assertTrue(allowed.allowed)
    }

    @Test fun confirmationsAreBoundToOneActionId() {
        val approvedOtherAction = CapabilityCatalog.evaluate(
            CapabilityId.SHARE_CONTENT, ActionEffect.EXTERNAL_COMMUNICATION,
            context(actionId = "send-2", confirmations = setOf("send-1"))
        )
        val approvedThisAction = CapabilityCatalog.evaluate(
            CapabilityId.SHARE_CONTENT, ActionEffect.EXTERNAL_COMMUNICATION,
            context(actionId = "send-2", confirmations = setOf("send-2"))
        )
        assertFalse(approvedOtherAction.allowed)
        assertTrue(approvedOtherAction.requiresConfirmation)
        assertTrue(approvedThisAction.allowed)
    }

    @Test fun localWritesRequireConfirmationEvenWhenResourceWasSelected() {
        val denied = CapabilityCatalog.evaluate(
            CapabilityId.SELECTED_FILE_WRITE, ActionEffect.LOCAL_CHANGE,
            context(selected = setOf(CapabilityId.SELECTED_FILE_WRITE))
        )
        val allowed = CapabilityCatalog.evaluate(
            CapabilityId.SELECTED_FILE_WRITE, ActionEffect.LOCAL_CHANGE,
            context(selected = setOf(CapabilityId.SELECTED_FILE_WRITE), confirmations = setOf("action-1"))
        )
        assertFalse(denied.allowed)
        assertTrue(denied.requiresConfirmation)
        assertTrue(allowed.allowed)
    }

    @Test fun initiatingCallsRequiresRecipientConfirmation() {
        val notConfirmed = CapabilityCatalog.evaluate(
            CapabilityId.CALL_INITIATE, ActionEffect.EXTERNAL_COMMUNICATION, context()
        )
        val confirmed = CapabilityCatalog.evaluate(
            CapabilityId.CALL_INITIATE, ActionEffect.EXTERNAL_COMMUNICATION,
            context(confirmations = setOf("action-1"))
        )
        assertFalse(notConfirmed.allowed)
        assertTrue(notConfirmed.requiresConfirmation)
        assertTrue(confirmed.allowed)
    }

    @Test fun contactChangesRequirePermissionAndConfirmation() {
        val permissionOnly = CapabilityCatalog.evaluate(
            CapabilityId.CONTACTS_WRITE, ActionEffect.LOCAL_CHANGE,
            context(grants = setOf(CapabilityId.CONTACTS_WRITE))
        )
        val fullyApproved = CapabilityCatalog.evaluate(
            CapabilityId.CONTACTS_WRITE, ActionEffect.LOCAL_CHANGE,
            context(grants = setOf(CapabilityId.CONTACTS_WRITE), confirmations = setOf("action-1"))
        )
        assertFalse(permissionOnly.allowed)
        assertTrue(fullyApproved.allowed)
    }

    @Test fun backgroundActionRequiresMatchingOwnerAuthorizedTask() {
        val denied = CapabilityCatalog.evaluate(
            CapabilityId.APP_LAUNCH, ActionEffect.LOCAL_CHANGE,
            context(foreground = false, ownerTaskId = "task-1", authorizedTasks = setOf("another-task"))
        )
        val allowed = CapabilityCatalog.evaluate(
            CapabilityId.APP_LAUNCH, ActionEffect.LOCAL_CHANGE,
            context(foreground = false, ownerTaskId = "task-1", authorizedTasks = setOf("task-1"))
        )
        assertFalse(denied.allowed)
        assertTrue(allowed.allowed)
    }

    @Test fun actionIdCannotMasqueradeAsOwnerTaskId() {
        val decision = CapabilityCatalog.evaluate(
            CapabilityId.APP_LAUNCH,
            ActionEffect.LOCAL_CHANGE,
            context(foreground = false, authorizedTasks = setOf("action-1"))
        )
        assertFalse(decision.allowed)
    }

    @Test fun accessibilityRequiresEnabledServiceAndForeground() {
        val disabled = CapabilityCatalog.evaluate(
            CapabilityId.ACCESSIBILITY_AUTOMATION, ActionEffect.LOCAL_CHANGE, context()
        )
        val enabled = CapabilityCatalog.evaluate(
            CapabilityId.ACCESSIBILITY_AUTOMATION, ActionEffect.LOCAL_CHANGE,
            context(accessibility = true)
        )
        assertFalse(disabled.allowed)
        assertTrue(enabled.allowed)
    }

    @Test fun blockedCapabilitiesCannotBeEnabledByConfirmation() {
        listOf(
            CapabilityId.SHELL_EXECUTION,
            CapabilityId.CREDENTIAL_EXTRACTION,
            CapabilityId.HIDDEN_MONITORING
        ).forEach { id ->
            val decision = CapabilityCatalog.evaluate(
                id, ActionEffect.READ_ONLY,
                context(confirmations = setOf("action-1"))
            )
            assertFalse(decision.allowed)
            assertFalse(decision.requiresConfirmation)
        }
    }

    @Test fun rejectsBlankActionIdentifier() {
        val decision = CapabilityCatalog.evaluate(
            CapabilityId.APP_LAUNCH, ActionEffect.LOCAL_CHANGE,
            context(actionId = " ")
        )
        assertFalse(decision.allowed)
    }
}
