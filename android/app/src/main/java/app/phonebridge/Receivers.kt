package app.phonebridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Incoming SMS: into the persistent outbox and flushed at once, so it reaches the PC ASAP and survives offline periods. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val st = Store(ctx)
        if (st.pairing == null || st.paused || !st.syncAllowed("sms")) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val items = JSONArray()
        parts.groupBy { it.originatingAddress }.forEach { (addr, ps) ->
            items.put(JSONObject().put("pid", 0).put("box", "inbox").put("number", addr ?: "")
                .put("body", ps.joinToString("") { it.messageBody ?: "" }).put("date", System.currentTimeMillis())
                .put("date_sent", ps.first().timestampMillis).put("read", 0))
        }
        val pending = goAsync()
        Thread {
            try {
                // Enqueue only (one SQLite insert): the upload happens in the service's flusher or the WorkManager job,
                // never inside this receiver's 10 s window.
                Outbox.add(ctx, JSONObject().put("t", "sms").put("items", items))
                Outbox.resetBackoff()
            } catch (e: Exception) {
                st.error(e)
            } finally {
                pending.finish()
            }
        }.start()
        BridgeService.start(ctx)
        BridgeWorker.flushSoon(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        BridgeWorker.schedule(ctx)
        BridgeService.start(ctx)
    }
}

/** SENT / DELIVERY callbacks of send_sms. Not exported: only our own explicit PendingIntents reach it. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val code = resultCode
        Thread {
            try {
                when (intent.action) {
                    Sms.ACTION_SENT -> Sms.onSent(ctx, intent.getStringExtra("id") ?: return@Thread, code == Activity.RESULT_OK || code == 0, code)
                    Sms.ACTION_DELIVERED -> Sms.onDelivered(ctx, intent)
                }
                Outbox.kick()
            } catch (e: Exception) {
                Store(ctx).error(e)
            } finally {
                pending.finish()
            }
        }.start()
        BridgeWorker.flushSoon(ctx) // in case the service is not running (e.g. killed between send and callback)
    }
}

/** Every 15 min: restart the service if it was killed, finish stuck sends, run a delta sync and flush the outbox. */
class BridgeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val st = Store(applicationContext)
        if (st.pairing == null || st.paused) return Result.success()
        if (inputData.getBoolean(FLUSH_ONLY, false)) {
            // One-shot from a receiver: upload what is queued; WorkManager retries with its own backoff if offline.
            return if (Outbox.flush(applicationContext) == 0L) Result.success() else Result.retry()
        }
        BridgeService.start(applicationContext)
        Sms.sweep(applicationContext)
        Sync(applicationContext).run()
        Outbox.flush(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("bridge", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BridgeWorker>(15, TimeUnit.MINUTES).build())
        }

        private const val FLUSH_ONLY = "flushOnly"

        /** Network-constrained one-time flush; KEEP so a burst of SMS schedules one job. */
        fun flushSoon(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("flush", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BridgeWorker>()
                    .setInputData(Data.Builder().putBoolean(FLUSH_ONLY, true).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("bridge")
            WorkManager.getInstance(ctx).cancelUniqueWork("flush")
        }
    }
}
