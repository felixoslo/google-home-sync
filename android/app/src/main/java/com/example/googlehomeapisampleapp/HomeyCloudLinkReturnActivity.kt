package no.klevenberg.homeyroomsync

import android.app.Activity
import android.os.Bundle
import com.google.home.CloudAccountLinkingWebBroker
import no.klevenberg.homeyroomsync.roomsync.RoomSyncLog

class HomeyCloudLinkReturnActivity : Activity() {
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    val uri=intent?.data
    if(uri?.scheme == CloudAccountLinkingWebBroker.SDK_REDIRECT_SCHEME &&
       uri.host == CloudAccountLinkingWebBroker.SDK_REDIRECT_HOST) {
      // Callback is only informational: never treat a deep link as proof of device membership.
      val status=uri.getQueryParameter("status")
      RoomSyncLog.write(this,"KOBLING_RETUR",when(status) {
        "SUCCESS" -> "Google meldte fullført koblingsflyt. Faktisk hjemtilordning må bekreftes ved enhetskontroll."
        "FAILURE" -> "Google meldte at koblingsflyten ikke ble fullført."
        else -> "Tilbake fra Google-koblingen. Faktisk resultat kontrolleres i enhetslisten."
      })
    }
    startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(
      android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
    finish()
  }
}
