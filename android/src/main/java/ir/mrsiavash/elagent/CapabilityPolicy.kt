package ir.mrsiavash.elagent

/** Declarative Android capability catalog. Metadata only; it does not execute device actions. */
enum class CapabilityId {
    APP_LAUNCH, OPEN_URL, SELECTED_FILE_READ, SELECTED_FILE_WRITE, SHARE_CONTENT,
    CALENDAR_WRITE, ALARM_WRITE, NOTIFICATION_POST, NOTIFICATION_READ, CAMERA_CAPTURE,
    SCREEN_CAPTURE, MICROPHONE_CAPTURE, LOCATION_ACCESS, CONTACTS_READ, CONTACTS_WRITE,
    SMS_SEND, CALL_INITIATE, ACCESSIBILITY_AUTOMATION, DEVICE_SETTINGS_CHANGE,
    SHELL_EXECUTION, CREDENTIAL_EXTRACTION, HIDDEN_MONITORING
}
enum class GrantRequirement {
    NONE, ANDROID_PERMISSION, USER_SELECTED_RESOURCE, ANDROID_SYSTEM_CONSENT,
    ACCESSIBILITY_SERVICE, EXPLICIT_USER_CONFIRMATION, BLOCKED
}
enum class ActionEffect {
    READ_ONLY, LOCAL_CHANGE, EXTERNAL_COMMUNICATION, DESTRUCTIVE, FINANCIAL, SECURITY_SENSITIVE
}
data class CapabilitySpec(val id: CapabilityId, val grant: GrantRequirement, val description: String, val requiresExplicitConfirmation: Boolean = false)
data class ActionContext(
    val appInForeground: Boolean,
    val platformGrantGranted: Boolean = false,
    val resourceSelectedByUser: Boolean = false,
    val accessibilityServiceEnabled: Boolean = false,
    val explicitConfirmation: Boolean = false,
    /** True only for a task the owner previously scheduled or approved in the app. */
    val ownerAuthorizedTask: Boolean = false
)
data class PolicyDecision(val allowed: Boolean, val requiresConfirmation: Boolean, val reason: String)

object CapabilityCatalog {
    private val specs = listOf(
        CapabilitySpec(CapabilityId.APP_LAUNCH, GrantRequirement.NONE, "Launch an installed app through Android intents"),
        CapabilitySpec(CapabilityId.OPEN_URL, GrantRequirement.NONE, "Open a URL through the user's selected browser"),
        CapabilitySpec(CapabilityId.SELECTED_FILE_READ, GrantRequirement.USER_SELECTED_RESOURCE, "Read files explicitly selected through the Android document picker"),
        CapabilitySpec(CapabilityId.SELECTED_FILE_WRITE, GrantRequirement.USER_SELECTED_RESOURCE, "Write to a destination explicitly selected through the Android document picker", true),
        CapabilitySpec(CapabilityId.SHARE_CONTENT, GrantRequirement.EXPLICIT_USER_CONFIRMATION, "Share user-approved content through Android Sharesheet"),
        CapabilitySpec(CapabilityId.CALENDAR_WRITE, GrantRequirement.ANDROID_PERMISSION, "Create or update calendar entries", true),
        CapabilitySpec(CapabilityId.ALARM_WRITE, GrantRequirement.ANDROID_SYSTEM_CONSENT, "Create alarms or reminders using supported system APIs", true),
        CapabilitySpec(CapabilityId.NOTIFICATION_POST, GrantRequirement.ANDROID_PERMISSION, "Post notifications"),
        CapabilitySpec(CapabilityId.NOTIFICATION_READ, GrantRequirement.ANDROID_SYSTEM_CONSENT, "Read notifications only after explicit Settings opt-in"),
        CapabilitySpec(CapabilityId.CAMERA_CAPTURE, GrantRequirement.ANDROID_PERMISSION, "Capture an image after runtime camera permission"),
        CapabilitySpec(CapabilityId.SCREEN_CAPTURE, GrantRequirement.ANDROID_SYSTEM_CONSENT, "Capture screen content through Android MediaProjection consent"),
        CapabilitySpec(CapabilityId.MICROPHONE_CAPTURE, GrantRequirement.ANDROID_PERMISSION, "Capture microphone input during a visible approved session"),
        CapabilitySpec(CapabilityId.LOCATION_ACCESS, GrantRequirement.ANDROID_PERMISSION, "Access location within granted precision and duration"),
        CapabilitySpec(CapabilityId.CONTACTS_READ, GrantRequirement.ANDROID_PERMISSION, "Read contacts after runtime permission"),
        CapabilitySpec(CapabilityId.CONTACTS_WRITE, GrantRequirement.ANDROID_PERMISSION, "Modify contacts after permission and per-operation confirmation", true),
        CapabilitySpec(CapabilityId.SMS_SEND, GrantRequirement.EXPLICIT_USER_CONFIRMATION, "Send a user-reviewed message through an Android-supported flow"),
        CapabilitySpec(CapabilityId.CALL_INITIATE, GrantRequirement.EXPLICIT_USER_CONFIRMATION, "Initiate a call only after the user confirms the recipient"),
        CapabilitySpec(CapabilityId.ACCESSIBILITY_AUTOMATION, GrantRequirement.ACCESSIBILITY_SERVICE, "Automate visible UI after the user enables the Accessibility service"),
        CapabilitySpec(CapabilityId.DEVICE_SETTINGS_CHANGE, GrantRequirement.EXPLICIT_USER_CONFIRMATION, "Open supported system settings or apply changes through public Android APIs", true),
        CapabilitySpec(CapabilityId.SHELL_EXECUTION, GrantRequirement.BLOCKED, "Arbitrary shell execution is not an Android app capability"),
        CapabilitySpec(CapabilityId.CREDENTIAL_EXTRACTION, GrantRequirement.BLOCKED, "Extracting credentials or secrets is prohibited"),
        CapabilitySpec(CapabilityId.HIDDEN_MONITORING, GrantRequirement.BLOCKED, "Hidden surveillance or covert capture is prohibited")
    ).associateBy { it.id }

    fun all(): List<CapabilitySpec> = specs.values.sortedBy { it.id.name }
    fun find(id: CapabilityId): CapabilitySpec? = specs[id]

    fun evaluate(id: CapabilityId, effect: ActionEffect, context: ActionContext): PolicyDecision {
        val spec = specs[id] ?: return PolicyDecision(false, false, "Unknown capability.")
        if (spec.grant == GrantRequirement.BLOCKED) return PolicyDecision(false, false, spec.description)
        if (!context.appInForeground && !context.ownerAuthorizedTask) {
            return PolicyDecision(false, false, "A foreground session or previously owner-authorized task is required.")
        }
        val grantSatisfied = when (spec.grant) {
            GrantRequirement.NONE -> true
            GrantRequirement.ANDROID_PERMISSION, GrantRequirement.ANDROID_SYSTEM_CONSENT -> context.platformGrantGranted
            GrantRequirement.USER_SELECTED_RESOURCE -> context.resourceSelectedByUser
            GrantRequirement.ACCESSIBILITY_SERVICE -> context.accessibilityServiceEnabled && context.appInForeground
            GrantRequirement.EXPLICIT_USER_CONFIRMATION -> context.explicitConfirmation
            GrantRequirement.BLOCKED -> false
        }
        if (!grantSatisfied) return PolicyDecision(false, true, "Required Android or user authorization has not been granted.")
        val highImpact = effect in setOf(ActionEffect.EXTERNAL_COMMUNICATION, ActionEffect.DESTRUCTIVE, ActionEffect.FINANCIAL, ActionEffect.SECURITY_SENSITIVE)
        if ((spec.requiresExplicitConfirmation || highImpact) && !context.explicitConfirmation) {
            return PolicyDecision(false, true, "This action requires final confirmation immediately before execution.")
        }
        return PolicyDecision(true, false, "Capability and action policy checks passed.")
    }
}
