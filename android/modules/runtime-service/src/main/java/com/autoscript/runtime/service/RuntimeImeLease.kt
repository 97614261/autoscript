package com.autoscript.runtime.service

/** Main thread owns focus/commit. A host worker may capture only the volatile opaque ticket. */
internal class RuntimeImeLease<T:Any> {
    private var owner:T?=null
    @Volatile private var identity:Any?=null
    fun start(next:T) {owner=next;identity=Any()}
    fun finish(previous:T) {if(owner===previous) {owner=null;identity=null}}
    fun ticket():Any?=identity
    fun commit(expected:Any?,text:String,send:(T,String)->Boolean):Boolean {
        require(text.toByteArray(Charsets.UTF_8).size<=4096 && '\u0000' !in text)
        check(expected!=null && expected===identity) {"IME_CONNECTION_CHANGED"}
        return send(requireNotNull(owner),text)
    }
}
