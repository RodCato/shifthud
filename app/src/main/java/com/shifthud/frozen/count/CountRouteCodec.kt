package com.shifthud.frozen.count

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private fun <T> JSONArray.read(map:(JSONObject)->T)=(0 until length()).map {map(getJSONObject(it))}
private fun <T> List<T>.json(map:(T)->JSONObject)=JSONArray().also {a->forEach {a.put(map(it))}}
private fun JSONObject.optional(name:String)=if(isNull(name))null else getString(name)
private fun segmentJson(s:RouteSegment)=JSONObject().put("id",s.id).put("aisle",s.aisle).put("side",s.side)
    .put("direction",s.direction?.name ?: JSONObject.NULL).put("count",s.count ?: JSONObject.NULL).put("excluded",s.excluded)
private fun readSegment(s:JSONObject)=RouteSegment(s.getString("id"),s.getInt("aisle"),s.getString("side"),s.optional("direction")?.let(WalkDirection::valueOf),if(s.isNull("count"))null else s.getInt("count"),s.getBoolean("excluded"))
internal fun encodeCountRoute(data:CountRouteData):String=JSONObject().put("version",1).put("revision",data.revision).put("target",data.target)
    .put("map",data.map.json(::segmentJson)).put("cycles",data.cycles.json {c->JSONObject()
        .put("number",c.number).put("started",c.started.toString()).put("finished",c.finished?.toString() ?: JSONObject.NULL)
        .put("target",c.target).put("map",c.map.json(::segmentJson)).put("current",c.current ?: JSONObject.NULL).put("paused",c.paused)
        .put("doors",c.doors.json {d->JSONObject().put("id",d.id).put("segment",d.segmentId).put("number",d.number).put("products",d.products).put("state",d.state.name).put("note",d.note)})
        .put("sessions",c.sessions.json {s->JSONObject().put("id",s.id).put("started",s.started.toString()).put("ended",s.ended?.toString() ?: JSONObject.NULL)
            .put("startDoor",s.startDoor ?: JSONObject.NULL).put("endDoor",s.endDoor ?: JSONObject.NULL).put("products",s.products).put("submission",s.submission.name).put("note",s.note)})
    }).toString()
internal fun decodeCountRoute(raw:String?):CountRouteData {
    if(raw==null)return CountRouteData()
    val root=JSONObject(raw);require(root.getInt("version")==1) {"Unsupported count route version; saved data was not replaced."}
    val data=CountRouteData(root.getLong("revision"),root.getJSONArray("map").read(::readSegment),root.getInt("target"),root.getJSONArray("cycles").read {c->
        CountCycle(c.getInt("number"),Instant.parse(c.getString("started")),c.optional("finished")?.let(Instant::parse),c.getInt("target"),
            c.getJSONArray("map").read(::readSegment),c.getJSONArray("doors").read {d->CountDoor(d.getString("id"),d.getString("segment"),d.getInt("number"),d.getInt("products"),DoorState.valueOf(d.getString("state")),d.getString("note"))},
            c.optional("current"),c.getBoolean("paused"),c.getJSONArray("sessions").read {s->CountSession(s.getString("id"),Instant.parse(s.getString("started")),s.optional("ended")?.let(Instant::parse),s.optional("startDoor"),s.optional("endDoor"),s.getInt("products"),Submission.valueOf(s.getString("submission")),s.getString("note"))})
    })
    validateMap(data.map);require(data.revision>=0 && data.target in 1..100)
    require(data.cycles.count {it.finished==null}<=1 && data.cycles.map {it.number}.distinct().size==data.cycles.size)
    data.cycles.forEach {c->
        validateMap(c.map);require(c.number>0 && c.target in 1..100)
        require(c.doors.map {Triple(it.id,it.segmentId,it.number)}==generateDoors(c.map).map {Triple(it.id,it.segmentId,it.number)})
        require(c.doors.isNotEmpty() && c.doors.all {it.products in 0..10000 && it.note.length<=1000})
        require(c.current==null || c.currentDoor?.state==DoorState.OPEN)
        require(c.sessions.isNotEmpty() && c.sessions.map {it.id}.distinct().size==c.sessions.size)
        require(c.sessions.count {it.ended==null}==if(c.paused || c.finished!=null)0 else 1)
        require(c.sessions.all {it.note.length<=1000 && it.id.isNotBlank()})
    }
    return data
}
