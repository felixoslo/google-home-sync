package no.klevenberg.homeyroomsync.roomsync

import android.content.Context
import com.google.home.HomeException
import no.klevenberg.homeyroomsync.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object CloudLinkDiagnostics {
  private fun safe(value: String?): String = (value ?: "").take(1800)
    .replace(Regex("https?://[^\\s]+"),"[URL utelatt]")
    .replace(Regex("GOCSPX-[A-Za-z0-9_-]+"),"[hemmelighet utelatt]")
  suspend fun record(ctx: Context, operation: String, exception: Exception): String {
    val e=exception as? HomeException
    val errors=JSONArray()
    val detail=mutableListOf<String>()
    val all=if(e==null) emptyList() else listOf(e.error)+e.subErrors.values.toList()+listOfNotNull(e.getInnerHomeException()?.error)
    all.distinctBy { "${it.code}:${it.reason}:${it.message}" }.take(25).forEach { error ->
      val reason=safe(error.reason);val message=safe(error.message)
      errors.put(JSONObject().put("code",error.code).put("reason",reason).put("message",message).put("domain",error.domain.toString()))
      detail.add("kode=${error.code}, årsak=$reason, melding=$message")
    }
    val summary=if(detail.isEmpty()) "${exception.javaClass.simpleName}: ${safe(exception.message)}" else detail.joinToString(" | ")
    RoomSyncLog.write(ctx,"KOBLING_DIAG",summary)
    val report=JSONObject().put("schemaVersion",1).put("appVersion",BuildConfig.VERSION_NAME)
      .put("operation",operation).put("timestamp",Instant.now().toString())
      .put("errorCode",e?.error?.code ?: -1).put("errorMessage",safe(exception.message)).put("subErrors",errors)
    val delivered=try { RoomSyncData.reportDiagnostics(report) } catch (_: Exception) { false }
    RoomSyncLog.write(ctx,"DIAGNOSE",if(delivered) "SDK-diagnose levert privat til LinuxServer." else "SDK-diagnose lagret i appen. LinuxServer kunne ikke nås; kontroller lokalnett/VPN.")
    return "Google-kobling feilet. ${if(delivered) "Detaljdiagnose sendt til LinuxServer." else "Detaljdiagnose finnes i loggen."}"
  }
}
