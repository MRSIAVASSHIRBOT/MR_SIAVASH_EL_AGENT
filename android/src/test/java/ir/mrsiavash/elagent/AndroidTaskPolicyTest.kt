package ir.mrsiavash.elagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTaskPolicyTest {
    private val scopes = setOf("device.heartbeat", "app.launch")

    @Test fun acceptsOnlyHeartbeatAndOptionalAppLaunchScopes() {
        assertTrue(AndroidTaskPolicy.areSupportedScopes(setOf("device.heartbeat")))
        assertTrue(AndroidTaskPolicy.areSupportedScopes(setOf("device.heartbeat", "app.launch")))
        assertFalse(AndroidTaskPolicy.areSupportedScopes(setOf("app.launch")))
        assertFalse(AndroidTaskPolicy.areSupportedScopes(setOf("device.heartbeat", "screen.capture")))
    }

    @Test fun allowsOnlyAndroidSupportedAppAliases() {
        assertTrue(AndroidTaskPolicy.canLaunch("app.launch", "app.launch", "chatgpt", scopes))
        assertTrue(AndroidTaskPolicy.canLaunch("app.launch", "app.launch", "browser", scopes))
        assertFalse(AndroidTaskPolicy.canLaunch("app.launch", "app.launch", "notepad", scopes))
        assertFalse(AndroidTaskPolicy.canLaunch("app.launch", "app.launch", "calculator", scopes))
        assertFalse(AndroidTaskPolicy.canLaunch("app.launch", "app.launch", "explorer", scopes))
    }

    @Test fun rejectsActionScopeMismatchAndMissingGrant() {
        assertFalse(AndroidTaskPolicy.canLaunch("screen.capture", "screen.capture", null, scopes))
        assertFalse(AndroidTaskPolicy.canLaunch("app.launch", "screen.capture", "browser", scopes))
        assertFalse(
            AndroidTaskPolicy.canLaunch(
                "app.launch", "app.launch", "browser", setOf("device.heartbeat")
            )
        )
    }

    @Test fun classifiesUnsupportedActionsSeparatelyFromPermissionDenials() {
        assertEquals("permission_denied", AndroidTaskPolicy.denialCode("screen.capture", scopes))
        assertEquals("permission_denied", AndroidTaskPolicy.denialCode("unknown.scope", scopes))
        assertEquals("app_not_available", AndroidTaskPolicy.denialCode("app.launch", scopes))
    }

    @Test fun bindsTaskToTheExactPairedDevice() {
        val ownerDeviceId = "00000000-0000-0000-0000-000000000001"
        assertTrue(AndroidTaskPolicy.belongsToDevice(ownerDeviceId, ownerDeviceId))
        assertFalse(AndroidTaskPolicy.belongsToDevice(null, ownerDeviceId))
        assertFalse(
            AndroidTaskPolicy.belongsToDevice(
                "00000000-0000-0000-0000-000000000002",
                ownerDeviceId
            )
        )
    }

}
