package no.klevenberg.homeyroomsync.roomsync

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.google.home.AvailableAgent
import com.google.home.CloudAccountLinkingWebBroker
import com.google.home.Structure
import com.google.home.annotation.HomeExperimentalApi
import com.google.home.initiateCloudAccountLink
import com.google.home.listAvailableAgents
import kotlinx.coroutines.withTimeout

@OptIn(HomeExperimentalApi::class)
object HomeyCloudLink {
  private fun checkHome(ctx: Context, structure: Structure) {
    check(RoomSyncLog.structureId(ctx) == structure.id.id) { "Valgt hjem er endret. Åpne romsynk på nytt." }
  }
  suspend fun find(ctx: Context, structure: Structure): AvailableAgent = withTimeout(60_000) {
    checkHome(ctx, structure)
    var token: String? = null
    val seen = mutableSetOf<String>()
    val matches = linkedMapOf<String, AvailableAgent>()
    do {
      val result = structure.listAvailableAgents(pageToken=token,pageSize=100)
      result.agents.filter { it.providerName.trim().equals("Homey",ignoreCase=true) }
        .forEach { matches[it.agentId]=it }
      token=result.nextPageToken?.takeIf { it.isNotBlank() }
      if(token != null) check(seen.add(token)) { "Google gjentok samme leverandørside." }
      check(seen.size <= 100) { "Leverandørlisten er for stor til å fullføre kontrollen." }
    } while(token != null)
    check(matches.size == 1) {
      if(matches.isEmpty()) "Google tilbyr ikke Homey i leverandørlisten for denne appen/kontoen. Ingen kobling er endret."
      else "Flere Homey-leverandører funnet. Ingen kobling er valgt automatisk."
    }
    matches.values.single().also {
      RoomSyncLog.write(ctx,"KOBLING","Homey funnet hos Google. Allerede koblet: ${it.isAlreadyLinked}. Hjem: ${structure.name}")
    }
  }
  suspend fun open(ctx: Context, structure: Structure, agent: AvailableAgent) {
    error("Read-only V2 diagnostic build: provider relinking disabled")
    checkHome(ctx,structure)
    check(agent.providerName.trim().equals("Homey",true)) { "Feil leverandør" }
    val url=withTimeout(30_000) { structure.initiateCloudAccountLink(agent) }
    val uri=Uri.parse(url)
    val base=Uri.parse(CloudAccountLinkingWebBroker.DEFAULT_LINK_BASE_URL)
    check(uri.scheme == "https" && uri.host == base.host && uri.path == base.path) { "Uventet koblingsadresse fra Google" }
    RoomSyncLog.write(ctx,"KOBLING","Åpner Googles Homey-kobling for ${structure.name}. Venter på Google-godkjenning; hjemtilordning er ikke bekreftet.")
    CustomTabsIntent.Builder().build().launchUrl(ctx,uri)
  }
}
