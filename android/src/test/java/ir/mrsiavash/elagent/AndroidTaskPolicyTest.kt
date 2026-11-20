package ir.mrsiavash.elagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTaskPolicyTest {
    private val scopes = setOf("device.heartbeat", "app.launch")

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
}
