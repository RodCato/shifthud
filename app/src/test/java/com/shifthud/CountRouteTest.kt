package com.shifthud

import android.app.Application
import androidx.datastore.preferences.core.*
import com.shifthud.frozen.count.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class CountRouteTest {
    private val file=Files.createTempDirectory("count-route").resolve("route.preferences_pb").toFile()
    private var job=SupervisorJob()
    private val clock=Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"),ZoneOffset.UTC)
    private fun prefs()=PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file})
    private var preferences=prefs();private var store=CountRouteStore(preferences,clock)
    @After fun cleanup()=runBlocking {job.cancelAndJoin();file.parentFile!!.deleteRecursively();Unit}
    private suspend fun data()=store.data.first()
    private fun configured(vararg counts:Int)=initialDoorMap().mapIndexed {i,s->s.copy(sections=listOf(PhysicalSection("test",department=SectionDepartment.FROZEN,kind=SectionKind.UPRIGHT,count=counts.getOrNull(i) ?: 1)),direction=s.direction ?: WalkDirection.FRONT_BACK)}
    private suspend fun start(map:List<RouteSegment> = configured(2,1,3,1,1,2)) {store.saveMap(data().revision,map);store.start(data().revision)}
    private suspend fun add(undo:Boolean=false) {val d=data();store.count(d.revision,d.active!!.number,d.active!!.current!!,undo)}
    private suspend fun resolve(state:DoorState,note:String="") {val d=data();store.resolve(d.revision,d.active!!.number,d.active!!.current!!,state,note)}
    @Test fun emptyMapHasNoGuessedDoorsAndFiveProductDefault()=runBlocking {
        val d=data();assertEquals(5,d.target);assertTrue(d.map.all {it.count==null && !it.excluded});assertTrue(generateDoors(d.map).isEmpty())
        assertNull(d.active);assertTrue(runCatching {store.start(d.revision)}.isFailure);assertEquals(d,data())
    }
    @Test fun partialMapCreatesOnlyConfiguredLocationsAndCannotClaimCoverage()=runBlocking {
        start(initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=null,sections=listOf(PhysicalSection("test",department=SectionDepartment.FROZEN,kind=SectionKind.UPRIGHT,count=1))) else s})
        val c=data().active!!;assertEquals(listOf("A10-R-01"),c.doors.map {it.id});assertFalse(c.mapComplete)
        resolve(DoorState.SAMPLED);assertFalse(data().active!!.covered);assertNull(data().active!!.current)
    }
    @Test fun serpentineRouteHasIndependentCountsAndExplicitAisle12Directions() {
        val map=configured(2,1,3,2,2,1).map {if(it.id=="A12-R")it.copy(direction=WalkDirection.BACK_FRONT) else it}
        assertEquals(listOf("A10-R-01","A10-R-02","A10-L-01","A11-R-01","A11-R-02","A11-R-03","A11-L-01","A11-L-02","A12-R-01","A12-R-02","A12-L-01"),generateDoors(map).map {it.id})
        assertEquals(listOf(WalkDirection.FRONT_BACK,WalkDirection.BACK_FRONT,WalkDirection.FRONT_BACK,WalkDirection.BACK_FRONT),map.take(4).map {it.direction})
        assertEquals(WalkDirection.BACK_FRONT,map[4].direction)
        assertTrue(mapChanges(configured(2,1,3,2,2,1),map).single().contains("Back → Front"))
    }
    @Test fun aisle12CanStartOnEitherSideWithoutChangingConfirmedAisles() {
        val map=configured(1,1,1,1,2,1)
        val reversed=map.take(4)+map.takeLast(2).reversed()
        assertEquals(listOf("A12-L-01","A12-R-01","A12-R-02"),generateDoors(reversed).takeLast(3).map {it.id})
        assertTrue(mapChanges(map,reversed).any {it.contains("walking order")})
    }
    @Test fun unknownAisle12DirectionAndInvalidCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {generateDoors(initialDoorMap().map {if(it.id=="A12-R")it.copy(count=null,sections=listOf(PhysicalSection("test",department=SectionDepartment.FROZEN,kind=SectionKind.UPRIGHT,count=3))) else it})}
        listOf(0,-1,201).forEach {n->assertThrows(IllegalArgumentException::class.java) {generateDoors(configured(n))}}
        assertThrows(IllegalArgumentException::class.java) {generateDoors(configured().reversed())}
    }
    @Test fun explicitExclusionsResolveMapWithoutInventingDoors()=runBlocking {
        start(initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=null,sections=listOf(PhysicalSection("test",department=SectionDepartment.FROZEN,kind=SectionKind.UPRIGHT,count=1))) else s.copy(excluded=true)})
        resolve(DoorState.SAMPLED);assertTrue(data().active!!.covered);assertEquals(1,data().active!!.doors.size)
    }
    @Test fun targetCanChangeBeforeAndDuringCycleWithoutCompletingDoors()=runBlocking {
        store.target(0,3);start();repeat(5) {add()}
        assertEquals(5,data().active!!.currentDoor!!.products);assertEquals(DoorState.OPEN,data().active!!.currentDoor!!.state)
        store.target(data().revision,8);assertEquals(8,data().active!!.target);assertEquals(8,data().target)
        add(true);assertEquals(4,data().active!!.currentDoor!!.products)
    }
    @Test fun completeBelowTargetAdvancesOnlyAfterExplicitResolution()=runBlocking {
        start();add();add();assertEquals("A10-R-01",data().active!!.current)
        resolve(DoorState.SAMPLED);val c=data().active!!
        assertEquals("A10-R-02",c.current);assertEquals(1,c.sampled);assertEquals(2,c.products)
    }
    @Test fun pauseMidDoorPersistsExactPositionCountsTargetDateAndSessions()=runBlocking {
        start();repeat(3) {add()};val before=data();val c=before.active!!
        store.pause(before.revision,c.number)
        assertTrue(runCatching {add()}.isFailure)
        job.cancelAndJoin();job=SupervisorJob();preferences=prefs();store=CountRouteStore(preferences,clock)
        var saved=data();assertTrue(saved.active!!.paused);assertEquals(c.current,saved.active!!.current);assertEquals(3,saved.active!!.currentDoor!!.products)
        assertEquals(5,saved.active!!.target);assertEquals(clock.instant(),saved.active!!.sessions.single().started)
        store.resume(saved.revision,c.number);saved=data();assertEquals(c.current,saved.active!!.current);assertEquals(2,saved.active!!.sessions.size)
        add();assertEquals(4,data().active!!.currentDoor!!.products)
    }
    @Test fun skipRetainsCountAndNoteAndCanBeRevisited()=runBlocking {
        start();add();resolve(DoorState.SKIPPED,"Door blocked")
        var c=data().active!!;assertEquals(0,c.sampled);assertEquals(1,c.skipped);assertEquals("Door blocked",c.doors.first().note)
        store.revisit(data().revision,c.number,c.doors.first().id);c=data().active!!
        assertEquals("A10-R-01",c.current);assertEquals(1,c.currentDoor!!.products);assertEquals(0,c.skipped)
        resolve(DoorState.SAMPLED);assertEquals(1,data().active!!.sampled)
    }
    @Test fun finishIsExplicitAndHistoryRetainsSkippedAndPartialDoors()=runBlocking {
        start();add();val first=data().active!!.current!!
        store.revisit(data().revision,1,"A10-R-02");resolve(DoorState.SKIPPED,"Try later")
        store.finish(data().revision,1);val old=data().cycles.single()
        assertNull(data().active);assertFalse(old.covered);assertEquals(1,old.partial);assertEquals(1,old.skipped)
        assertEquals(1,old.doors.single {it.id==first}.products);assertEquals(clock.instant(),old.finished)
        store.start(data().revision);assertEquals(old,data().cycles.first());assertEquals(2,data().active!!.number);assertEquals(0,data().active!!.products)
    }
    @Test fun finishingLastDoorDoesNotAutomaticallyFinishOrRestartCycle()=runBlocking {
        start(initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=null,sections=listOf(PhysicalSection("test",department=SectionDepartment.FROZEN,kind=SectionKind.UPRIGHT,count=1))) else s.copy(excluded=true)})
        resolve(DoorState.SAMPLED);assertNotNull(data().active);assertNull(data().active!!.current)
        store.finish(data().revision,1);assertNull(data().active);assertEquals(1,data().cycles.size)
    }
    @Test fun mapCorrectionsNeverRewriteActiveOrHistoricalSnapshots()=runBlocking {
        start();add();resolve(DoorState.SAMPLED);val snapshot=data().active!!
        val revised=configured(1,4,1,1,1,1)
        assertTrue(mapChanges(data().map,revised).any {it.startsWith("A10-R")})
        store.saveMap(data().revision,revised);assertEquals(snapshot,data().active)
        store.finish(data().revision,1);val history=data().cycles.first();store.start(data().revision)
        assertEquals(history,data().cycles.first());assertEquals(4,data().active!!.doors.count {it.segmentId=="A10-L"})
        assertEquals(1,data().active!!.doors.count {it.segmentId=="A10-R"})
    }
    @Test fun duplicateAndStaleActionsCannotDoubleCountOrAdvanceAnotherDoor()=runBlocking {
        start();val d=data();coroutineScope {repeat(2) {launch {store.count(d.revision,1,"A10-R-01")}}}
        assertEquals(1,data().active!!.products)
        val updated=data();store.resolve(updated.revision,1,"A10-R-01",DoorState.SAMPLED)
        store.resolve(updated.revision,1,"A10-R-01",DoorState.SAMPLED)
        assertEquals("A10-R-02",data().active!!.current);assertEquals(1,data().active!!.sampled)
        val before=data();store.start(d.revision);assertEquals(before,data())
    }
    @Test fun manualSubmissionIsIndependentOfSamplingAndEditableInHistory()=runBlocking {
        start();val c=data().active!!;val session=c.sessions.single()
        Submission.entries.forEach {s->store.submission(data().revision,1,session.id,s,"Personal note")}
        assertEquals(c.doors,data().active!!.doors);assertEquals(c.current,data().active!!.current)
        resolve(DoorState.SAMPLED);store.finish(data().revision,1)
        store.submission(data().revision,1,session.id,Submission.APPROVED,"Verified manually")
        assertEquals(Submission.APPROVED,data().cycles.first().sessions.first().submission)
        assertEquals(1,data().cycles.first().sampled)
    }
    @Test fun malformedPersistenceIsNeverResetOrOverwritten()=runBlocking {
        preferences.edit {it[stringPreferencesKey("count_route_v1")]="broken"}
        assertTrue(runCatching {data()}.isFailure)
        assertTrue(runCatching {store.saveMap(0,configured())}.isFailure)
        assertEquals("broken",preferences.data.first()[stringPreferencesKey("count_route_v1")])
    }
    @Test fun finishedHistoryRoundTripsWithMapSessionNotesAndProgress()=runBlocking {
        start();add();store.pause(data().revision,1);store.resume(data().revision,1);add(true);resolve(DoorState.SKIPPED,"Recheck")
        store.finish(data().revision,1);val before=data();assertEquals(before,decodeCountRoute(encodeCountRoute(before)))
        job.cancelAndJoin();job=SupervisorJob();preferences=prefs();store=CountRouteStore(preferences,clock);assertEquals(before,data())
    }
    private fun section(id:String,owner:SectionDepartment?,count:Int?,kind:SectionKind=SectionKind.UPRIGHT)=PhysicalSection(id,id,owner,kind,count)
    @Test fun mixedOwnershipAndCaseTypesGenerateOnlyFrozenLocationsInWalkingOrder() {
        val sections=listOf(section("meat",SectionDepartment.MEAT,7),section("frozen-doors",SectionDepartment.FROZEN,2),section("other",SectionDepartment.OTHER,3),section("frozen-cases",SectionDepartment.FROZEN,1,SectionKind.OPEN_CASE))
        val map=initialDoorMap().mapIndexed {i,s->if(i<2)s.copy(sections=if(i==0)sections else sections.reversed()) else s.copy(excluded=true)}
        val doors=generateDoors(map)
        assertEquals(listOf("A10-R-01","A10-R-02","A10-R-03","A10-L-01","A10-L-02","A10-L-03"),doors.map {it.id})
        assertEquals(listOf("frozen-doors","frozen-doors","frozen-cases","frozen-cases","frozen-doors","frozen-doors"),doors.map {it.sectionId})
        assertTrue(map.all {it.configured})
        val cycle=CountCycle(1,clock.instant(),target=5,map=map,doors=doors,current=doors.first().id,sessions=emptyList())
        assertTrue(cycle.location(doors[2]).contains("Case 3"))
        assertEquals(0,cycle.skipped)
    }
    @Test fun unknownOwnershipDoesNotCreateDoorsAndNonFrozenNeedsNoCount() {
        val map=initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(sections=listOf(section("unknown",null,10),section("meat",SectionDepartment.MEAT,null),section("frozen",SectionDepartment.FROZEN,2))) else s.copy(excluded=true)}
        assertEquals(2,generateDoors(map).size);assertFalse(map.first().configured)
        val resolved=map.map {it.copy(sections=it.sections?.filterNot {s->s.id=="unknown"})}
        assertTrue(resolved.all {it.configured});assertEquals(2,generateDoors(resolved).size)
    }
    @Test fun ownershipCorrectionsAndSectionReorderingPreserveActiveHistory()=runBlocking {
        val map=initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(sections=listOf(section("one",SectionDepartment.FROZEN,2),section("two",SectionDepartment.MEAT,4))) else s.copy(excluded=true)}
        start(map);add();val snapshot=data().active!!
        val corrected=map.map {it.copy(sections=it.sections?.reversed()?.map {s->s.copy(department=if(s.id=="one")SectionDepartment.MEAT else SectionDepartment.FROZEN)})}
        store.saveMap(data().revision,corrected);assertEquals(snapshot,data().active)
        store.finish(data().revision,1);val history=data().cycles.first();store.start(data().revision)
        assertEquals(history,data().cycles.first());assertEquals(4,data().active!!.doors.size)
        assertTrue(data().active!!.doors.all {it.sectionId==null})
        assertTrue(data().previousMaps.contains(corrected))
        assertEquals(data(),decodeCountRoute(encodeCountRoute(data())))
    }
    @Test fun versionOneTotalsRequireOwnershipReviewButKeepCycleCounts()=runBlocking {
        val oldMap=initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=2) else s.copy(excluded=true)}
        val doors=generateDoors(oldMap).mapIndexed {i,d->if(i==0)d.copy(products=3) else d}
        val old=CountRouteData(map=oldMap,cycles=listOf(CountCycle(1,clock.instant(),target=5,map=oldMap,doors=doors,current=doors.first().id,sessions=listOf(CountSession("old",clock.instant(),startDoor=doors.first().id,products=3)))))
        val json=org.json.JSONObject(encodeCountRoute(old)).put("version",1)
        val decoded=decodeCountRoute(json.toString())
        assertEquals(old.cycles,decoded.cycles);assertEquals(3,decoded.active!!.products)
        assertEquals(oldMap,decoded.map);assertTrue(simplifyDoorMap(decoded.map).warnings.isNotEmpty())
        assertTrue(generateDoors(simplifyDoorMap(decoded.map).map).isEmpty())
        assertFalse(decoded.active!!.ownershipKnown);assertFalse(decoded.active!!.covered)
        preferences.edit {it[stringPreferencesKey("count_route_v1")]=json.toString()}
        assertTrue(runCatching {store.count(0,1,"A10-R-01")}.isFailure)
        store.finish(data().revision,1);assertEquals(3,data().cycles.first().products)
        assertTrue(runCatching {store.start(data().revision)}.isFailure)
    }

    @Test fun fiveCountsDistinguishUnknownFromZeroAndPreserveFixedOrder()=runBlocking {
        assertEquals(5,data().map.size);assertTrue(data().map.all {it.count==null})
        val map=simpleDoorMap().mapIndexed {i,s->s.copy(count=listOf(2,0,1,null,3)[i])}
        store.saveMap(0,map);store.start(data().revision)
        assertEquals(listOf("A10-R-01","A10-R-02","A11-R-01","A12-F-01","A12-F-02","A12-F-03"),data().active!!.doors.map {it.id})
        assertTrue(data().map[1].configured);assertFalse(data().map[3].configured)
        assertEquals(listOf(WalkDirection.FRONT_BACK,WalkDirection.BACK_FRONT,WalkDirection.FRONT_BACK,WalkDirection.BACK_FRONT,WalkDirection.FINAL_SECTION),data().map.map {it.direction})
        repeat(3) {resolve(DoorState.SAMPLED)}
        assertEquals("A12-F-01",data().active!!.current)
        add();store.pause(data().revision,1);val paused=data()
        job.cancelAndJoin();job=SupervisorJob();preferences=prefs();store=CountRouteStore(preferences,clock)
        assertEquals(paused,data());store.resume(data().revision,1)
        assertEquals("A12-F-01",data().active!!.current);assertEquals(1,data().active!!.currentDoor!!.products)
    }
    @Test fun safeLegacyConversionArchivesMapAndPreservesExactActiveSnapshot() {
        val map=configured(2,3,4,5,6,7)
        val doors=generateDoors(map).mapIndexed {i,d->if(i==2)d.copy(products=3) else d}
        val cycle=CountCycle(1,clock.instant(),target=5,map=map,doors=doors,current=doors[2].id,paused=true,sessions=listOf(CountSession("saved",clock.instant(),ended=clock.instant(),startDoor=doors[0].id,endDoor=doors[2].id,products=3)))
        val decoded=decodeCountRoute(encodeCountRoute(CountRouteData(map=map,cycles=listOf(cycle))))
        assertEquals(listOf(2,3,4,5,13),decoded.map.map {it.count});assertEquals(listOf(map),decoded.previousMaps)
        assertEquals(cycle,decoded.active);assertEquals(decoded,decodeCountRoute(encodeCountRoute(decoded)))
    }
    @Test fun casesUnknownOwnershipAndPartialAisle12RequireExplicitReview() {
        val old=initialDoorMap().mapIndexed {i,s->when(i) {
            0->s.copy(sections=listOf(section("case",SectionDepartment.FROZEN,3,SectionKind.OPEN_CASE)))
            1->s.copy(sections=listOf(section("unknown",null,4)))
            4->s.copy(direction=WalkDirection.FRONT_BACK,sections=listOf(section("known",SectionDepartment.FROZEN,2)))
            else->s
        }}
        val decoded=decodeCountRoute(encodeCountRoute(CountRouteData(map=old)))
        assertEquals(old,decoded.map);assertTrue(decoded.previousMaps.isEmpty())
        val conversion=simplifyDoorMap(old);assertEquals(3,conversion.warnings.size)
        assertNull(conversion.map[0].count);assertNull(conversion.map[1].count);assertNull(conversion.map[4].count)
    }
    @Test fun confirmingLegacyRouteDoesNotResetPositionOrRecordedProgress()=runBlocking {
        val old=initialDoorMap().mapIndexed {i,s->if(i==0)s.copy(count=2) else s.copy(excluded=true)}
        val doors=generateDoors(old).mapIndexed {i,d->if(i==1)d.copy(products=3) else d.copy(state=DoorState.SAMPLED,products=2)}
        val cycle=CountCycle(1,clock.instant(),target=5,map=old,doors=doors,current=doors[1].id,sessions=listOf(CountSession("session",clock.instant(),startDoor=doors[0].id,products=5)))
        preferences.edit {it[stringPreferencesKey("count_route_v1")]=encodeCountRoute(CountRouteData(map=old,cycles=listOf(cycle)))}
        store.confirmExistingRoute(0,1);assertEquals(cycle.copy(ownershipConfirmed=true),data().active)
        add();assertEquals(4,data().active!!.currentDoor!!.products)
        store.saveMap(data().revision,simpleDoorMap().mapIndexed {i,s->s.copy(count=if(i==0)2 else 0)})
        assertEquals(4,data().active!!.currentDoor!!.products);assertEquals(1,data().active!!.sampled)
        assertEquals(old,data().previousMaps.single())
    }
    @Test fun zeroDoorMapIsResolvedButCannotStartAndInvalidCountsAreRejected()=runBlocking {
        val zeros=simpleDoorMap().map {it.copy(count=0)}
        store.saveMap(0,zeros);assertTrue(data().map.all {it.configured});assertTrue(generateDoors(data().map).isEmpty())
        assertTrue(runCatching {store.start(data().revision)}.isFailure)
        listOf(-1,40001).forEach {n->assertThrows(IllegalArgumentException::class.java) {validateMap(zeros.mapIndexed {i,s->if(i==0)s.copy(count=n) else s})}}
    }

}
