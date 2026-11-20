package ir.mrsiavash.elagent

/**
 * Android is intentionally a smaller execution surface than Windows.
 * Only aliases with stable, explicit Android launch behavior are supported.
 */
internal object AndroidTaskPolicy {
    val supportedAppAliases = setOf("chatgpt", "browser")

    fun belongsToDevice(taskDeviceId: String?, localDeviceId: String): Boolean =
        taskDeviceId != null && taskDeviceId == localDeviceId

    fun canLaunch(
        action: String,
        requiredScope: String,
        app: String?,
        grantedScopes: Set<String>
    ): Boolean =
        action == "app.launch" &&
        requiredScope == "app.launch" &&
        "app.launch" in grantedScopes &&
        app != null &&
        app in supportedAppAliases

    fun denialCode(requiredScope: String, grantedScopes: Set<String>): String =
        if (requiredScope !in setOf("app.launch", "screen.capture") || requiredScope !in grantedScopes) {
            "permission_denied"
        } else {
            "app_not_available"
        }
}
