package no.klevenberg.homeyroomsync.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.home.Structure
import com.google.home.annotation.HomeExperimentalApi
import com.google.home.createRoom
import com.google.home.moveDevicesToRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.klevenberg.homeyroomsync.roomsync.*

private fun moveChoice(choice:String) = choice.startsWith("ROOM:") || choice.startsWith("CREATE:")
private fun optionLabel(choice:String, rooms:List<GoogleRoomEntry>):String=when {
  choice=="KEEP" -> "Behold"
  choice=="IGNORE" -> "Overse"
  choice.startsWith("ROOM:") -> rooms.firstOrNull {it.id==choice.removePrefix("ROOM:")}?.name ?: "Ukjent"
  choice.startsWith("CREATE:") -> "Nytt: "+choice.removePrefix("CREATE:")
  else -> "Behold"
}

@OptIn(HomeExperimentalApi::class)
@Composable
internal fun RoomSyncBatchReviewPanel(
  structure:Structure, analysis:RoomReviewAnalysis, rooms:List<GoogleRoomEntry>,
  allowWrites:Boolean=true, onRefresh:()->Unit
) {
  val context=LocalContext.current
  val scope=rememberCoroutineScope()
  val rows=analysis.batchRows
  val choices=remember(analysis) {mutableStateMapOf<String,String>()}
  var all by remember(analysis) {mutableStateOf(false)}
  var showMissing by remember(analysis) {mutableStateOf(false)}
  var limit by remember(analysis) {mutableIntStateOf(12)}
  var confirm by remember {mutableStateOf(false)}
  var busy by remember {mutableStateOf(false)}
  var progress by remember {mutableIntStateOf(0)}
  var total by remember {mutableIntStateOf(0)}
  var current by remember {mutableStateOf("")}
  var message by remember {mutableStateOf("")}
  fun chosen(row:BatchReviewRow):String = choices[row.googleId] ?: when(
    RoomSyncLog.googleDecision(context,row.googleId)) {
      "DROP" -> "IGNORE"
      "KEEP","MANUAL" -> "KEEP"
      else -> row.defaultChoice
    }
  val pending=rows.mapNotNull {r->
    val c=chosen(r)
    if(moveChoice(c) && !(c.startsWith("ROOM:") && c.removePrefix("ROOM:")==r.currentRoomId))
      r to c else null
  }
  val newRooms=pending.mapNotNull {(_,c)->c.takeIf {it.startsWith("CREATE:")}
    ?.removePrefix("CREATE:")}.distinctBy(RoomSyncData::normalize)
  val shown=if(all) rows else rows.filter {r->
    val c=chosen(r);c!="KEEP" || r.homeyRoom==null || r.reason.startsWith("Flere")
  }
  Card(shape=RoundedCornerShape(20.dp),
    colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
    Column(Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement=Arrangement.spacedBy(12.dp)) {
      Text("Enheter og romvalg",style=MaterialTheme.typography.titleLarge)
      Text(if(allowWrites)
        "Homeys faktiske rom er standard ved entydige treff. Uklare navn beholdes."
        else "Homey-inventar utilgjengelig. Google-listen vises bare for lesing.",
        style=MaterialTheme.typography.bodyMedium)
      Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ReviewCount(rows.size.toString(),"Google",Modifier.weight(1f))
        ReviewCount(rows.count {it.homeyRoom==null}.toString(),"Uavklart",Modifier.weight(1f))
        ReviewCount(pending.size.toString(),"Valgt",Modifier.weight(1f))
      }
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        TextButton(enabled=!busy,onClick={all=!all;limit=12}) {
          Text(if(all) "Vis kun forslag" else "Vis alle enheter")
        }
        Text(if(all) "Alle" else "Forslag og avvik",style=MaterialTheme.typography.labelMedium)
      }
      HorizontalDivider()
      shown.take(limit).forEach {r->
        var dropdown by remember(r.googleId) {mutableStateOf(false)}
        val c=chosen(r)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
          Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(r.deviceName,style=MaterialTheme.typography.bodyMedium,
              fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text("Home: "+r.currentRoomName+" · Homey: "+(r.homeyRoom?:"?"),
              style=MaterialTheme.typography.labelSmall,maxLines=2)
          }
          Box {
            OutlinedButton(enabled=allowWrites && !busy,onClick={dropdown=true},
              modifier=Modifier.widthIn(min=90.dp,max=144.dp),
              contentPadding=PaddingValues(horizontal=6.dp,vertical=5.dp)) {
              Text(optionLabel(c,rooms),maxLines=2,
                style=MaterialTheme.typography.labelMedium)
            }
            DropdownMenu(expanded=dropdown,onDismissRequest={dropdown=false}) {
              DropdownMenuItem(text={Text("Behold i "+r.currentRoomName)},onClick={
                choices[r.googleId]="KEEP"
                RoomSyncLog.setGoogleDecision(context,r.googleId,"KEEP")
                dropdown=false
              })
              DropdownMenuItem(text={Text("Overse denne enheten")},onClick={
                choices[r.googleId]="IGNORE"
                RoomSyncLog.setGoogleDecision(context,r.googleId,"DROP")
                dropdown=false
              })
              if(r.defaultChoice.startsWith("CREATE:")) {
                DropdownMenuItem(text={Text("Opprett "+r.homeyRoom)},onClick={
                  choices[r.googleId]=r.defaultChoice
                  RoomSyncLog.setGoogleDecision(context,r.googleId,"")
                  dropdown=false
                })
              }
              rooms.sortedBy {it.name}.forEach {room->
                DropdownMenuItem(text={Text("Flytt til "+room.name)},onClick={
                  choices[r.googleId]="ROOM:"+room.id
                  RoomSyncLog.setGoogleDecision(context,r.googleId,"")
                  dropdown=false
                })
              }
            }
          }
          TextButton(enabled=allowWrites && !busy,onClick={
            choices[r.googleId]="IGNORE"
            RoomSyncLog.setGoogleDecision(context,r.googleId,"DROP")
          },contentPadding=PaddingValues(horizontal=2.dp,vertical=5.dp)) {
            Text("Overse",style=MaterialTheme.typography.labelSmall)
          }
        }
        HorizontalDivider()
      }
      if(shown.size>limit) OutlinedButton(enabled=!busy,
        modifier=Modifier.fillMaxWidth(),onClick={limit+=12}) {
        Text("Vis 12 flere ("+(shown.size-limit)+" gjenstår)")
      }
      Text(pending.size.toString()+" flyttinger valgt · "+newRooms.size+
        " nye rom. Ingen endring er utført ennå.",
        style=MaterialTheme.typography.bodySmall)
      if(busy) {
        LinearProgressIndicator(progress={if(total>0) progress.toFloat()/total else 0f},
          modifier=Modifier.fillMaxWidth())
        Text(progress.toString()+" av "+total+" · "+current,
          style=MaterialTheme.typography.bodySmall)
      }
      if(analysis.missingHomey.isNotEmpty()) {
        HorizontalDivider()
        TextButton(onClick={showMissing=!showMissing},enabled=!busy) {
          Text((if(showMissing) "Skjul" else "Vis")+" "+
            analysis.missingHomey.size+" Homey-enheter uten sikkert Google-treff")
        }
        if(showMissing) {
          Text("Disse enhetene må være synlige i Google Home før appen kan plassere dem i et rom.",
            style=MaterialTheme.typography.bodySmall)
          analysis.missingHomey.take(30).forEach {h ->
            Text(h.name+"  ·  "+h.room.orEmpty(),
              style=MaterialTheme.typography.labelMedium)
          }
        }
      }
      if(message.isNotEmpty()) Text(message,style=MaterialTheme.typography.bodyMedium)
      Button(enabled=allowWrites && !busy && pending.isNotEmpty(),
        modifier=Modifier.fillMaxWidth(),
        onClick={confirm=true}) {
        Text("Utfør "+pending.size+" valgte flyttinger")
      }
      Text("Du må bekrefte antall flyttinger og nye rom før noen Google-rom endres.",
        style=MaterialTheme.typography.labelSmall)
    }
  }
  if(confirm) AlertDialog(onDismissRequest={if(!busy)confirm=false},
    title={Text("Bekreft romflytting")},
    text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
      Text("Flytt "+pending.size+" Google-enheter til valgte rom.")
      Text("Nye rom: "+(if(newRooms.isEmpty())"Ingen" else newRooms.joinToString(", ")))
      Text("Behold/Overse blir ikke flyttet. Lysstatus og Homey Flows endres aldri.")
    }},
    confirmButton={Button(onClick={
      val tasks=pending.toList()
      confirm=false
      busy=true;progress=0;total=tasks.size;message=""
      scope.launch {
        try {
          require(RoomSyncLog.structureId(context)==structure.id.id &&
            structure.name.trim().equals(no.klevenberg.homeyroomsync.roomsync.SyncSettings.HOME_NAME,true)) {
            "Feil Google-hjem. Ingen endringer utført."
          }
          withContext(Dispatchers.IO) {
            for((r,c) in tasks) {
              current=r.deviceName
              val available=withTimeout(25000) {structure.rooms().first()}
              val target=if(c.startsWith("ROOM:")) {
                available.single {it.id.id==c.removePrefix("ROOM:")}
              } else {
                val name=c.removePrefix("CREATE:").trim()
                require(name.length in 2..60)
                val normalized=RoomSyncData.normalize(name)
                available.singleOrNull {RoomSyncData.normalize(it.name)==normalized}
                  ?:run {
                    withTimeout(25000) {structure.createRoom(name)}
                    withTimeout(25000) {structure.rooms().first {list->
                      list.any {RoomSyncData.normalize(it.name)==normalized}
                    }.single {RoomSyncData.normalize(it.name)==normalized}}
                  }
              }
              val device=withTimeout(25000) {structure.devices().first()}
                .single {it.id.id==r.googleId}
              if(device.roomId!=target.id) {
                withTimeout(25000) {structure.moveDevicesToRoom(target,listOf(device))}
                withTimeout(25000) {structure.devices().first {list->
                  list.any {it.id.id==r.googleId && it.roomId==target.id}
                }}
              }
              RoomSyncLog.setGoogleDecision(context,r.googleId,"MANUAL")
              RoomSyncLog.write(context,"BATCH_ROM",
                r.deviceName+" -> "+target.name+" (verifisert)")
              progress++
            }
          }
          message="Fullført: "+progress+" av "+total+" romtilordninger verifisert."
          onRefresh()
        } catch(e:CancellationException) {
          message="Avbrutt etter "+progress+" av "+total
          throw e
        } catch(e:Exception) {
          RoomSyncFailureReporter.failure(context,"batchMoveError","room-sync.batch",e)
          message="Stoppet etter "+progress+" av "+total+": "+
            (e.message?:e.javaClass.simpleName).take(140)
          RoomSyncLog.write(context,"BATCH_FEIL",message.take(180))
          onRefresh()
        } finally {busy=false}
      }
    }) {Text("Bekreft og utfør")}},
    dismissButton={TextButton(onClick={confirm=false}) {Text("Avbryt")}}
  )
}

@Composable
private fun ReviewCount(value:String,label:String,modifier:Modifier) {
  Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
    Text(value,style=MaterialTheme.typography.headlineSmall,
      fontWeight=FontWeight.Bold)
    Text(label,style=MaterialTheme.typography.labelSmall)
  }
}
