package no.klevenberg.homeyroomsync.roomsync

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.klevenberg.homeyroomsync.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe unattended failure reporting. Never uploads logs, stack traces, account
 * identifiers, OAuth values or device inventory. The app does not contact ChatGPT:
 * it stores a bounded report in the existing authenticated LinuxServer receiver.
 * Failed uploads remain in private app preferences until a later app start/check.
 */
object RoomSyncFailureReporter {
  private const val PREFS = "homey_room_failure_v1"
  private const val PENDING = "pending"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val flushLock = Mutex()
  @Volatile private var lastMainThreadPulse = SystemClock.uptimeMillis()
  @Volatile private var lastHangReport = 0L
  private val mainHandler = Handler(Looper.getMainLooper())
  private fun startMainThreadWatchdog(app: Application) {
    val heartbeat=object: Runnable {
      override fun run() {
        lastMainThreadPulse=SystemClock.uptimeMillis()
        mainHandler.postDelayed(this,2000L)
      }
    }
    mainHandler.post(heartbeat)
    scope.launch {
      while(true) {
        delay(5_000L)
        val phase=prefs(app).getString("phase","") ?: ""
        val now=SystemClock.uptimeMillis()
        // Only monitor a real room-sync read, never background standby.
        if(phase.startsWith("v2ReadOnly.") &&
          now-lastMainThreadPulse>15_000L && now-lastHangReport>300_000L) {
          lastHangReport=now
          enqueue(app,"mainThreadUnresponsive",phase,"heartbeat-stalled")
          flush(app)
        }
      }
    }
  }
  private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE)

  @Synchronized
  private fun enqueue(ctx: Context, type: String, phase: String, detail: String) {
    val p=prefs(ctx)
    val items=try { JSONArray(p.getString(PENDING,"[]")) }
      catch(_: Exception) { JSONArray() }
    val entry=JSONObject()
      .put("type",type.take(35)).put("phase",phase.take(70))
      .put("detail",detail.take(100)).put("timestamp",Instant.now().toString())
    items.put(entry)
    val bounded=JSONArray()
    for(i in maxOf(0,items.length()-12) until items.length())
      bounded.put(items.getJSONObject(i))
    // Crash handler uses commit to preserve the report before process termination.
    p.edit().putString(PENDING,bounded.toString()).commit()
  }

  fun setPhase(ctx: Context, phase: String) {
    prefs(ctx).edit().putString("phase",phase.take(70)).apply()
  }

  fun failure(ctx: Context, type: String, phase: String, error: Throwable? = null) {
    enqueue(ctx,type,phase,error?.javaClass?.simpleName ?: "unknown")
    schedule(ctx)
  }

  fun schedule(ctx: Context) {
    val appContext=ctx.applicationContext
    scope.launch { flush(appContext) }
  }

  private suspend fun flush(ctx: Context) = flushLock.withLock {
    repeat(12) {
      val entry = synchronized(this) {
        val array=try {JSONArray(prefs(ctx).getString(PENDING,"[]"))}
          catch(_:Exception) {JSONArray()}
        if(array.length()>0) array.optJSONObject(0) else null
      } ?: return@withLock
      val report=JSONObject()
        .put("schemaVersion",1)
        .put("appVersion",BuildConfig.VERSION_NAME)
        .put("operation","appFailure")
        .put("timestamp",entry.optString("timestamp",Instant.now().toString()))
        .put("errorCode",1)
        .put("errorMessage",(
          entry.optString("type")+"; phase="+entry.optString("phase")+
          "; class="+entry.optString("detail")).take(450))
        .put("subErrors",JSONArray())
      val sent=try { RoomSyncData.reportDiagnostics(report) }
        catch(_:Exception) { false }
      if(!sent) return@withLock
      synchronized(this) {
        val p=prefs(ctx)
        val all=try {JSONArray(p.getString(PENDING,"[]"))}
          catch(_:Exception) {JSONArray()}
        val remaining=JSONArray()
        // Compare the first entry before removing, since a crash could enqueue
        // while the upload was in progress.
        if(all.length()>0 && all.optJSONObject(0)?.toString()==entry.toString()) {
          for(i in 1 until all.length()) remaining.put(all.get(i))
          p.edit().putString(PENDING,remaining.toString()).apply()
        }
      }
    }
  }

  fun initialize(app: Application) {
    startMainThreadWatchdog(app)
    try {
      val original=Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
          val phase=prefs(app).getString("phase","outside-room-sync") ?: "unknown"
          enqueue(app,"uncaughtCrash",phase,error.javaClass.simpleName)
        } catch(_:Throwable) { /* Keep original Android crash handler intact. */ }
        if(original!=null) original.uncaughtException(thread,error)
        else Process.killProcess(Process.myPid())
      }
    } catch(_:Throwable) {}

    // Android 11+ retains ANR/crash reasons even when a process cannot send a
    // network report while stuck. Read them when the app starts again.
    scope.launch {
      if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
          val am=app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
          val history=am.getHistoricalProcessExitReasons(app.packageName,0,12)
          val p=prefs(app)
          val last=p.getLong("last_exit_timestamp",0L)
          val relevant=history.filter {info ->
            info.timestamp > last && info.reason in listOf(
              ApplicationExitInfo.REASON_ANR,
              ApplicationExitInfo.REASON_CRASH,
              ApplicationExitInfo.REASON_CRASH_NATIVE
            )
          }.sortedBy { it.timestamp }
          for(info in relevant) {
            val label=when(info.reason) {
              ApplicationExitInfo.REASON_ANR -> "androidANR"
              ApplicationExitInfo.REASON_CRASH_NATIVE -> "nativeCrash"
              else -> "androidCrash"
            }
            enqueue(app,label,p.getString("phase","unknown") ?: "unknown",
              "exit-reason-"+info.reason)
          }
          history.maxOfOrNull {it.timestamp}?.let { latest ->
            if(latest>last) p.edit().putLong("last_exit_timestamp",latest).apply()
          }
        } catch(e:Exception) { Log.w("RoomSyncFailure", "Unable to read system exit history", e) }
      }
      flush(app)
    }
  }
}
