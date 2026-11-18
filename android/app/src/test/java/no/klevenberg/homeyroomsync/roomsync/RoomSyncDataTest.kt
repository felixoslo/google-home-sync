package no.klevenberg.homeyroomsync.roomsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSyncDataTest {
  private val homey = listOf(
    HomeyRoomEntry("Andreas Spot", "Andreas"),
    HomeyRoomEntry("Freddy", "Loft"),
    HomeyRoomEntry("Duplikat", "Bad"),
    HomeyRoomEntry("Duplikat", "Bad Lille")
  )
  private val rooms = listOf(GoogleRoomEntry("r1", "Andreas"))

  @Test fun exactUniqueNamesCanMove() {
    val plan = RoomSyncData.match(homey,listOf(GoogleDeviceEntry("d1","andreas  spot",null)),rooms)
    assertEquals(1,plan.moves.size)
    assertEquals("Andreas",plan.moves[0].roomName)
    assertTrue(plan.skipped.isEmpty())
  }
  @Test fun idempotentWhenAlreadyInRoom() {
    val plan = RoomSyncData.match(homey,listOf(GoogleDeviceEntry("d1","Andreas Spot","r1")),rooms)
    assertEquals(0,plan.moves.size)
    assertEquals(1,plan.correct)
  }
  @Test fun duplicatesNeverMove() {
    val plan = RoomSyncData.match(homey,listOf(GoogleDeviceEntry("d1","Duplikat",null)),rooms)
    assertEquals(0,plan.moves.size)
    assertEquals(1,plan.skipped.size)
  }
  @Test fun duplicateGoogleNamesNeverMove() {
    val plan = RoomSyncData.match(homey,listOf(
      GoogleDeviceEntry("d1","Andreas Spot",null),GoogleDeviceEntry("d2","andreas spot",null)),rooms)
    assertEquals(0,plan.moves.size)
    assertEquals(2,plan.skipped.size)
  }
  @Test fun missingRoomIsProposedOnlyForMatchingDevice() {
    val plan = RoomSyncData.match(homey,listOf(GoogleDeviceEntry("d1","Freddy",null)),rooms)
    assertEquals(listOf("Loft"),plan.newRooms)
  }
  @Test fun googleOnlyDeviceIsSkipped() {
    val plan = RoomSyncData.match(homey,listOf(GoogleDeviceEntry("d1","Chromecast stue",null)),rooms)
    assertEquals(0,plan.moves.size)
    assertTrue(plan.skipped[0].reason.contains("ikke i Homey"))
  }
  @Test fun ambiguousRoomsNeverMove() {
    val plan = RoomSyncData.match(homey,listOf(GoogleDeviceEntry("d1","Andreas Spot",null)),
      rooms+GoogleRoomEntry("r2","ANDREAS"))
    assertEquals(0,plan.moves.size)
  }

  @Test fun prefixAliasUsesHomeyZone() {
    val devices = listOf(HomeyRoomEntry("Loft Wallplug","Loft","socket"))
    val result = RoomSyncData.possibleHomeyMatches("Wallplug","Loft",devices)
    assertEquals(listOf("Loft Wallplug"),result.map { it.name })
    assertEquals("Loft",RoomSyncData.suggestedRoom("Wallplug","Loft",devices))
    // A suggested alias is never an automatic move.
    assertTrue(RoomSyncData.match(devices,listOf(GoogleDeviceEntry("g1","Wallplug",null)),
      listOf(GoogleRoomEntry("r1","Loft"))).moves.isEmpty())
  }
  @Test fun duplicateZonePrefixRequiresManualChoice() {
    val devices = listOf(HomeyRoomEntry("Loft Wallplug","Loft"),
      HomeyRoomEntry("Vaskerom Wallplug","Vaskerom"))
    assertEquals(2,RoomSyncData.possibleHomeyMatches("Wallplug",null,devices).size)
    assertEquals(null,RoomSyncData.suggestedRoom("Wallplug",null,devices))
  }
  @Test fun explicitNameBeatsPrefixAlias() {
    val devices = listOf(HomeyRoomEntry("Loft Wallplug","Loft"),
      HomeyRoomEntry("Wallplug","Stue"))
    assertEquals("Stue",RoomSyncData.suggestedRoom("Wallplug",null,devices))
  }
  @Test fun matchingNeverUsesPrefixAsAutoPermission() {
    val devices = listOf(HomeyRoomEntry("Loft Lamp","Loft"))
    assertEquals(0,RoomSyncData.match(devices,
      listOf(GoogleDeviceEntry("g","Lamp",null)),listOf()).moves.size)
  }

  @Test fun currentHomeyZoneOverridesStaleNamePrefix() {
    val devices = listOf(
      HomeyRoomEntry("Loft Wallplug","Kjøkken","socket"),
      HomeyRoomEntry("Loft Lampe","Loft","light"))
    val googleRooms = listOf(GoogleRoomEntry("r1","Loft"),GoogleRoomEntry("r2","Kjøkken"))
    assertEquals("Kjøkken",RoomSyncData.suggestedRoom(
      "Wallplug","Loft",devices,googleRooms))
    assertEquals(0,RoomSyncData.match(devices,
      listOf(GoogleDeviceEntry("g1","Wallplug","r1")),googleRooms).moves.size)
  }
  @Test fun noRoomIsNeverInferredFromPrefix() {
    val devices = listOf(HomeyRoomEntry("Loft Wallplug",null,"socket"),
      HomeyRoomEntry("Loft Lampe","Loft","light"))
    val rooms = listOf(GoogleRoomEntry("r1","Loft"))
    assertEquals(null,RoomSyncData.suggestedRoom("Wallplug","Loft",devices,rooms))
  }
  @Test fun ambiguousAliasesInSameZoneStillRequireUser() {
    val devices = listOf(HomeyRoomEntry("Loft Wallplug","Loft"),
      HomeyRoomEntry("Kjøkken Wallplug","Loft"))
    val rooms = listOf(GoogleRoomEntry("r1","Loft"),GoogleRoomEntry("r2","Kjøkken"))
    assertEquals(null,RoomSyncData.suggestedRoom("Wallplug","Loft",devices,rooms))
  }

  @Test fun uniquePrefixIsNotReportedAsTotallyMissing() {
    val homey = listOf(HomeyRoomEntry("Loft Wallplug","Kjøkken","socket"),
      HomeyRoomEntry("Loft lampe","Loft","light"))
    val google = listOf(GoogleDeviceEntry("g1","Wallplug","r1"))
    val rooms = listOf(GoogleRoomEntry("r1","Loft"),GoogleRoomEntry("r2","Kjøkken"))
    assertTrue(RoomSyncData.possibleGoogleNameMatch(homey[0],homey,google,rooms))
    assertTrue(!RoomSyncData.possibleGoogleNameMatch(homey[1],homey,google,rooms))
  }

  @Test fun indexedReviewResolvesMissingAndAliasesWithoutMoving() {
    val homes=listOf(HomeyRoomEntry("Loft Wallplug","Kjøkken","socket"),
      HomeyRoomEntry("Kjøkken Lys","Kjøkken","light"),
      HomeyRoomEntry("Gjest Lampe","Gjest","light"))
    val rooms=listOf(GoogleRoomEntry("r1","Loft"),
      GoogleRoomEntry("r2","Kjøkken"),GoogleRoomEntry("r3","Gjest"))
    val googles=listOf(GoogleDeviceEntry("g1","Wallplug","r1"),
      GoogleDeviceEntry("g2","Kjøkken Lys","r2"))
    val result=RoomSyncData.analyzeReview(homes,googles,rooms)
    assertEquals(1,result.missingHomey.size)
    assertEquals("Gjest Lampe",result.missingHomey.single().name)
    assertEquals("Loft Wallplug",result.matchesByGoogleId["g1"]?.single()?.name)
    assertEquals(0,RoomSyncData.match(homes,googles,rooms).moves.size)
  }
  @Test fun indexedReviewWithLargeInventoryFinishesQuickly() {
    val homes=(1..200).map {i ->
      HomeyRoomEntry("Loft kontakt $i","Loft","socket")
    }
    val rooms=listOf(GoogleRoomEntry("r1","Loft"))
    val googles=(1..60).map {i ->
      GoogleDeviceEntry("google$i","kontakt $i","r1")
    }
    val started=System.nanoTime()
    repeat(20) {
      val result=RoomSyncData.analyzeReview(homes,googles,rooms)
      assertEquals(140,result.missingHomey.size)
      assertEquals(60,result.matchesByGoogleId.size)
    }
    val milliseconds=(System.nanoTime()-started)/1_000_000
    // Generous regression guard: 20 full 200x60 reviews within 8 seconds.
    assertTrue("Indexed review too slow: $milliseconds ms",milliseconds<8000)
  }

  @Test fun bulkReviewDefaultsToHomeyRoomWithoutMutating() {
    val hs=listOf(HomeyRoomEntry("Loft Wallplug","Kjøkken","socket"),
      HomeyRoomEntry("Andre","Stue","light"))
    val rooms=listOf(GoogleRoomEntry("r1","Loft"),GoogleRoomEntry("r2","Kjøkken"))
    val gg=listOf(GoogleDeviceEntry("g1","Wallplug","r1"),
      GoogleDeviceEntry("g2","Ukjent","r1"))
    val a=RoomSyncData.analyzeReview(hs,gg,rooms)
    assertEquals("ROOM:r2",a.batchRows.single {it.googleId=="g1"}.defaultChoice)
    assertEquals("KEEP",a.batchRows.single {it.googleId=="g2"}.defaultChoice)
  }
  @Test fun bulkReviewMissingRoomNeedsExplicitCreateApproval() {
    val a=RoomSyncData.analyzeReview(
      listOf(HomeyRoomEntry("Bod lys","Bod","light")),
      listOf(GoogleDeviceEntry("g","Bod lys",null)),
      listOf(GoogleRoomEntry("r1","Stue")))
    assertEquals("CREATE:Bod",a.batchRows.single().defaultChoice)
  }
  @Test fun bulkReviewAmbiguousNameNeverDefaultsToMove() {
    val a=RoomSyncData.analyzeReview(
      listOf(HomeyRoomEntry("Bad","Bad","light")),
      listOf(GoogleDeviceEntry("g1","Bad","x"),GoogleDeviceEntry("g2","Bad","y")),
      listOf(GoogleRoomEntry("x","Stue"),GoogleRoomEntry("y","Bad")))
    assertTrue(a.batchRows.all {it.defaultChoice=="KEEP"})
  }

  @Test fun publicProxyRequiresTrustedHttpsAndRejectsRedirectLikeOrigins() {
    assertTrue(RoomSyncData.publicHttpsEndpointIsSafe(
      "https://sync.example.org/api/homey-room-sync/v1/inventory"))
    assertTrue(RoomSyncData.publicHttpsEndpointIsSafe(
      "https://example.org/api/homey-room-sync/v1/diagnostics"))
    assertTrue(!RoomSyncData.publicHttpsEndpointIsSafe(
      "http://example.org/api/homey-room-sync/v1/inventory"))
    assertTrue(!RoomSyncData.publicHttpsEndpointIsSafe(
      "https://example.org:8443/api/homey-room-sync/v1/inventory"))
    assertTrue(!RoomSyncData.publicHttpsEndpointIsSafe(
      "https://user:pass@example.org/api/homey-room-sync/v1/inventory"))
    assertTrue(!RoomSyncData.publicHttpsEndpointIsSafe(
      "https://example.org/api/homey-room-sync/v1/inventory#unsafe"))
  }
}
