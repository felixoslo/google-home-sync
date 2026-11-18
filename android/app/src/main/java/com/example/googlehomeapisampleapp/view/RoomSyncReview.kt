package no.klevenberg.homeyroomsync.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.home.Structure
import com.google.home.annotation.HomeExperimentalApi
import com.google.home.createRoom
import com.google.home.moveDevicesToRoom
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import no.klevenberg.homeyroomsync.roomsync.GoogleDeviceEntry
import no.klevenberg.homeyroomsync.roomsync.GoogleRoomEntry
import no.klevenberg.homeyroomsync.roomsync.HomeyRoomEntry
import no.klevenberg.homeyroomsync.roomsync.RoomReviewAnalysis
import no.klevenberg.homeyroomsync.roomsync.RoomSyncData
import no.klevenberg.homeyroomsync.roomsync.RoomSyncLog

/* Review is intentionally separate from the automatic exact-name sync runner.
 * Zone prefixes are displayed as hints only; never silently applied.
 */
@OptIn(HomeExperimentalApi::class)
@Composable
internal fun RoomSyncReviewPanel(
  structure: Structure,
  homey: List<HomeyRoomEntry>,
  google: List<GoogleDeviceEntry>,
  rooms: List<GoogleRoomEntry>,
  analysis: RoomReviewAnalysis,
  onRefresh: () -> Unit
) {
  if(homey.isEmpty() || google.isEmpty()) {
    Text("Venter på ny V2-lesetest før enhetslisten kan gjennomgås.")
    return
  }
  val ctx=LocalContext.current
  // Prepared entirely off the Compose main thread. Never calculate the
  // all-Homey x all-Google matching matrix from a composable.
  val allCandidates=analysis.candidates
  var showReview by remember { mutableStateOf(false) }
  var visibleCount by remember { mutableStateOf(6) }
  var showDropped by remember { mutableStateOf(false) }
  val candidates=allCandidates.filter {RoomSyncLog.googleDecision(ctx,it.id)!="DROP"}
  val dropped=allCandidates.filter {RoomSyncLog.googleDecision(ctx,it.id)=="DROP"}
  val missing=analysis.missingHomey
  var showMissing by remember { mutableStateOf(false) }

  Card(shape=RoundedCornerShape(18.dp),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
      Text("Enheter som trenger et valg",style=MaterialTheme.typography.titleMedium)
      Text(candidates.size.toString()+" Google-enheter kan vurderes. Romnavn foran enhetsnavnet, for eksempel «Loft Wallplug», brukes som et forslag – aldri som bevis.",
        style=MaterialTheme.typography.bodyMedium)
      TextButton(onClick={showReview=!showReview}) {
        Text(if(showReview) "Skjul gjennomgang" else "Vis romvalg")
      }
      if(showReview) {
        candidates.take(visibleCount).forEach { g ->
          RoomSyncReviewDevice(structure,g,homey,rooms,onRefresh)
        }
        if(candidates.size>visibleCount) {
          TextButton(onClick={visibleCount += 6}) {
            Text("Vis 6 flere ("+(candidates.size-visibleCount)+" gjenstår)")
          }
        }
      }
      if(dropped.isNotEmpty()) {
        TextButton(onClick={showDropped=!showDropped}) {
          Text(if(showDropped) "Skjul droppede" else "Vis droppede ("+dropped.size+")")
        }
        if(showDropped) dropped.forEach { g ->
          RoomSyncReviewDevice(structure,g,homey,rooms,onRefresh)
        }
      }
    }
  }
  Card(shape=RoundedCornerShape(18.dp)) {
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
      Text("Homey-enheter som ikke vises i Google",
        style=MaterialTheme.typography.titleMedium)
      Text(missing.size.toString()+" Homey-lys/kontakter uten sikkert Google-treff. Et rom kan opprettes når enheten er synlig, men appen kan ikke importere en utilgjengelig enhet gjennom rom-API-et.",
        style=MaterialTheme.typography.bodyMedium)
      TextButton(onClick={showMissing=!showMissing}) {
        Text(if(showMissing) "Skjul listen" else "Vis enheter og velg hva som kan droppes")
      }
      if(showMissing) missing.forEach { device ->
        RoomSyncMissingDevice(device)
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun RoomSyncMissingDevice(device: HomeyRoomEntry) {
  val ctx=LocalContext.current
  var ignored by remember(device.name) {
    mutableStateOf(RoomSyncData.normalize(device.name) in RoomSyncLog.ignoredMissingNames(ctx))
  }
  Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
    Text(device.name,style=MaterialTheme.typography.titleSmall)
    Text("Homey-rom: "+device.room.orEmpty(),style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={
      ignored=!ignored
      RoomSyncLog.setMissingIgnored(ctx,device.name,ignored)
      RoomSyncLog.write(ctx,"VURDERING",device.name+
        (if(ignored) ": droppet fra gjennomgang" else ": tatt med igjen"))
    }) {Text(if(ignored) "Ta med igjen" else "Dropp fra gjennomgang")}
  }
}

@OptIn(HomeExperimentalApi::class)
@Composable
private fun RoomSyncReviewDevice(
  structure: Structure,
  device: GoogleDeviceEntry,
  homey: List<HomeyRoomEntry>,
  rooms: List<GoogleRoomEntry>,
  onRefresh: () -> Unit
) {
  val ctx=LocalContext.current
  val scope=rememberCoroutineScope()
  val currentRoom=rooms.firstOrNull { it.id==device.roomId }?.name ?: "Uten rom"
  val candidates=RoomSyncData.possibleHomeyMatches(device.name,currentRoom,homey,rooms)
  val suggestion=RoomSyncData.suggestedRoom(device.name,currentRoom,homey,rooms)
  val suggestionExists=rooms.any { RoomSyncData.normalize(it.name)==RoomSyncData.normalize(suggestion.orEmpty()) }
  var decision by remember(device.id) { mutableStateOf(RoomSyncLog.googleDecision(ctx,device.id)) }
  var expanded by remember(device.id) { mutableStateOf(false) }
  var menuOpen by remember(device.id) { mutableStateOf(false) }
  // Homey's current zone wins as the initially selected destination.
  val recommendedRoomId = rooms.singleOrNull {
    RoomSyncData.normalize(it.name)==RoomSyncData.normalize(suggestion.orEmpty())
  }?.id.orEmpty()
  var selectedRoomId by remember(device.id, recommendedRoomId) {
    mutableStateOf(recommendedRoomId)
  }
  var newRoomName by remember(device.id,suggestion) {
    mutableStateOf(if(!suggestionExists) suggestion.orEmpty() else "")
  }
  var createMode by remember(device.id,suggestionExists) {
    mutableStateOf(suggestion!=null && !suggestionExists)
  }
  var busy by remember(device.id) { mutableStateOf(false) }
  var result by remember(device.id) { mutableStateOf("") }
  val selectedRoom=rooms.firstOrNull { it.id==selectedRoomId }
  Card(shape=RoundedCornerShape(14.dp),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
      Text(device.name,style=MaterialTheme.typography.titleSmall)
      Text("Nå i Google Home: "+currentRoom,style=MaterialTheme.typography.bodySmall)
      Text("Standard: behold dagens plassering til du velger en endring.",
        style=MaterialTheme.typography.bodySmall)
      when {
        decision=="KEEP" -> Text("Beholdes i dagens rom. Automatisk flytting er sperret for denne enheten.",
          color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
        decision=="DROP" -> Text("Droppet fra videre gjennomgang.",style=MaterialTheme.typography.bodySmall)
        decision=="MANUAL" -> Text("Rom valgt manuelt. Automatisk omplassering er sperret.",
          color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
        suggestion!=null -> Text("Homey-rom (prioritert): "+suggestion+
          (if(suggestionExists) "" else " (Google-rommet mangler)"),
          color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
        else -> Text("Ingen entydig romkobling: Hvilket Google-rom skal enheten være i?",
          style=MaterialTheme.typography.bodySmall)
      }
      if(candidates.size>1 && decision.isEmpty()) {
        Text("Flere mulige Homey-enheter: "+candidates.take(4).joinToString { it.name },
          style=MaterialTheme.typography.bodySmall)
      }
      if(busy) {
        LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        Text("Venter på bekreftelse fra Google Home …",
          style=MaterialTheme.typography.bodySmall)
      }
      if(result.isNotEmpty()) Text(result,style=MaterialTheme.typography.bodySmall)
      TextButton(onClick={expanded=!expanded},enabled=!busy) {
        Text(if(expanded) "Skjul valg" else if(decision.isNotEmpty()) "Endre valget" else "Velg hva appen skal gjøre")
      }
      if(expanded) {
        // Default is KEEP; no mutation happens until an explicit button is pressed.
        OutlinedButton(enabled=!busy,onClick={
          RoomSyncLog.setGoogleDecision(ctx,device.id,"KEEP")
          decision="KEEP"
          selectedRoomId=""
          createMode=false
          result="Enheten beholdes i "+currentRoom+"."
          RoomSyncLog.write(ctx,"ROMVALG",device.name+": behold i "+currentRoom)
        }) {Text("Behold enheten der den er")}
        OutlinedButton(enabled=!busy,onClick={
          RoomSyncLog.setGoogleDecision(ctx,device.id,"DROP")
          decision="DROP"
          result="Enheten er droppet fra gjennomgangen."
          RoomSyncLog.write(ctx,"ROMVALG",device.name+": droppet")
        }) {Text("Dropp denne enheten")}
        if(decision.isNotEmpty()) {
          TextButton(enabled=!busy,onClick={
            RoomSyncLog.setGoogleDecision(ctx,device.id,"")
            decision=""
            result="Kan vurderes igjen."
            RoomSyncLog.write(ctx,"ROMVALG",device.name+": gjenåpnet")
          }) {Text("Vurder enheten på nytt")}
        }
        HorizontalDivider()
        Text("Flytt bare hvis du ønsker det:",style=MaterialTheme.typography.bodyMedium)
        if(suggestion!=null && suggestionExists) {
          TextButton(enabled=!busy,onClick={
            selectedRoomId=rooms.singleOrNull {
              RoomSyncData.normalize(it.name)==RoomSyncData.normalize(suggestion)
            }?.id ?: ""
            createMode=false
          }) {Text("Bruk Homey-rommet: "+suggestion)}
        }
        Box {
          OutlinedButton(enabled=!busy,onClick={menuOpen=true}) {
            Text(selectedRoom?.name ?: "Velg Google-rom")
          }
          DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
            rooms.sortedBy { it.name }.forEach { room ->
              DropdownMenuItem(text={Text(room.name)},onClick={
                selectedRoomId=room.id
                createMode=false
                menuOpen=false
              })
            }
          }
        }
        Button(enabled=!busy && selectedRoomId.isNotBlank(),onClick={
          busy=true
          scope.launch {
            try {
              checkSelectedHome(ctx,structure)
              val targetId=selectedRoomId
              val targetRooms=withTimeout(20_000) { structure.rooms().first() }
              val target=targetRooms.single { it.id.id==targetId }
              val googleDevices=withTimeout(20_000) { structure.devices().first() }
              val current=googleDevices.single { it.id.id==device.id }
              if(current.roomId != target.id) {
                structure.moveDevicesToRoom(target,listOf(current))
                withTimeout(20_000) {
                  structure.devices().first { list ->
                    list.any { it.id.id==device.id && it.roomId==target.id }
                  }
                }
              }
              RoomSyncLog.setGoogleDecision(ctx,device.id,"MANUAL")
              decision="MANUAL"
              RoomSyncLog.write(ctx,"ROMVALG",
                device.name+": manuelt valgt "+target.name+" (bekreftet)")
              result="Bekreftet: "+target.name
              onRefresh()
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {result="Kunne ikke bekrefte flytting: "+(e.message ?: e.javaClass.simpleName).take(140)}
            finally {busy=false}
          }
        }) {Text("Flytt til valgt Google-rom")}
        TextButton(enabled=!busy,onClick={createMode=!createMode}) {
          Text(if(createMode) "Skjul romoppretting" else
          if(suggestion!=null && !suggestionExists) "Opprett Homey-rommet: "+suggestion
          else "Opprett nytt Google-rom")
        }
        if(createMode) {
          OutlinedTextField(value=newRoomName,onValueChange={newRoomName=it.take(60)},
            modifier=Modifier.fillMaxWidth(),singleLine=true,
            label={Text("Navn på nytt Google-rom")})
          Text("Rommet opprettes bare når du trykker på knappen, og enheten flyttes dit etterpå.",
            style=MaterialTheme.typography.bodySmall)
          Button(enabled=!busy && newRoomName.trim().length in 2..60,onClick={
            busy=true
            scope.launch {
              var created=false
              try {
                checkSelectedHome(ctx,structure)
                val name=newRoomName.trim()
                val before=withTimeout(20_000) {structure.rooms().first()}
                require(before.none { RoomSyncData.normalize(it.name)==RoomSyncData.normalize(name) }) {
                  "Rommet finnes allerede. Velg det fra listen i stedet."
                }
                structure.createRoom(name)
                created=true
                val room=withTimeout(20_000) {
                  structure.rooms().first { list ->
                    list.any { RoomSyncData.normalize(it.name)==RoomSyncData.normalize(name) }
                  }.single { RoomSyncData.normalize(it.name)==RoomSyncData.normalize(name) }
                }
                val googleDevices=withTimeout(20_000) {structure.devices().first()}
                val current=googleDevices.single {it.id.id==device.id}
                structure.moveDevicesToRoom(room,listOf(current))
                withTimeout(20_000) {structure.devices().first { list ->
                  list.any {it.id.id==device.id && it.roomId==room.id}
                }}
                RoomSyncLog.setGoogleDecision(ctx,device.id,"MANUAL")
                decision="MANUAL"
                RoomSyncLog.write(ctx,"ROMVALG",device.name+
                  ": rom opprettet og flytting bekreftet til "+name)
                result="Nytt rom opprettet og flytting bekreftet: "+name
                createMode=false
                onRefresh()
              } catch(e: CancellationException) {throw e}
              catch(e: Exception) {
                result=(if(created) "Rommet kan være opprettet, men flytting er ikke bekreftet: "
                  else "Kunne ikke opprette rom: ")+(e.message ?: e.javaClass.simpleName).take(140)
                RoomSyncLog.write(ctx,"ROMVALG_FEIL",device.name+": "+result.take(180))
                onRefresh()
              } finally {busy=false}
            }
          }) {Text(if(busy) "Utfører …" else "Opprett rom og flytt enheten")}
        }
      }
    }
  }
}

private fun checkSelectedHome(ctx: android.content.Context, structure: Structure) {
  check(RoomSyncLog.structureId(ctx)==structure.id.id &&
    structure.name.trim().equals(no.klevenberg.homeyroomsync.roomsync.SyncSettings.HOME_NAME,ignoreCase=true)) {
      "Feil Google-hjem. Ingen endring utført."
    }
}
