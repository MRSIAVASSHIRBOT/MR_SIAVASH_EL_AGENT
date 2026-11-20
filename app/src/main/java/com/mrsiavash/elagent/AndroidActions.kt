package com.mrsiavash.elagent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

object AndroidActions {
    fun launch(activity: Activity, alias: String) {
        val intent = when (alias) {
            "calculator" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            "browser" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            "chatgpt" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))
            else -> throw UnsupportedOperationException("این برنامه در فهرست مجاز Android نیست.")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            activity.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("برنامه موردنظر روی این دستگاه نصب یا در دسترس نیست.", error)
        }
    }
}
