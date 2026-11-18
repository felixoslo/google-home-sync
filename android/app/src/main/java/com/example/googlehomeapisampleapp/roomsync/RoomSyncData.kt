package no.klevenberg.homeyroomsync.roomsync

import android.content.Context
import no.klevenberg.homeyroomsync.BuildConfig
import java.net.URL
import java.net.URI
import java.time.Duration
import java.time.OffsetDateTime
import java.time.Instant
import java.util.Locale
import java.text.Normalizer
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class HomeyRoomEntry(val name: String, val room: String?, val deviceClass: String? = null)
data class HomeySnapshot(val devices: List<HomeyRoomEntry>, val generatedAt: Instant)
data class GoogleRoomEntry(val id: String, val name: String)
data class GoogleDeviceEntry(val id: String, val name: String, val roomId: String?)
data class ProposedMove(val id: String, val deviceName: String, val roomName: String, val previousRoomId: String?)
data class SkippedDevice(val name: String, val reason: String)
data class SyncProposal(val moves: List<ProposedMove>, val newRooms: List<String>, val correct: Int,
                        val skipped: List<SkippedDevice>)

/** Prepared off the Android UI thread; already ordered for display.
 *  No device writes are performed during analysis.
 */
data class BatchReviewRow(
  val googleId: String, val deviceName: String, val currentRoomName: String,
  val currentRoomId: String?, val homeyRoom: String?,
  val defaultChoice: String, val reason: String
)

enum class MoveProbeMode { MOVE_UNASSIGNED, SAME_ROOM_PERMISSION_PROBE, NOT_ACCESSIBLE }

data class MoveProbePlan(
  val mode: MoveProbeMode,
  val deviceId: String? = null,
  val deviceName: String? = null,
  val targetRoomId: String? = null,
  val fromRoom: String = "Uten rom",
  val toRoom: String = "",
  val explanation: String = ""
)

data class RoomReviewAnalysis(
  val candidates: List<GoogleDeviceEntry>,
  val missingHomey: List<HomeyRoomEntry>,
  val matchesByGoogleId: Map<String,List<HomeyRoomEntry>>,
  val batchRows: List<BatchReviewRow>
)


object RoomSyncData {
  const val ENDPOINT = SyncSettings.ENDPOINT
  /**
   * Public server uses the system CA trust and normal HTTPS hostname
   * verification, so Let's Encrypt renewals do not break the app.
   * Never send the private read-only token over plain HTTP or a redirect.
   */
  fun publicHttpsEndpointIsSafe(endpoint: String): Boolean {
    val url = try { URI(endpoint) } catch(_: Exception) { return false }
    return url.scheme?.lowercase(Locale.ROOT) == "https" &&
      !url.host.isNullOrBlank() && url.port in listOf(-1,443) &&
      url.rawUserInfo==null && url.rawFragment==null &&
      url.path.startsWith("/api/homey-room-sync/v1/")
  }
  fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

  fun decode(text: String): HomeySnapshot {
    val json = JSONObject(text)
    require(json.optInt("schemaVersion") == 1 && json.optString("source") == "Homey Pro") { "Ukjent Homey-inventar" }
    val timestamp = OffsetDateTime.parse(json.getString("generatedAt")).toInstant()
    val age = Duration.between(timestamp, Instant.now())
    require(age.toMinutes() in -2..180) { "Homey-data er for gamle eller har ugyldig dato" }
    val data = json.getJSONArray("devices")
    require(data.length() in 5..1000) { "Ugyldig antall Homey-enheter" }
    val entries = (0 until data.length()).map { i ->
      val entry = data.getJSONObject(i)
      HomeyRoomEntry(entry.getString("name"), if (entry.isNull("room")) null else entry.optString("room").takeIf { it.isNotBlank() },entry.optString("deviceClass").takeIf { it.isNotBlank() })
    }
    return HomeySnapshot(entries, timestamp)
  }

  private fun authenticatedPublicConnection(endpoint: String): HttpsURLConnection {
    require(publicHttpsEndpointIsSafe(endpoint)) { "Ugyldig ekstern HTTPS-adresse" }
    val conn = URL(endpoint).openConnection() as HttpsURLConnection
    // Standard Android certificate-chain and hostname verification apply.
    // A public CA-issued certificate is used on Home Assistant's existing 443 proxy.
    conn.instanceFollowRedirects = false
    conn.connectTimeout = 8000
    conn.readTimeout = 12000
    conn.setRequestProperty("X-Room-Sync-Key", BuildConfig.ROOM_SYNC_READ_TOKEN)
    conn.setRequestProperty("Accept", "application/json")
    return conn
  }
  suspend fun fetch(): HomeySnapshot = withContext(Dispatchers.IO) {
    val conn=authenticatedPublicConnection(ENDPOINT)
    try {
      val bytes=conn.inputStream.use { it.readNBytes(600_001) }
      require(bytes.size<=600_000) { "Homey-data overstiger maksimal størrelse" }
      decode(bytes.toString(Charsets.UTF_8))
    } finally { conn.disconnect() }
  }
  suspend fun reportDiagnostics(report: JSONObject): Boolean = withContext(Dispatchers.IO) {
    val conn=authenticatedPublicConnection(ENDPOINT.substringBeforeLast('/')+"/diagnostics")
    try {
      val bytes=report.toString().toByteArray(Charsets.UTF_8)
      require(bytes.size<=32768) { "Diagnoserapporten er for stor" }
      conn.requestMethod="POST"
      conn.doOutput=true
      conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      conn.responseCode==200
    } finally { conn.disconnect() }
  }

  /**
   * Homey's *actual room membership* is authoritative. A room prefix is only a
   * device-identity hint (and can be stale after a Homey room move).
   * Alias suggestions never authorize unattended Google Home changes.
   */
  fun possibleHomeyMatches(
    googleName: String,
    currentGoogleRoom: String?,
    homey: List<HomeyRoomEntry>,
    googleRooms: List<GoogleRoomEntry> = emptyList()
  ): List<HomeyRoomEntry> {
    val googleNormalized = normalize(googleName)
    if (googleNormalized.isEmpty()) return emptyList()
    val exact = homey.filter { normalize(it.name) == googleNormalized }
    if (exact.isNotEmpty()) return exact

    // Room name candidates come from actual Homey zones and existing Google
    // room names, not from guessing that the first token is always a room.
    val knownRoomNames = (homey.mapNotNull { it.room } +
      googleRooms.map { it.name }).map(::normalize)
      .filter(String::isNotBlank).distinct().sortedByDescending { it.length }

    fun unprefixed(name: String): Set<String> {
      val normalized = normalize(name)
      return buildSet {
        add(normalized)
        knownRoomNames.forEach { room ->
          if(normalized.startsWith(room + " ")) {
            add(normalized.removePrefix(room + " "))
          }
        }
      }
    }
    val googleAliases = unprefixed(googleName)
    val aliases = homey.filter { candidate ->
      val candidateAliases = unprefixed(candidate.name)
      candidateAliases.intersect(googleAliases).any { alias ->
        alias.isNotBlank() && alias != googleNormalized || 
          (candidateAliases.size > 1 && alias == googleNormalized)
      }
    }
    val location = normalize(currentGoogleRoom.orEmpty())
    return aliases.sortedWith(compareByDescending<HomeyRoomEntry> {
      normalize(it.room.orEmpty()) == location
    }.thenBy { it.name })
  }

  fun suggestedRoom(googleName: String, currentGoogleRoom: String?,
                    homey: List<HomeyRoomEntry>,
                    googleRooms: List<GoogleRoomEntry> = emptyList()): String? {
    val matches = possibleHomeyMatches(googleName, currentGoogleRoom, homey, googleRooms)
    // Never decide based on a shared alias even when possible matches happen
    // to be in the same room: entity identity must be unambiguous.
    if (matches.size != 1) return null
    return matches.single().room?.takeIf(String::isNotBlank)
  }

  /** True only when there is one unambiguous exact or room-prefix identity. */
  fun possibleGoogleNameMatch(homeyDevice: HomeyRoomEntry,
                              homey: List<HomeyRoomEntry>,
                              google: List<GoogleDeviceEntry>,
                              rooms: List<GoogleRoomEntry>): Boolean {
    return google.any { g ->
      val roomName = rooms.firstOrNull { it.id == g.roomId }?.name
      val matches = possibleHomeyMatches(g.name, roomName, homey, rooms)
      matches.size == 1 && matches.single() == homeyDevice
    }
  }

  /**
   * Pre-indexes actual Homey names and recognized zone prefixes once.
   * Former per-device missing-device check recalculated and normalized the whole
   * Homey/Google cross product for each Homey device (roughly H*G*H).
   * This builds indexes in O((H+G)*R) and resolves each Google device once.
   * This is a suggestion-only inventory analysis, NOT a move plan.
   */
  /**
   * A single safe SDK capability probe. Prefer a *real* room assignment for
   * one accessible unassigned device matched uniquely to its Homey room.
   * With none accessible, perform an explicit same-room SDK permission probe.
   * Never move an already assigned device to a different room just as a test.
   */
  fun planMoveProbe(
    homey: List<HomeyRoomEntry>,
    google: List<GoogleDeviceEntry>,
    rooms: List<GoogleRoomEntry>,
    analysis: RoomReviewAnalysis = analyzeReview(homey,google,rooms)
  ): MoveProbePlan {
    val duplicates=google.groupBy {normalize(it.name)}
    val roomNames=rooms.groupBy {normalize(it.name)}
    val known=google.filter {duplicates[normalize(it.name)]?.size==1}
      .sortedBy {it.name.lowercase(Locale.ROOT)}
    for(device in known.filter {it.roomId==null}) {
      val matched=analysis.matchesByGoogleId[device.id].orEmpty().singleOrNull()
      val homeyRoom=matched?.room?.trim().orEmpty()
      val targets=roomNames[normalize(homeyRoom)].orEmpty()
      if(homeyRoom.isNotEmpty() && targets.size==1) {
        return MoveProbePlan(MoveProbeMode.MOVE_UNASSIGNED,device.id,device.name,
          targets.single().id,"Uten rom",targets.single().name,
          "Reell flytting av enhet uten rom til dens autoritative Homey-rom")
      }
    }
    if(google.any {it.roomId==null}) {
      return MoveProbePlan(MoveProbeMode.NOT_ACCESSIBLE,
        explanation="Enheter uten rom finnes, men ingen har et entydig Homey-rom og et eksisterende Google-rom. Ingen automatisk flytting tillatt.")
    }
    // Same-room operation proves whether the SDK accepts a room-write command,
    // without changing placement. Prefer non-critical lights with exact identity.
    val inPlace=known.mapNotNull {g ->
      val h=analysis.matchesByGoogleId[g.id].orEmpty().singleOrNull()
      val current=rooms.singleOrNull {it.id==g.roomId}
      if(h!=null && current!=null &&
        h.deviceClass=="light" &&
        normalize(h.room.orEmpty())==normalize(current.name)) g to current
      else null
    }.firstOrNull()
    return if(inPlace!=null) MoveProbePlan(MoveProbeMode.SAME_ROOM_PERMISSION_PROBE,
      inPlace.first.id,inPlace.first.name,inPlace.second.id,
      inPlace.second.name,inPlace.second.name,
      "API-test til samme rom, uten flytting. Bekrefter bare at Google godtar kommandoen.")
    else MoveProbePlan(MoveProbeMode.NOT_ACCESSIBLE,
      explanation="Google Home API viser ingen enhet uten rom og ingen trygg eksisterende lys-enhet for en skriveprøve.")
  }

  fun analyzeReview(
    homey: List<HomeyRoomEntry>,
    google: List<GoogleDeviceEntry>,
    rooms: List<GoogleRoomEntry>,
    plan: SyncProposal = match(homey,google,rooms)
  ): RoomReviewAnalysis {
    val knownRooms = (homey.mapNotNull { it.room }+rooms.map { it.name })
      .map(::normalize).filter(String::isNotBlank).distinct()
    fun aliases(raw: String): Set<String> {
      val full=normalize(raw)
      if(full.isBlank()) return emptySet()
      return buildSet {
        add(full)
        for(room in knownRooms) {
          if(full.startsWith(room+" ")) {
            val suffix=full.removePrefix(room+" ")
            if(suffix.isNotBlank()) add(suffix)
          }
        }
      }
    }
    val exact=homey.groupBy { normalize(it.name) }
    val aliasIndex=mutableMapOf<String,MutableList<HomeyRoomEntry>>()
    homey.forEach { h ->
      aliases(h.name).forEach { alias ->
        aliasIndex.getOrPut(alias) { mutableListOf() }.add(h)
      }
    }
    val results=linkedMapOf<String,List<HomeyRoomEntry>>()
    val uniquelyRepresented=mutableSetOf<HomeyRoomEntry>()
    google.forEach { g ->
      val name=normalize(g.name)
      val entries=exact[name] ?: aliases(g.name).flatMap { aliasIndex[it].orEmpty() }.distinct()
      results[g.id]=entries
      if(entries.size==1) uniquelyRepresented.add(entries.single())
    }
    val moveIds=plan.moves.map {it.id}.toSet()
    val skippedNames=plan.skipped.map {normalize(it.name)}.toSet()
    val googleCandidates=google.filter {g ->
      g.id in moveIds || normalize(g.name) in skippedNames
    }.sortedWith(compareByDescending<GoogleDeviceEntry> {g ->
      results[g.id]?.singleOrNull()?.room != null
    }.thenBy {it.name.lowercase(Locale.ROOT)})
    val missing=homey.filter {h ->
      h.deviceClass in setOf("light","socket") && !h.room.isNullOrBlank() &&
        h !in uniquelyRepresented
    }.distinctBy {normalize(it.name)}.sortedBy {it.name}
    val byGoogleNames=google.groupBy { normalize(it.name) }
    val byRooms=rooms.groupBy { normalize(it.name) }
    val rows=google.sortedBy { it.name.lowercase(Locale.ROOT) }.map { g ->
      val actualRoom=rooms.firstOrNull { it.id==g.roomId }?.name ?: "Uten rom"
      val candidates=results[g.id].orEmpty()
      val unique=candidates.singleOrNull()
      val desired=unique?.room?.trim().orEmpty()
      val distinctGoogle=(byGoogleNames[normalize(g.name)]?.size ?: 0)==1
      val googleRoom=byRooms[normalize(desired)].orEmpty()
      val choice=when {
        !distinctGoogle || unique==null || desired.isEmpty() -> "KEEP"
        googleRoom.size>1 -> "KEEP"
        googleRoom.size==1 && googleRoom[0].id==g.roomId -> "KEEP"
        googleRoom.size==1 -> "ROOM:"+googleRoom.single().id
        else -> "CREATE:"+desired
      }
      val why=when {
        !distinctGoogle -> "Flere Google-enheter har samme navn. Velg manuelt."
        unique==null -> "Ingen entydig Homey-enhet. Beholdes som standard."
        desired.isEmpty() -> "Enheten har ikke et konkret rom i Homey."
        googleRoom.size>1 -> "Flere Google-rom med samme navn. Beholdes."
        choice=="KEEP" -> "Allerede i riktig rom eller beholdes."
        choice.startsWith("CREATE:") -> "Homey-rommet finnes ikke i Google Home."
        else -> "Entydig Homey-treff. Homey-rommet er prioritert."
      }
      BatchReviewRow(g.id,g.name,actualRoom,g.roomId,desired.ifBlank {null},choice,why)
    }
    return RoomReviewAnalysis(googleCandidates,missing,results,rows)
  }

  fun match(homey: List<HomeyRoomEntry>, google: List<GoogleDeviceEntry>, rooms: List<GoogleRoomEntry>): SyncProposal {
    val byHomey = homey.groupBy { normalize(it.name) }
    val byGoogle = google.groupBy { normalize(it.name) }
    val byRoom = rooms.groupBy { normalize(it.name) }
    val moves = mutableListOf<ProposedMove>()
    val skipped = mutableListOf<SkippedDevice>()
    val create = linkedSetOf<String>()
    var correct = 0
    for (device in google) {
      val name = normalize(device.name)
      val homeMatch = byHomey[name]
      val reason = when {
        name.isBlank() -> "Tomt enhetsnavn"
        (byGoogle[name]?.size ?: 0)>1 -> "Flere Google-enheter med samme navn"
        homeMatch == null -> "Navnet finnes ikke i Homey"
        homeMatch.size > 1 -> "Flere Homey-enheter med samme navn"
        homeMatch.single().room.isNullOrBlank() -> "Homey-enheten er i en overordnet/system-sone"
        (byRoom[normalize(homeMatch.single().room!!)]?.size ?: 0)>1 -> "Flere Google-rom med samme navn"
        else -> null
      }
      if (reason != null) { skipped.add(SkippedDevice(device.name,reason)); continue }
      val roomName = homeMatch!!.single().room!!
      val room = byRoom[normalize(roomName)]?.singleOrNull()
      if (room != null && device.roomId == room.id) {correct++;continue}
      if (room == null) create.add(roomName)
      moves.add(ProposedMove(device.id,device.name,roomName,device.roomId))
    }
    return SyncProposal(moves,create.toList(),correct,skipped)
  }
}
