package no.klevenberg.homeyroomsync.roomsync

import android.content.Context
import com.google.home.Structure
import com.google.home.HomeClient
import com.google.home.annotation.HomeExperimentalApi
import com.google.home.setAsCloudLinkDefault
import com.google.home.createRoom
import com.google.home.moveDevicesToRoom
import java.time.Instant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Assigns a default Google Home for future cloud links and organizes rooms.
 * Already-linked, home-less devices cannot be silently moved by an undocumented API.
 */
@OptIn(HomeExperimentalApi::class)
object RoomSyncRunner {
  private val lock = Mutex()
  private const val MAX_MOVES_PER_RUN = 15
  private const val MAX_ROOMS_PER_RUN = 4
  private const val MIN_GAP_MS = 20 * 60 * 1000L

  suspend fun execute(ctx: Context, structure: Structure, force: Boolean = false, client: HomeClient? = null): String = lock.withLock {
    if (!RoomSyncLog.enabled(ctx)) return@withLock "Automatisk synkronisering er slått av"
    val expectedHome = RoomSyncLog.structureId(ctx)
    if (expectedHome.isBlank() || expectedHome != structure.id.id ||
        !structure.name.trim().equals(no.klevenberg.homeyroomsync.roomsync.SyncSettings.HOME_NAME, ignoreCase = true)) {
      // Fail closed if Google silently changed structure or no home was selected.
      RoomSyncLog.setStatus(ctx, "Sperret: Feil eller uregistrert Google Home-hjem")
      return@withLock "Feil Google Home-hjem"
    }
    val now = System.currentTimeMillis()
    if (!force && now - RoomSyncLog.lastCheck(ctx) in 0 until MIN_GAP_MS)
      return@withLock RoomSyncLog.lastStatus(ctx)
    RoomSyncLog.setLastCheck(ctx)
    try {
      val homey = RoomSyncData.fetch()
      // Homey's provider link is handled by Google's provider-specific account-linking flow.
      // Do not repeatedly call setAsCloudLinkDefault for this app's unrelated cloud project.
      val rooms = withTimeout(25_000) { structure.rooms().first { it.isNotEmpty() } }
      val devices = withTimeout(25_000) { structure.devices().first { it.isNotEmpty() } }
      val roomSnapshots = rooms.map { GoogleRoomEntry(it.id.id,it.name) }
      val deviceSnapshots = devices.map { GoogleDeviceEntry(it.id.id,it.name,it.roomId?.id) }
      val inStructure = deviceSnapshots.map { RoomSyncData.normalize(it.name) }.toSet()
      val globalDevices = try {
        if (client == null) emptyList() else withTimeout(15_000) { client.devices().first() }.toList()
      } catch (_: Exception) { emptyList() }
      val globalByName = globalDevices.groupBy { RoomSyncData.normalize(it.name) }
      val missing = homey.devices.filter { d ->
        d.room != null && d.deviceClass in setOf("light","socket") &&
          RoomSyncData.normalize(d.name) !in inStructure
      }.map { d ->
        val acrossAccount = globalByName[RoomSyncData.normalize(d.name)].orEmpty()
        val reason = when {
          acrossAccount.size == 1 && acrossAccount.single().structureId == null -> "${d.room} · Google-koblet uten hjem"
          acrossAccount.size == 1 && acrossAccount.single().structureId?.id != structure.id.id -> "${d.room} · Google viser enheten i annet hjem"
          else -> "${d.room} · Finnes i Homey, men ikke i valgt Google-hjem"
        }
        SkippedDevice(d.name,reason)
      }.sortedWith(compareBy({it.reason.contains("Soverom").not()},{it.name}))
      RoomSyncLog.setMissing(ctx,missing)
      val plan = RoomSyncData.match(homey.devices,deviceSnapshots,roomSnapshots)
      RoomSyncLog.setSkipped(ctx,plan.skipped)
      val eligible = plan.moves.filter { it.id !in RoomSyncLog.ignoredGoogleIds(ctx) }.take(MAX_MOVES_PER_RUN)
      val desiredRooms = eligible.map { it.roomName }.distinctBy { RoomSyncData.normalize(it) }
      val unknownRooms = desiredRooms.filter { candidate ->
        rooms.none { RoomSyncData.normalize(it.name)==RoomSyncData.normalize(candidate) }
      }
      if (unknownRooms.size > MAX_ROOMS_PER_RUN) {
        val message = "Sperret: For mange nye rom i én synkronisering (${unknownRooms.size})"
        RoomSyncLog.write(ctx,"SPERRET",message)
        RoomSyncLog.setStatus(ctx,message)
        return@withLock message
      }
      for (roomName in unknownRooms) {
        structure.createRoom(roomName)
        withTimeout(18_000) {
          structure.rooms().first { list -> list.any { RoomSyncData.normalize(it.name)==RoomSyncData.normalize(roomName) } }
        }
        RoomSyncLog.write(ctx,"ROM", "Opprettet Google Home-rom: $roomName")
      }
      var confirmed = 0
      var pending = 0
      var failed = 0
      for (move in eligible) {
        try {
          // Fresh reads guard against concurrent renames or lost permissions.
          val latestRooms = withTimeout(20_000) { structure.rooms().first { it.isNotEmpty() } }
          val target = latestRooms.single { RoomSyncData.normalize(it.name)==RoomSyncData.normalize(move.roomName) }
          val currentDevices = withTimeout(20_000) { structure.devices().first { it.isNotEmpty() } }
          val current = currentDevices.singleOrNull { it.id.id==move.id }
            ?: throw IllegalStateException("Enheten forsvant fra Google Home")
          val recalculated = RoomSyncData.match(homey.devices,
            currentDevices.map { GoogleDeviceEntry(it.id.id,it.name,it.roomId?.id) },
            latestRooms.map { GoogleRoomEntry(it.id.id,it.name) })
          if (recalculated.moves.none { it.id==move.id && RoomSyncData.normalize(it.roomName)==RoomSyncData.normalize(move.roomName) }) {
            continue // Already correct or newly ambiguous: do not move.
          }
          if (current.roomId == target.id) continue
          val from = latestRooms.firstOrNull { it.id == current.roomId }?.name ?: "Uten rom"
          structure.moveDevicesToRoom(target, listOf(current))
          val verified = try {
            withTimeout(12_000) {
              structure.devices().first { latest ->
                latest.any { it.id.id==move.id && it.roomId == target.id }
              }
            }
            true
          } catch (_: TimeoutCancellationException) { false }
          if (verified) {
            confirmed++
            RoomSyncLog.write(ctx,"FLYTTET", "${move.deviceName}: $from → ${move.roomName} (bekreftet)")
          } else {
            pending++
            RoomSyncLog.write(ctx,"UKONTROLLERT", "${move.deviceName}: $from → ${move.roomName} (kommando sendt, status ikke bekreftet)")
          }
        } catch (e: Exception) {
          failed++
          RoomSyncLog.write(ctx,"FEIL", "${move.deviceName} → ${move.roomName}: ${e.message ?: "ukjent"}")
        }
      }
      val message = "Sjekket ${deviceSnapshots.size} Google-enheter. $confirmed flyttet, $pending avventer bekreftelse, " +
        "$failed feil, ${plan.correct} riktig, ${plan.skipped.size} hoppet over. " +
        "${missing.size} lamper/kontakter i Homey mangler fra Google-hjemmet."
      RoomSyncLog.setStatus(ctx,message)
      if (confirmed+pending+failed>0 || unknownRooms.isNotEmpty()) RoomSyncLog.write(ctx,"KJØRING",message)
      message
    } catch (e: Exception) {
      val message = "Synkronisering utsatt: ${e.message ?: "ukjent feil"}"
      RoomSyncLog.setStatus(ctx,message)
      RoomSyncLog.write(ctx,"FEIL",message)
      message
    }
  }
}
