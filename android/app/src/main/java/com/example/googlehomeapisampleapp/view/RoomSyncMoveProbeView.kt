package no.klevenberg.homeyroomsync.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.home.Structure
import com.google.home.annotation.HomeExperimentalApi
import com.google.home.moveDevicesToRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.klevenberg.homeyroomsync.roomsync.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

@OptIn(HomeExperimentalApi::class)
@Composable
internal fun RoomSyncMoveProbeView(
  structure:Structure,
  homey:List<HomeyRoomEntry>,
  google:List<GoogleDeviceEntry>,
  rooms:List<GoogleRoomEntry>,
  analysis:RoomReviewAnalysis,
  onRefresh:()->Unit
) {
  val ctx=LocalContext.current
  val scope=rememberCoroutineScope()
  val plan=remember(homey,google,rooms,analysis) {
    RoomSyncData.planMoveProbe(homey,google,rooms,analysis)
  }
  var busy by remember {mutableStateOf(false)}
  var stage by remember {mutableStateOf("")}
  var result by remember {mutableStateOf("")}
  Card(shape=RoundedCornerShape(18.dp),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
    Column(Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement=Arrangement.spacedBy(8.dp)) {
      Text("Test Google Home-romflytting",style=MaterialTheme.typography.titleMedium)
      when(plan.mode) {
        MoveProbeMode.MOVE_UNASSIGNED ->
          Text("Reell flytting: "+plan.deviceName+" fra Uten rom til "+plan.toRoom,
            style=MaterialTheme.typography.bodyMedium)
        MoveProbeMode.SAME_ROOM_PERMISSION_PROBE ->
          Text("Ingen API-tilgjengelige enheter uten rom. Prøver Google-romkommando for "+
            plan.deviceName+" til samme rom ("+plan.toRoom+") uten å endre plassering.",
            style=MaterialTheme.typography.bodySmall)
        MoveProbeMode.NOT_ACCESSIBLE ->
          Text(plan.explanation,style=MaterialTheme.typography.bodySmall)
      }
      if(busy) {
        LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        Text(stage,style=MaterialTheme.typography.bodySmall)
      }
      if(result.isNotBlank()) Text(result,style=MaterialTheme.typography.bodyMedium,
        color=MaterialTheme.colorScheme.primary)
      Button(modifier=Modifier.fillMaxWidth(),enabled=!busy,onClick={
        busy=true
        result=""
        scope.launch {
          val attempted=plan.mode!=MoveProbeMode.NOT_ACCESSIBLE
          var verified=false
          var outcome="NO_ACCESSIBLE_DEVICE"
          var details=plan.explanation
          try {
            RoomSyncFailureReporter.setPhase(ctx,"roomSync.moveProbe")
            check(RoomSyncLog.structureId(ctx)==structure.id.id &&
              structure.name.trim().equals(no.klevenberg.homeyroomsync.roomsync.SyncSettings.HOME_NAME,ignoreCase=true)) {
              "Feil Google-hjem. Ingen endring utført."
            }
            if(attempted) {
              stage="Kontrollerer enhet og rom"
              val targetId=requireNotNull(plan.targetRoomId)
              val deviceId=requireNotNull(plan.deviceId)
              withContext(Dispatchers.IO) {
                val target=withTimeout(20_000) {structure.rooms().first()}
                  .single {it.id.id==targetId}
                val device=withTimeout(20_000) {structure.devices().first()}
                  .single {it.id.id==deviceId}
                when(plan.mode) {
                  MoveProbeMode.MOVE_UNASSIGNED ->
                    check(device.roomId==null) {
                      "Enheten er ikke lenger uten rom; oppdater oversikten."
                    }
                  MoveProbeMode.SAME_ROOM_PERMISSION_PROBE ->
                    check(device.roomId==target.id) {
                      "Enheten er flyttet siden sist; ingen endring utført."
                    }
                  else -> error("Ingen gyldig test")
                }
                stage="Sender romkommando til Google"
                withTimeout(25_000) {structure.moveDevicesToRoom(target,listOf(device))}
                stage="Leser bekreftet plassering fra Google"
                withTimeout(25_000) {
                  structure.devices().first {devices ->
                    devices.any {it.id.id==deviceId && it.roomId==target.id}
                  }
                }
              }
              verified=true
              outcome=if(plan.mode==MoveProbeMode.MOVE_UNASSIGNED)
                "VERIFIED_MOVE" else "API_ACCEPTED_NOOP"
              details=if(outcome=="VERIFIED_MOVE")
                "Enheten ble faktisk tilordnet rom og endringen ble lest tilbake."
              else "Google aksepterte kommando til samme rom. Ingen faktisk flytting."
              RoomSyncLog.write(ctx,"GOOGLE_API_TEST",
                plan.deviceName+": "+outcome+" ("+plan.fromRoom+" til "+plan.toRoom+")")
            } else {
              RoomSyncLog.write(ctx,"GOOGLE_API_TEST",
                "Ingen passende enhet uten rom tilgjengelig gjennom Google Home API")
            }
          } catch(e:CancellationException) {
            throw e
          } catch(e:Exception) {
            outcome="ERROR"
            details=(e.javaClass.simpleName+": "+
              (e.message?:"Ukjent Google API-feil")).take(190)
            RoomSyncFailureReporter.failure(ctx,"moveProbeError","roomSync.moveProbe",e)
            RoomSyncLog.write(ctx,"GOOGLE_API_TEST_FEIL",details)
          } finally {
            stage="Sender kontrollrapport til LinuxServer"
            val report=JSONObject()
              .put("schemaVersion",1)
              .put("appVersion",no.klevenberg.homeyroomsync.BuildConfig.VERSION_NAME)
              .put("operation","roomMoveTest")
              .put("timestamp",Instant.now().toString())
              .put("errorCode",if(outcome=="ERROR") 1 else 0)
              .put("errorMessage",if(outcome=="ERROR") details else "")
              .put("subErrors",JSONArray())
              .put("result",JSONObject()
                .put("mode",plan.mode.name)
                .put("deviceName",plan.deviceName.orEmpty().take(140))
                .put("fromRoom",plan.fromRoom.take(100))
                .put("toRoom",plan.toRoom.take(100))
                .put("outcome",outcome)
                .put("details",details.take(190))
                .put("verified",verified)
                .put("attempted",attempted))
            val sent=try {
              withTimeout(18_000) {RoomSyncData.reportDiagnostics(report)}
            } catch(e:Exception) {false}
            result=when(outcome) {
              "VERIFIED_MOVE" -> "Bekreftet: "+plan.deviceName+" ble flyttet til "+plan.toRoom+"."
              "API_ACCEPTED_NOOP" -> "API-kall godtatt. Enheten ble i "+plan.toRoom+
                ". Reell romflytting er ennå ikke bekreftet."
              "NO_ACCESSIBLE_DEVICE" -> "Ingen tilgjengelig enhet uten rom. Ingen endring utført."
              else -> "Google API-test feilet: "+details
            }+(if(sent) " Resultat lagret på LinuxServer."
                else " Rapporten kunne ikke leveres; se lokal logg.")
            RoomSyncFailureReporter.setPhase(ctx,"idle")
            busy=false
            if(outcome=="VERIFIED_MOVE") onRefresh()
          }
        }
      }) {
        Text("Kjør én Google API-test")
      }
    }
  }
}
