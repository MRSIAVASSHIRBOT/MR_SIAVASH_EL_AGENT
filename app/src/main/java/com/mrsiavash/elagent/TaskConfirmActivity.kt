package com.mrsiavash.elagent

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class TaskConfirmActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var message: TextView
    private lateinit var accept: Button
    private lateinit var reject: Button
    private var taskId: String = ""
    private var alias: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        alias = intent.getStringExtra(EXTRA_ALIAS).orEmpty()
        val store = SecureStore(this)
        if (taskId.isBlank()) taskId = store.pendingTaskId().orEmpty()
        if (alias.isBlank()) alias = store.pendingAlias().orEmpty()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        message = TextView(this).apply { textSize = 18f; text = "وظیفه Hub: بازکردن برنامه «$alias»\n\nاین عمل فقط پس از تأیید شما اجرا می‌شود." }
        accept = Button(this).apply { text = "تأیید و بازکردن برنامه" }
        reject = Button(this).apply { text = "ردکردن وظیفه" }
        layout.addView(message)
        layout.addView(accept)
        layout.addView(reject)
        setContentView(layout)

        accept.setOnClickListener { complete(approve = true) }
        reject.setOnClickListener { complete(approve = false) }
    }

    private fun complete(approve: Boolean) {
        accept.isEnabled = false
        reject.isEnabled = false
        message.text = "در حال ثبت نتیجه…"
        executor.execute {
            val store = SecureStore(this)
            try {
                if (approve && !store.pendingWasLaunched()) {
                    runOnUiThread { message.text = "در حال بازکردن برنامه…" }
                    runOnUiThread {
                        try {
                            AndroidActions.launch(this, alias)
                            store.markPendingLaunched()
                            sendCompletion(true, "launched")
                        } catch (error: Exception) {
                            sendCompletion(false, if (error is UnsupportedOperationException) "unsupported_platform" else "app_not_available")
                        }
                    }
                } else {
                    sendCompletion(false, "launch_failed")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    message.text = "خطا: ${error.message ?: "ارتباط با Hub برقرار نشد"}"
                    accept.isEnabled = true
                    reject.isEnabled = true
                }
            }
        }
    }

    private fun sendCompletion(succeeded: Boolean, code: String) {
        executor.execute {
            try {
                HubClient(this).completeTask(taskId, succeeded, code)
                SecureStore(this).clearPendingTask()
                runOnUiThread {
                    message.text = if (succeeded) "وظیفه با موفقیت ثبت شد." else "وظیفه رد شد یا اجرا نشد."
                    accept.isEnabled = false
                    reject.isEnabled = false
                }
            } catch (error: Exception) {
                runOnUiThread {
                    message.text = "نتیجه محلی ثبت شد، اما گزارش به Hub نرسید: ${error.message}"
                    accept.isEnabled = true
                    reject.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_ALIAS = "app_alias"
    }
}
