package ir.mrsiavash.elagent

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class HeartbeatWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            HubClient(SecureStore(applicationContext)).heartbeat()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
