package no.klevenberg.homeyroomsync.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.home.AvailableAgent
import no.klevenberg.homeyroomsync.roomsync.HomeyCloudLink
import no.klevenberg.homeyroomsync.roomsync.CloudLinkDiagnostics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.LinearProgressIndicator
import no.klevenberg.homeyroomsync.roomsync.RoomSyncFailureReporter
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.google.home.HomeClient
import com.google.home.PermissionsState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import no.klevenberg.homeyroomsync.roomsync.HomeyRoomEntry
import no.klevenberg.homeyroomsync.roomsync.GoogleDeviceEntry
import no.klevenberg.homeyroomsync.roomsync.GoogleRoomEntry
import no.klevenberg.homeyroomsync.roomsync.RoomReviewAnalysis
import no.klevenberg.homeyroomsync.roomsync.SyncProposal
import kotlinx.coroutines.launch
import no.klevenberg.homeyroomsync.roomsync.RoomSyncLog
import no.klevenberg.homeyroomsync.roomsync.RoomSyncData
import no.klevenberg.homeyroomsync.roomsync.RoomSyncRunner
import no.klevenberg.homeyroomsync.roomsync.RoomSyncScheduler
import no.klevenberg.homeyroomsync.viewmodel.structures.StructureViewModel

private data class RoomSyncReviewSnapshot(
  val homey: List<HomeyRoomEntry>,
  val google: List<GoogleDeviceEntry>,
  val rooms: List<GoogleRoomEntry>,
  val analysis: RoomReviewAnalysis,
  val plan: SyncProposal,
  val homeyAvailable: Boolean = true
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, com.google.home.annotation.HomeExperimentalApi::class)
@Composable
fun HomeyRoomSyncSheet(structureVM: StructureViewModel, homeClient: HomeClient, onClose: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var enabled by remember { mutableStateOf(RoomSyncLog.enabled(ctx)) }
  var busy by remember { mutableStateOf(false) }
  var refresh by remember { mutableStateOf(0) }
  var homeyAgent by remember { mutableStateOf<AvailableAgent?>(null) }
  var linkStatus by remember { mutableStateOf("Undersøker Googles Homey-kobling …") }
  var accountSummary by remember { mutableStateOf("V2-lesetest: venter på Google-data …") }
  var deliveryStatus by remember { mutableStateOf("Ingen V2-rapport sendt ennå") }
  var roomPreview by remember { mutableStateOf("Romoversikt venter på Google-data …") }
  var scanRevision by remember { mutableStateOf(0) }
  var scanEnabled by remember { mutableStateOf(true) }
  var scanActive by remember { mutableStateOf(false) }
  var scanStep by remember { mutableStateOf(0) }
  var scanStage by remember { mutableStateOf("Venter på Google Home") }
  // Publish one immutable ready-to-render snapshot only AFTER background matching.
  // Partial Compose state updates previously triggered expensive matching on main.
  var reviewSnapshot by remember(structureVM.id) {
    mutableStateOf<RoomSyncReviewSnapshot?>(null)
  }
  var showTechnical by remember { mutableStateOf(false) }
  var showHistory by remember { mutableStateOf(false) }
  var linkPending by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(structureVM.id, scanRevision, scanEnabled) {
    if(!scanEnabled) return@LaunchedEffect
    scanActive=true
    scanStep=0
    scanStage="Klargjør sikker lesetest"
    RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.prepare")
    try {
    accountSummary = "V2-lesetest pågår …"
    roomPreview = "Romoversikt pågår …"
    deliveryStatus = "Samler Google-resultater for privat rapportering …"
    var googleRoomsFallback=emptyList<GoogleRoomEntry>()
    var googleDevicesFallback=emptyList<GoogleDeviceEntry>()
    val details = JSONObject()
      .put("scope", "HOME_PLATFORM_SCOPE_VERSION_2")
      .put("selectedHome", structureVM.name.take(130))
      .put("readOnly", true)
      .put("synthetic", false)
    try {
      scanStage="Leser Google-hjem og tilgjengelige enheter"
      RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.account")
      // Google SDK and platform flows must not block the Compose main thread.
      val accessibleHomes = withTimeout(18_000) {
        withContext(Dispatchers.IO) { homeClient.structures().first { it.isNotEmpty() } }
      }
      val accountDevices = withTimeout(18_000) {
        withContext(Dispatchers.IO) { homeClient.devices().first() }
      }
      scanStep=1
      val withoutHome = accountDevices.filter { it.structureId == null }
      val chosen = accountDevices.count { it.structureId?.id == structureVM.id }
      val names = accessibleHomes.map { it.name }.sorted().joinToString(", ")
      details.put("accessibleHomeCount", accessibleHomes.size)
        .put("accessibleHomeNames", JSONArray(accessibleHomes.take(60).map { it.name.take(130) }))
        .put("accountDeviceCount", accountDevices.size)
        .put("unassignedDeviceCount", withoutHome.size)
        .put("unassignedDeviceNames", JSONArray(withoutHome.take(80).map { it.name.take(130) }))
        .put("selectedHomeDeviceCount", chosen)
      accountSummary = "V2: Tilgjengelige hjem: " + accessibleHomes.size + " (" + names + "). Synlige enheter: " + accountDevices.size + "; uten hjem: " + withoutHome.size + "; i valgt hjem: " + chosen + ". Bare hjem med tilgang telles."
      RoomSyncLog.write(ctx, "V2_TEST", accountSummary)
    } catch(e: Exception) {
      currentCoroutineContext().ensureActive()
      RoomSyncFailureReporter.failure(ctx,"googleReadError","v2ReadOnly.account",e)
      val reason = e.javaClass.simpleName + ": " + (e.message?.take(160) ?: "")
      details.put("structuresError", reason.take(500))
      accountSummary = "V2: kunne ikke lese hjem/enheter: " + reason
      RoomSyncLog.write(ctx, "V2_TESTFEIL", accountSummary)
    }
    try {
      scanStage="Kontrollerer eksisterende Homey-kobling"
      RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.provider")
      homeyAgent = withTimeout(20_000) {
        withContext(Dispatchers.IO) { HomeyCloudLink.find(ctx,structureVM.structure) }
      }
      scanStep=2
      linkStatus = "V2: Homey funnet. Allerede koblet: " + homeyAgent?.isAlreadyLinked
      details.put("providerFound", true)
        .put("providerAlreadyLinked", homeyAgent?.isAlreadyLinked)
    } catch(e: Exception) {
      currentCoroutineContext().ensureActive()
      RoomSyncFailureReporter.failure(ctx,"providerReadError","v2ReadOnly.provider",e)
      scanStep=2
      details.put("providerFound", false)
        .put("providerError", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(500))
      linkStatus=CloudLinkDiagnostics.record(ctx,"listAvailableAgents",e)
    }
    try {
      scanStage="Verifiserer Google Home-tillatelse"
      RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.permissions")
      val permission = withTimeout(10_000) {
        withContext(Dispatchers.IO) {
          homeClient.hasPermissions().first {
            it != PermissionsState.PERMISSIONS_STATE_UNINITIALIZED
          }
        }
      }
      scanStep=3
      details.put("permissionsState", permission.name)
      check(permission == PermissionsState.GRANTED) { "Google Home-tilgangen er ikke gitt" }
      val structure = structureVM.structure
      scanStage="Henter rom og enheter fra Google Home"
      RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.rooms")
      val rooms = withTimeout(18_000) {
        withContext(Dispatchers.IO) { structure.rooms().first() }
      }.map { GoogleRoomEntry(it.id.id,it.name) }
      val devices = withTimeout(18_000) {
        withContext(Dispatchers.IO) { structure.devices().first() }
      }.map { GoogleDeviceEntry(it.id.id,it.name,it.roomId?.id) }
      googleRoomsFallback=rooms
      googleDevicesFallback=devices
      scanStep=4
      scanStage="Sammenligner med romtilordningene i Homey"
      RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.match")
      val homey = withTimeout(22_000) { RoomSyncData.fetch() }
      // No UI inventory state is published until the indexed analysis is done.
      // This avoids main-thread recomposition during the heaviest task.
      val prepared = withTimeout(12_000) {
        withContext(Dispatchers.Default) {
      val plan = RoomSyncData.match(homey.devices, devices, rooms)
      val analysis = RoomSyncData.analyzeReview(homey.devices,devices,rooms,plan)
      val missing = analysis.missingHomey
      val withoutRoom = devices.count { it.roomId == null }
      details.put("googleRoomCount", rooms.size)
        .put("devicesWithoutRoomCount", withoutRoom)
        .put("correctRoomCount", plan.correct)
        .put("proposedMoveCount", plan.moves.size)
        .put("proposedNewRoomCount", plan.newRooms.size)
        .put("skippedDeviceCount", plan.skipped.size)
        .put("missingHomeyLightSocketCount", missing.size)
      val roomNames = rooms.associate { it.id to it.name }
      val entries = listOf(
        "missingHomeyDevices" to missing.sortedBy { it.name }.map {
          JSONObject().put("name", it.name.take(100)).put("room", it.room.orEmpty().take(100))
            .put("deviceClass", it.deviceClass.orEmpty().take(100))
        },
        "skippedGoogleDevices" to plan.skipped.sortedBy { it.name }.map { item ->
          val googleDevice = devices.firstOrNull { it.name == item.name }
          val googleRoomName = rooms.firstOrNull { it.id == googleDevice?.roomId }?.name
          val matches = googleDevice?.id?.let { analysis.matchesByGoogleId[it] }.orEmpty()
          val hint = if(matches.size == 1 && item.reason == "Navnet finnes ikke i Homey")
            "Mulig navneprefiks; Homey-rom: "+matches.single().room.orEmpty()+"; velg manuelt"
          else item.reason
          JSONObject().put("name", item.name.take(100)).put("reason", hint.take(100))
        },
        "proposedRoomMoves" to plan.moves.sortedBy { it.deviceName }.map {
          JSONObject().put("name", it.deviceName.take(100))
            .put("fromRoom", roomNames[it.previousRoomId].orEmpty().take(100))
            .put("toRoom", it.roomName.take(100))
        },
      )
      for ((key, rows) in entries) {
        val list = JSONArray()
        details.put(key, list)
        for (row in rows.take(80)) {
          list.put(row)
          if (details.toString().toByteArray(Charsets.UTF_8).size > 24_000) {
            list.remove(list.length() - 1)
            break
          }
        }
      }
      val preview = "Romoversikt: " + rooms.size + " rom, " + withoutRoom + " enheter uten rom, " +
        plan.correct + " riktig plassert, " + plan.moves.size + " foreslåtte flyttinger, " +
        plan.skipped.size + " hoppet over og " + missing.size + " Homey-lamper/kontakter uten navnetreff."
      Triple(preview,plan,analysis)
        }
      }
      // One atomic handover: Compose displays an immutable, precomputed snapshot.
      reviewSnapshot=RoomSyncReviewSnapshot(
        homey.devices,devices,rooms,prepared.third,prepared.second)
      roomPreview=prepared.first
      scanStep=5
    } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      RoomSyncFailureReporter.failure(ctx,"roomPreviewError",
        "v2ReadOnly.rooms-or-match",e)
      val reason = (e.message ?: e.javaClass.simpleName).take(300)
      reviewSnapshot=if(googleDevicesFallback.isNotEmpty()) {
        // Show safe Google-only inventory if LinuxServer cannot be reached,
        // but never allow changes without a current authoritative Homey snapshot.
        withContext(Dispatchers.Default) {
          val plan=RoomSyncData.match(emptyList(),googleDevicesFallback,googleRoomsFallback)
          val analysis=RoomSyncData.analyzeReview(
            emptyList(),googleDevicesFallback,googleRoomsFallback,plan)
          RoomSyncReviewSnapshot(emptyList(),googleDevicesFallback,googleRoomsFallback,
            analysis,plan,homeyAvailable=false)
        }
      } else null
      details.put("roomPreviewError", reason)
      roomPreview = if(reviewSnapshot!=null)
        "Google Home-data lest inn, men Homey-inventar utilgjengelig via internett. Listen er skrivebeskyttet. " + reason
      else "Romoversikt kunne ikke leses. " + reason
    }
    scanStage="Sender kontrollrapport sikkert til LinuxServer"
    RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.delivery")
    val report=JSONObject()
      .put("schemaVersion",1)
      .put("appVersion",no.klevenberg.homeyroomsync.BuildConfig.VERSION_NAME)
      .put("operation","v2ReadOnlyResults")
      .put("timestamp",java.time.Instant.now().toString())
      .put("errorCode",0)
      .put("errorMessage","")
      .put("subErrors",JSONArray())
      .put("result",details)
    val delivered=try {withTimeout(22_000) {RoomSyncData.reportDiagnostics(report)}}
      catch(e: Exception) {
      currentCoroutineContext().ensureActive()
      RoomSyncFailureReporter.failure(ctx,"reportDeliveryError","v2ReadOnly.delivery",e)
      RoomSyncLog.write(ctx,"V2_LEVERING_FEIL",(e.javaClass.simpleName+": "+(e.message ?: "")).take(180))
      false
    }
    deliveryStatus=if(delivered)
      "V2-rapport levert til LinuxServer. ChatGPT kan lese den ved neste kontroll."
    else
      "V2-rapport IKKE levert over offentlig HTTPS. Sjekk internett og prøv igjen."
    RoomSyncLog.write(ctx,if(delivered) "V2_LEVERT" else "V2_IKKE_LEVERT",deliveryStatus)
    if (!details.has("structuresError") && !details.has("roomPreviewError") &&
        details.optString("permissionsState") == "GRANTED") {
      RoomSyncLog.recordV2Read(ctx,roomPreview)
    }
    scanStep=6
    scanStage=if(delivered) "Kontrollen er ferdig – rapport mottatt" else
      "Kontrollen er ferdig – LinuxServer kunne ikke bekrefte mottak"
    RoomSyncFailureReporter.setPhase(ctx,"idle")
    RoomSyncFailureReporter.schedule(ctx)
    refresh++
    } catch(e: CancellationException) {
      // A user pressing Avbryt does not count as an app failure.
      if(!scanEnabled) scanStage="Lesetesten ble avbrutt, uten endringer."
      else scanStage="Lesetesten ble avbrutt."
      throw e
    } catch(e: Exception) {
      scanStage="Lesetesten stoppet: "+e.javaClass.simpleName
      RoomSyncFailureReporter.failure(ctx,"scanUncaughtError","v2ReadOnly.topLevel",e)
      RoomSyncLog.write(ctx,"V2_FEIL",scanStage)
    } finally {
      scanActive=false
    }
  }
  val currentLinkPending = androidx.compose.runtime.rememberUpdatedState(linkPending)
  DisposableEffect(lifecycleOwner) {
    val observer=LifecycleEventObserver { _,event ->
      if(event == Lifecycle.Event.ON_RESUME && currentLinkPending.value) {
        linkPending=false
        scope.launch {
          busy=true
          try {
            kotlinx.coroutines.delay(1500)
            val before=RoomSyncLog.lastMissing(ctx).size
            try { kotlinx.coroutines.withTimeout(30_000) { homeClient.syncLinkedDevices() } }
            catch(_: Exception) { RoomSyncLog.write(ctx,"KOBLING","Google-oppdatering kunne ikke bekreftes; leser faktisk enhetsliste.") }
            RoomSyncRunner.execute(ctx,structureVM.structure,force=true,client=homeClient)
            val after=RoomSyncLog.lastMissing(ctx).size
            RoomSyncLog.write(ctx,"KOBLING_KONTROLL","Utenfor valgt hjem før/etter: $before/$after. Uendret antall bekrefter ikke vellykket hjemtilordning.")
            linkStatus="Etterkontroll utført. Se status og logg for faktisk resultat."
          } finally { busy=false;refresh++ }
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  var showMissing by remember { mutableStateOf(false) }
  var showSkipped by remember { mutableStateOf(false) }
  // Do not recompose the entire full-screen dialog every three seconds.
  // The review list is expensive; explicitly update it when the scan completes.
  val _refresh = refresh
  val status = RoomSyncLog.lastStatus(ctx)
  val skipped = RoomSyncLog.lastSkipped(ctx)
  val history = RoomSyncLog.history(ctx).take(60)
  fun formatTime(ms: Long) = if (ms > 0)
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())
      .format(Instant.ofEpochMilli(ms)) else "Aldri"
  val readTime = formatTime(RoomSyncLog.lastV2Read(ctx))
  val syncTime = formatTime(RoomSyncLog.lastCheck(ctx))
  val snapshot=reviewSnapshot

  Dialog(onDismissRequest=onClose,
    properties=DialogProperties(usePlatformDefaultWidth=false)) {
    Surface(modifier=Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
      Column(modifier=Modifier.fillMaxSize().safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=12.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
          Column(modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("HOMEY  →  GOOGLE HOME",
              style=MaterialTheme.typography.labelMedium,
              color=MaterialTheme.colorScheme.primary)
            Text("Room Sync",style=MaterialTheme.typography.headlineMedium)
            Text(structureVM.name,style=MaterialTheme.typography.bodyMedium,
              color=MaterialTheme.colorScheme.onSurfaceVariant)
          }
          TextButton(onClick=onClose) {Text("Lukk")}
        }
        Card(shape=RoundedCornerShape(18.dp),
          colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
          Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Oversikt",style=MaterialTheme.typography.titleMedium)
            if(snapshot==null) {
              Text(if(scanActive) "Klargjør ferdig enhetsoversikt …"
                else "Ingen fullført enhetsoversikt. Trykk Oppdater for ny lesetest.")
            } else {
              val p=snapshot.plan
              Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(modifier=Modifier.weight(1f)) {
                  Text(snapshot.google.size.toString(),style=MaterialTheme.typography.headlineSmall)
                  Text("Google-enheter",style=MaterialTheme.typography.labelSmall)
                }
                Column(modifier=Modifier.weight(1f)) {
                  Text(if(snapshot.homeyAvailable) p.correct.toString() else "—",
                    style=MaterialTheme.typography.headlineSmall)
                  Text("Riktige rom",style=MaterialTheme.typography.labelSmall)
                }
                Column(modifier=Modifier.weight(1f)) {
                  Text(p.skipped.size.toString(),style=MaterialTheme.typography.headlineSmall)
                  Text("Til gjennomgang",style=MaterialTheme.typography.labelSmall)
                }
              }
              Text("Google-rom: "+snapshot.rooms.size+
                "   •   Sikkert flyttebehov: "+
                (if(snapshot.homeyAvailable) p.moves.size.toString() else "Ikke kontrollert"),
                style=MaterialTheme.typography.bodyMedium)
              if(!snapshot.homeyAvailable) {
                Text("Kun Google Home-data tilgjengelig. Ingen flytting tillates uten Homey-inventar.",
                  color=MaterialTheme.colorScheme.error,
                  style=MaterialTheme.typography.bodyMedium)
              }
            }
            HorizontalDivider()
            Text("Siste fullførte V2-lesetest: "+readTime,
              style=MaterialTheme.typography.bodyMedium)
            if(scanActive) {
              Text(scanStage,style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.primary)
              LinearProgressIndicator(progress={scanStep / 6f},
                modifier=Modifier.fillMaxWidth())
              Text("Trinn "+scanStep+" av 6 – viser arbeidssteg, ikke tidsestimat",
                style=MaterialTheme.typography.bodySmall)
              OutlinedButton(onClick={
                scanEnabled=false
                scanStage="Avbrutt av bruker – ingen enheter endret"
                RoomSyncFailureReporter.setPhase(ctx,"v2ReadOnly.cancelled")
              },modifier=Modifier.fillMaxWidth()) {Text("Avbryt lesetest")}
            } else {
              Text(scanStage,style=MaterialTheme.typography.bodySmall)
              Text(deliveryStatus,style=MaterialTheme.typography.bodySmall)
              Button(onClick={
                scanEnabled=true
                scanRevision++
              },enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                Text("Oppdater oversikten (trygg lesetest)")
              }
            }
          }
        }
        if(snapshot!=null) {
          RoomSyncBatchReviewPanel(structureVM.structure,
            snapshot.analysis,snapshot.rooms,
            allowWrites=snapshot.homeyAvailable,onRefresh={scanRevision++})
        }
        Card(shape=RoundedCornerShape(18.dp)) {
          Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Automatisk synkronisering",
              style=MaterialTheme.typography.titleMedium)
            Text("Avslått – låst i tidlig alfa",
              style=MaterialTheme.typography.bodyMedium,
              color=MaterialTheme.colorScheme.primary)
            Text("For å unngå utilsiktede flyttinger brukes nå bare den bekreftede samleknappen nederst i enhetslisten. Automatisk bakgrunnssynk er deaktivert i denne alfaen.",
              style=MaterialTheme.typography.bodySmall)
            Text("Siste romkontroll: "+syncTime,
              style=MaterialTheme.typography.bodySmall)
          }
        }
        Card(shape=RoundedCornerShape(18.dp)) {
          Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Tilkobling og teknisk informasjon",
              style=MaterialTheme.typography.titleMedium)
            Text(if(homeyAgent?.isAlreadyLinked==true) "Homey er koblet til Google Home"
              else linkStatus,style=MaterialTheme.typography.bodyMedium)
            Text("Homey-inventar og feilrapporter hentes via offentlig HTTPS på den konfigurerte HTTPS-adressen med egen appnøkkel. Fungerer på mobilnett og Wi-Fi uten VPN.",
              style=MaterialTheme.typography.bodySmall)
            Text("Appfeil, langvarig heng (ANR) og tidsavbrudd sendes automatisk og sikkert til LinuxServer. Frakoblede rapporter lagres på telefonen og sendes ved neste mulighet.",
              style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={showTechnical=!showTechnical}) {
              Text(if(showTechnical) "Skjul teknisk status" else "Vis teknisk status")
            }
            if(showTechnical) {
              Text(accountSummary,style=MaterialTheme.typography.bodySmall)
              Text(roomPreview,style=MaterialTheme.typography.bodySmall)
              Text("Faktisk synk: "+status,style=MaterialTheme.typography.bodySmall)
              if(homeyAgent==null) Text("Homey-leverandør er ikke bekreftet tilgjengelig.")
              OutlinedButton(enabled=!busy && homeyAgent!=null &&
                homeyAgent?.isAlreadyLinked!=true,onClick={
                scope.launch {
                  busy=true
                  try {
                    HomeyCloudLink.open(ctx,structureVM.structure,homeyAgent!!)
                    linkPending=true
                    linkStatus="Fullfør Googles egen innlogging."
                  } catch(e:Exception) {
                    linkStatus=CloudLinkDiagnostics.record(ctx,"initiateCloudAccountLink",e)
                  } finally {busy=false;refresh++}
                }
              }) {Text("Koble Homey til Google")}
            }
          }
        }
        Card(shape=RoundedCornerShape(18.dp)) {
          Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(modifier=Modifier.fillMaxWidth(),
              horizontalArrangement=Arrangement.SpaceBetween) {
              Text("Aktivitetslogg",style=MaterialTheme.typography.titleMedium)
              TextButton(enabled=!busy && history.isNotEmpty(),
                onClick={RoomSyncLog.clearHistory(ctx);refresh++}) {Text("Tøm")}
            }
            TextButton(onClick={showHistory=!showHistory}) {
              Text(if(showHistory) "Skjul logg" else "Vis logg ("+history.size+")")
            }
            if(showHistory) {
              if(history.isEmpty()) Text("Ingen aktiviteter registrert.")
              history.take(30).forEach {
                Text(it,style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
              }
            }
          }
        }
        Text("Enheter uten tilgang i Googles API kan ikke flyttes. Du bestemmer plasseringen ved usikre navn. Alle manuelle endringer kontrolleres ved å lese Google Home på nytt.",
          style=MaterialTheme.typography.bodySmall,
          color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
