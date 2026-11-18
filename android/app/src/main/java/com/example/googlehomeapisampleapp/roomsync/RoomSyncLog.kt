package no.klevenberg.homeyroomsync.roomsync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object RoomSyncLog {
  private const val PREFS = "homey_room_sync_v2"
  private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
  // Explicit opt-in for this release: ignore older "enabled" preferences to prevent
  // unattended writes when upgrading from a diagnostic-only build.
  // New early-alpha review build: prevent unattended sync after accidental switch use.
  private const val ENABLED_KEY = "sync_enabled_v5"
  fun enabled(ctx: Context) = prefs(ctx).getBoolean(ENABLED_KEY,false)
  fun setEnabled(ctx: Context, enabled: Boolean) {
    prefs(ctx).edit().putBoolean(ENABLED_KEY,enabled).apply()
  }
  fun googleDecision(ctx: Context, id: String): String {
    val json = try { JSONObject(prefs(ctx).getString("review_decisions_v1","{}")) }
      catch (_: Exception) { JSONObject() }
    return json.optString(id,"")
  }
  @Synchronized fun setGoogleDecision(ctx: Context, id: String, decision: String) {
    require(decision in setOf("","KEEP","DROP","MANUAL"))
    val json = try { JSONObject(prefs(ctx).getString("review_decisions_v1","{}")) }
      catch (_: Exception) { JSONObject() }
    if (decision.isBlank()) json.remove(id) else json.put(id,decision)
    prefs(ctx).edit().putString("review_decisions_v1",json.toString()).apply()
    setGoogleIgnored(ctx,id,decision.isNotEmpty())
  }
  fun ignoredGoogleIds(ctx: Context): Set<String> {
    val arr = try { JSONArray(prefs(ctx).getString("ignored_google_device_ids","[]")) }
      catch (_: Exception) { JSONArray() }
    return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
  }
  @Synchronized fun setGoogleIgnored(ctx: Context, id: String, ignored: Boolean) {
    if (id.isBlank()) return
    val ids = ignoredGoogleIds(ctx).toMutableSet()
    if (ignored) ids.add(id) else ids.remove(id)
    val arr = JSONArray()
    ids.take(300).forEach { arr.put(it) }
    prefs(ctx).edit().putString("ignored_google_device_ids",arr.toString()).apply()
  }
  fun ignoredMissingNames(ctx: Context): Set<String> {
    val arr = try { JSONArray(prefs(ctx).getString("ignored_missing_names","[]")) }
      catch (_: Exception) { JSONArray() }
    return (0 until arr.length()).map { arr.optString(it) }.toSet()
  }
  @Synchronized fun setMissingIgnored(ctx: Context, name: String, ignored: Boolean) {
    val n = RoomSyncData.normalize(name)
    if (n.isBlank()) return
    val ids = ignoredMissingNames(ctx).toMutableSet()
    if (ignored) ids.add(n) else ids.remove(n)
    val arr = JSONArray()
    ids.take(300).forEach { arr.put(it) }
    prefs(ctx).edit().putString("ignored_missing_names",arr.toString()).apply()
  }
  fun lastV2Read(ctx: Context) = prefs(ctx).getLong("last_v2_read",0L)
  fun lastV2Status(ctx: Context) =
    prefs(ctx).getString("last_v2_status","Ingen komplett V2-lesetest registrert") ?: ""
  fun recordV2Read(ctx: Context, summary: String) {
    prefs(ctx).edit().putLong("last_v2_read",System.currentTimeMillis())
      .putString("last_v2_status",summary.take(500)).apply()
  }
  fun lastCheck(ctx: Context) = prefs(ctx).getLong("last_check",0L)
  fun setLastCheck(ctx: Context) {prefs(ctx).edit().putLong("last_check",System.currentTimeMillis()).apply()}
  fun lastStatus(ctx: Context) = prefs(ctx).getString("status","Ikke synkronisert ennå") ?: ""
  fun setStatus(ctx: Context, value: String) { prefs(ctx).edit().putString("status",value).apply() }
  fun structureId(ctx: Context) = prefs(ctx).getString("structure_id","") ?: ""
  fun setStructureId(ctx: Context, id: String) { prefs(ctx).edit().putString("structure_id",id).apply() }
  fun defaultCloudHomeId(ctx: Context) = prefs(ctx).getString("cloud_home_default_id", "") ?: ""
  fun markCloudHomeDefault(ctx: Context, id: String) = prefs(ctx).edit().putString("cloud_home_default_id",id).apply()
  fun linkedRefreshHomeId(ctx: Context) = prefs(ctx).getString("linked_refresh_home_id", "") ?: ""
  fun markLinkedRefresh(ctx: Context, id: String) = prefs(ctx).edit().putString("linked_refresh_home_id",id).apply()
  fun lastMissing(ctx: Context): List<SkippedDevice> {
    val arr=JSONArray(prefs(ctx).getString("missing_from_home", "[]"))
    return (0 until arr.length()).map { i ->
      val obj=arr.getJSONObject(i);SkippedDevice(obj.optString("name"),obj.optString("reason"))
    }
  }
  fun setMissing(ctx: Context, missing: List<SkippedDevice>) {
    val arr=JSONArray()
    missing.take(150).forEach { arr.put(JSONObject().put("name",it.name).put("reason",it.reason)) }
    prefs(ctx).edit().putString("missing_from_home",arr.toString()).apply()
  }

  fun lastSkipped(ctx: Context): List<SkippedDevice> {
    val arr = JSONArray(prefs(ctx).getString("skipped","[]"))
    return (0 until arr.length()).map { i ->
      val obj = arr.getJSONObject(i)
      SkippedDevice(obj.optString("name"),obj.optString("reason"))
    }
  }
  fun setSkipped(ctx: Context, skipped: List<SkippedDevice>) {
    val arr=JSONArray()
    skipped.take(200).forEach { arr.put(JSONObject().put("name",it.name).put("reason",it.reason)) }
    prefs(ctx).edit().putString("skipped",arr.toString()).apply()
  }
  @Synchronized fun write(ctx: Context, kind: String, description: String) {
    val p=prefs(ctx)
    val list=try { JSONArray(p.getString("history","[]")) } catch (_: Exception) { JSONArray() }
    list.put(JSONObject().put("time",Instant.now().toString()).put("kind",kind).put("text",description))
    val compact=JSONArray()
    for (i in maxOf(0,list.length()-200) until list.length()) compact.put(list.getJSONObject(i))
    p.edit().putString("history",compact.toString()).apply()
  }
  @Synchronized fun clearHistory(ctx: Context) {
    prefs(ctx).edit().remove("history").apply()
  }
  fun history(ctx: Context): List<String> {
    val data=JSONArray(prefs(ctx).getString("history","[]"))
    return (data.length()-1 downTo 0).map { i ->
      val row=data.getJSONObject(i)
      val timestamp = try {
        java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
          .withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(row.optString("time")))
      } catch (_: Exception) { row.optString("time") }
      "$timestamp · ${row.optString("kind")}: ${row.optString("text")}" 
    }
  }
}
