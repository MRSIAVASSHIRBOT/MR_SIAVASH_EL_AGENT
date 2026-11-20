package ir.mrsiavash.elagent

import org.junit.Assert.*
import org.junit.Test

class CapabilityCatalogTest {
    @Test fun exposesBroadCatalogWithoutGrantingItAutomatically() {
        assertTrue(CapabilityCatalog.all().size >= 20)
        assertFalse(CapabilityCatalog.evaluate(CapabilityId.SCREEN_CAPTURE, ActionEffect.READ_ONLY, ActionContext(appInForeground = true)).allowed)
    }
    @Test fun allowsSelectedDocumentReadOnlyAfterUserSelection() {
        val d = CapabilityCatalog.evaluate(CapabilityId.SELECTED_FILE_READ, ActionEffect.READ_ONLY, ActionContext(appInForeground = true, resourceSelectedByUser = true))
        assertTrue(d.allowed); assertFalse(d.requiresConfirmation)
    }
    @Test fun rejectsFileAccessWithoutUserSelection() {
        val d = CapabilityCatalog.evaluate(CapabilityId.SELECTED_FILE_READ, ActionEffect.READ_ONLY, ActionContext(appInForeground = true))
        assertFalse(d.allowed); assertTrue(d.requiresConfirmation)
    }
    @Test fun requiresSystemGrantForScreenCapture() {
        val denied = CapabilityCatalog.evaluate(CapabilityId.SCREEN_CAPTURE, ActionEffect.READ_ONLY, ActionContext(appInForeground = true))
        val allowed = CapabilityCatalog.evaluate(CapabilityId.SCREEN_CAPTURE, ActionEffect.READ_ONLY, ActionContext(appInForeground = true, platformGrantGranted = true))
        assertFalse(denied.allowed); assertTrue(allowed.allowed)
    }
    @Test fun requiresFinalConfirmationForExternalEffects() {
        val denied = CapabilityCatalog.evaluate(CapabilityId.SHARE_CONTENT, ActionEffect.EXTERNAL_COMMUNICATION, ActionContext(appInForeground = true, explicitConfirmation = false))
        val allowed = CapabilityCatalog.evaluate(CapabilityId.SHARE_CONTENT, ActionEffect.EXTERNAL_COMMUNICATION, ActionContext(appInForeground = true, explicitConfirmation = true))
        assertFalse(denied.allowed); assertTrue(denied.requiresConfirmation); assertTrue(allowed.allowed)
    }
    @Test fun blocksShellCredentialExtractionAndHiddenMonitoring() {
        listOf(CapabilityId.SHELL_EXECUTION, CapabilityId.CREDENTIAL_EXTRACTION, CapabilityId.HIDDEN_MONITORING).forEach { id ->
            val d = CapabilityCatalog.evaluate(id, ActionEffect.READ_ONLY, ActionContext(appInForeground = true, explicitConfirmation = true))
            assertFalse(d.allowed); assertFalse(d.requiresConfirmation)
        }
    }
    @Test fun backgroundActionsNeedPriorOwnerAuthorization() {
        val denied = CapabilityCatalog.evaluate(CapabilityId.APP_LAUNCH, ActionEffect.LOCAL_CHANGE, ActionContext(appInForeground = false))
        val allowed = CapabilityCatalog.evaluate(CapabilityId.APP_LAUNCH, ActionEffect.LOCAL_CHANGE, ActionContext(appInForeground = false, ownerAuthorizedTask = true))
        assertFalse(denied.allowed); assertTrue(allowed.allowed)
    }
    @Test fun accessibilityRequiresEnabledServiceAndForeground() {
        val denied = CapabilityCatalog.evaluate(CapabilityId.ACCESSIBILITY_AUTOMATION, ActionEffect.LOCAL_CHANGE, ActionContext(appInForeground = true))
        val allowed = CapabilityCatalog.evaluate(CapabilityId.ACCESSIBILITY_AUTOMATION, ActionEffect.LOCAL_CHANGE, ActionContext(appInForeground = true, accessibilityServiceEnabled = true))
        assertFalse(denied.allowed); assertTrue(allowed.allowed)
    }
    @Test fun requiresConfirmationForLocalDataWrites() {
        val withoutConfirmation = CapabilityCatalog.evaluate(
            CapabilityId.SELECTED_FILE_WRITE,
            ActionEffect.LOCAL_CHANGE,
            ActionContext(appInForeground = true, resourceSelectedByUser = true)
        )
        val withConfirmation = CapabilityCatalog.evaluate(
            CapabilityId.SELECTED_FILE_WRITE,
            ActionEffect.LOCAL_CHANGE,
            ActionContext(appInForeground = true, resourceSelectedByUser = true, explicitConfirmation = true)
        )
        assertFalse(withoutConfirmation.allowed)
        assertTrue(withoutConfirmation.requiresConfirmation)
        assertTrue(withConfirmation.allowed)
    }

    @Test fun requiresPermissionAndConfirmationForContactChanges() {
        val permissionOnly = CapabilityCatalog.evaluate(
            CapabilityId.CONTACTS_WRITE,
            ActionEffect.LOCAL_CHANGE,
            ActionContext(appInForeground = true, platformGrantGranted = true)
        )
        val fullyAuthorized = CapabilityCatalog.evaluate(
            CapabilityId.CONTACTS_WRITE,
            ActionEffect.LOCAL_CHANGE,
            ActionContext(appInForeground = true, platformGrantGranted = true, explicitConfirmation = true)
        )
        assertFalse(permissionOnly.allowed)
        assertTrue(permissionOnly.requiresConfirmation)
        assertTrue(fullyAuthorized.allowed)
    }

}
