package no.klevenberg.homeyroomsync.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

@Composable
fun HomeySyncLandingView(
  homeName:String?,
  readTime:String,
  onOpenSync:()->Unit,
  onOpenDevices:()->Unit,
  onPermissions:()->Unit
) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    .padding(horizontal=20.dp,vertical=18.dp),
    verticalArrangement=Arrangement.spacedBy(18.dp)) {
    Text("HOMEY  /  GOOGLE HOME",
      style=MaterialTheme.typography.labelLarge,
      color=MaterialTheme.colorScheme.primary)
    Text("Et hjem. Riktige rom.",
      style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
    Text("Hold romplasseringene i Google Home i samsvar med Homey – med oversikt og full kontroll før noe flyttes.",
      style=MaterialTheme.typography.bodyLarge)
    Card(shape=RoundedCornerShape(22.dp),
      colors=CardDefaults.cardColors(
        containerColor=MaterialTheme.colorScheme.primaryContainer)) {
      Column(Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Romoversikt",style=MaterialTheme.typography.titleLarge,
          fontWeight=FontWeight.Bold)
        Text(homeName ?: "Klargjør Google Home …",
          style=MaterialTheme.typography.titleMedium)
        Text("Les inn enheter, se Homeys romforslag og velg hva som skal flyttes.",
          style=MaterialTheme.typography.bodyMedium)
        Button(modifier=Modifier.fillMaxWidth(),enabled=homeName!=null,
          onClick=onOpenSync) {Text("Åpne Room Sync")}
      }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
      Card(modifier=Modifier.weight(1f),shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp),
          verticalArrangement=Arrangement.spacedBy(6.dp)) {
          Text("STATUS",style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.primary)
          Text("Trygg lesemodus",style=MaterialTheme.typography.titleSmall,
            fontWeight=FontWeight.SemiBold)
          Text("Ingen flytting uten bekreftelse.",
            style=MaterialTheme.typography.bodySmall)
        }
      }
      Card(modifier=Modifier.weight(1f),shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp),
          verticalArrangement=Arrangement.spacedBy(6.dp)) {
          Text("SIST SJEKKET",style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.primary)
          Text(readTime,style=MaterialTheme.typography.titleSmall)
          Text("Dato fra siste vellykkede V2-test.",
            style=MaterialTheme.typography.bodySmall)
        }
      }
    }
    Card(shape=RoundedCornerShape(18.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Andre verktøy",style=MaterialTheme.typography.titleMedium)
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick=onOpenDevices) {
          Text("Vis Google-enheter og andre funksjoner")
        }
        TextButton(onClick=onPermissions) {Text("Google Home-tilgang")}
      }
    }
    Text("EARLY ALPHA · Automatisk romsynk er deaktivert. "+
      "Feilrapporter lagres sikkert på LinuxServer.",
      style=MaterialTheme.typography.labelSmall,
      color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
