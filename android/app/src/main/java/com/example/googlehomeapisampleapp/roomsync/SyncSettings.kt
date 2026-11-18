package no.klevenberg.homeyroomsync.roomsync

/** Public template only. Use values from your own deployment. */
object SyncSettings {
  const val HOME_NAME = "YOUR_GOOGLE_HOME_NAME"
  const val ENDPOINT =
    "https://YOUR_LINUX_SERVER:8443/api/homey-room-sync/v1/inventory"
  const val CERTIFICATE_SHA256 = "REPLACE_WITH_CERT_SHA256"
}
