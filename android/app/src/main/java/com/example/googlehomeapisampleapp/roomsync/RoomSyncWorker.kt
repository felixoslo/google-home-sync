package no.klevenberg.homeyroomsync.roomsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.google.home.PermissionsState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import no.klevenberg.homeyroomsync.HomeClientProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RoomSyncEntryPoint { fun homeClientProvider(): HomeClientProvider }

class RoomSyncWorker(ctx: Context, parameters: WorkerParameters): CoroutineWorker(ctx,parameters) {
  override suspend fun doWork(): Result {
    if (!RoomSyncLog.enabled(applicationContext)) return Result.success()
    val id = RoomSyncLog.structureId(applicationContext)
    if (id.isBlank()) return Result.success() // First app start must select the correct Google Home.
    return try {
      val entry = EntryPointAccessors.fromApplication(applicationContext,RoomSyncEntryPoint::class.java)
      val client = entry.homeClientProvider().getClient()
      val permission = withTimeout(20_000) { client.hasPermissions().first { it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED } }
      if (permission != PermissionsState.GRANTED) return Result.success()
      val home = withTimeout(25_000) { client.structures().first { it.isNotEmpty() } }.singleOrNull { it.id.id == id }
        ?: return Result.success() // Never alter another household.
      RoomSyncRunner.execute(applicationContext,home,client=client)
      Result.success()
    } catch (e: Exception) {
      RoomSyncLog.setStatus(applicationContext,"Bakgrunnssynk utsatt: ${e.message ?: "ukjent"}")
      RoomSyncLog.write(applicationContext,"BAKGRUNN", "Utsatt: ${e.javaClass.simpleName}")
      Result.retry()
    }
  }
}

object RoomSyncScheduler {
  fun ensure(ctx: Context) {
    if (!RoomSyncLog.enabled(ctx)) return
    val request = PeriodicWorkRequestBuilder<RoomSyncWorker>(1,TimeUnit.HOURS)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("homey-google-room-sync",
      ExistingPeriodicWorkPolicy.KEEP,request)
  }
  fun cancel(ctx: Context) {
    WorkManager.getInstance(ctx).cancelUniqueWork("homey-google-room-sync")
  }
}
