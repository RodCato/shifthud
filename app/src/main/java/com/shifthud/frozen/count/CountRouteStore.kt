package com.shifthud.frozen.count

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID

private val Context.countRoutePreferences by preferencesDataStore(name="frozen_count_route")

/** Isolated from Frozen task, punch and payroll storage. Every action uses an optimistic revision. */
class CountRouteStore(private val preferences:DataStore<Preferences>,private val clock:Clock=Clock.systemUTC()) {
    constructor(context:Context):this(context.applicationContext.countRoutePreferences)
    private val key=stringPreferencesKey("count_route_v1")
    val data=preferences.data.map { decodeCountRoute(it[key]) }
    private suspend fun change(revision:Long,update:(CountRouteData)->CountRouteData) {
        preferences.edit { values->
            val old=decodeCountRoute(values[key])
            if(old.revision==revision)values[key]=encodeCountRoute(update(old).copy(revision=old.revision+1))
        }
    }
    suspend fun saveMap(revision:Long,map:List<RouteSegment>) {validateMap(map);change(revision) {it.copy(map=map)}}
    suspend fun target(revision:Long,target:Int) {
        require(target in 1..100) {"Choose a target from 1 to 100 products per door."}
        change(revision) {data->data.copy(target=target,cycles=data.cycles.map {if(it.finished==null)it.copy(target=target) else it})}
    }
    suspend fun start(revision:Long)=change(revision) {data->
        check(data.active==null) {"Finish the current cycle before starting another."}
        require(data.map.all {it.sections!=null || it.excluded || it.count==null}) {"Review department ownership before starting a route."}
        val doors=generateDoors(data.map);require(doors.isNotEmpty()) {"Configure at least one door first."}
        val now=clock.instant();val first=doors.first().id
        val cycle=CountCycle((data.cycles.maxOfOrNull {it.number} ?: 0)+1,now,target=data.target,map=data.map,doors=doors,current=first,
            sessions=listOf(CountSession(UUID.randomUUID().toString(),now,startDoor=first)))
        data.copy(cycles=data.cycles+cycle)
    }
    private suspend fun active(revision:Long,cycle:Int,update:(CountCycle)->CountCycle)=change(revision) {data->
        check(data.active?.number==cycle) {"This counting cycle is no longer active."}
        data.copy(cycles=data.cycles.map {if(it.number==cycle)update(it) else it})
    }
    suspend fun count(revision:Long,cycle:Int,door:String,undo:Boolean=false)=active(revision,cycle) {c->
        check(c.ownershipKnown) {"Legacy route ownership is unknown. Finish this cycle and configure Frozen sections."}
        check(!c.paused && c.current==door && c.currentDoor?.state==DoorState.OPEN)
        val current=requireNotNull(c.currentDoor)
        require(!undo || current.products>0)
        require(undo || current.products<10000) {"This door has reached the tracker limit."}
        val delta=if(undo)-1 else 1
        c.copy(doors=c.doors.map {if(it.id==door)it.copy(products=it.products+delta) else it},
            sessions=c.sessions.map {if(it.ended==null)it.copy(products=it.products+delta) else it})
    }
    suspend fun resolve(revision:Long,cycle:Int,door:String,state:DoorState,note:String="")=active(revision,cycle) {c->
        require(state!=DoorState.OPEN && note.length<=1000)
        check(c.ownershipKnown) {"Legacy route ownership is unknown. Finish this cycle and configure Frozen sections."}
        check(!c.paused && c.current==door && c.currentDoor?.state==DoorState.OPEN)
        c.copy(doors=c.doors.map {if(it.id==door)it.copy(state=state,note=note.trim()) else it},current=c.nextOpen(door)?.id)
    }
    suspend fun revisit(revision:Long,cycle:Int,door:String)=active(revision,cycle) {c->
        check(c.ownershipKnown) {"Legacy route ownership is unknown. Finish this cycle and configure Frozen sections."}
        check(!c.paused)
        val selected=c.doors.single {it.id==door};check(selected.state!=DoorState.SAMPLED)
        c.copy(doors=c.doors.map {if(it.id==door)it.copy(state=DoorState.OPEN) else it},current=door)
    }
    suspend fun pause(revision:Long,cycle:Int)=active(revision,cycle) {c->
        check(!c.paused);c.copy(paused=true,sessions=c.sessions.map {if(it.ended==null)it.copy(ended=clock.instant(),endDoor=c.current) else it})
    }
    suspend fun resume(revision:Long,cycle:Int)=active(revision,cycle) {c->
        check(c.paused);c.copy(paused=false,sessions=c.sessions+CountSession(UUID.randomUUID().toString(),clock.instant(),startDoor=c.current))
    }
    suspend fun finish(revision:Long,cycle:Int)=active(revision,cycle) {c->
        val now=clock.instant();c.copy(finished=now,paused=true,sessions=c.sessions.map {if(it.ended==null)it.copy(ended=now,endDoor=c.current) else it})
    }
    suspend fun submission(revision:Long,cycle:Int,session:String,status:Submission,note:String)=change(revision) {data->
        require(note.length<=1000);require(data.cycles.any {it.number==cycle && it.sessions.any {s->s.id==session}})
        data.copy(cycles=data.cycles.map {if(it.number==cycle)it.copy(sessions=it.sessions.map {s->if(s.id==session)s.copy(submission=status,note=note.trim()) else s}) else it})
    }
}
