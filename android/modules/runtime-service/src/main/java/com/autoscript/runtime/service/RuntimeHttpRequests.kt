package com.autoscript.runtime.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class RuntimeHttpFailure(val code:String):RuntimeException()

internal data class RuntimeHttpSpec(val method:String,val url:URI,val headers:Map<String,String>,val body:ByteArray,
    val timeoutMs:Int,val maxResponseBytes:Int) {
    companion object {
        private fun invalid():Nothing=throw RuntimeHttpFailure("HTTP_REQUEST_INVALID")
        fun url(value:String):URI {
            if(value.toByteArray(Charsets.UTF_8).size !in 1..2048 || value.any {it.isISOControl() || it.isWhitespace()}) invalid()
            val uri=try {URI(value)} catch(_:Exception) {invalid()}
            if(uri.scheme !in setOf("http","https") || uri.host.isNullOrEmpty() || uri.rawUserInfo!=null || uri.port !in -1..65535 || uri.port==0) invalid()
            return uri
        }
        fun parse(source:String):RuntimeHttpSpec {
            if(source.toByteArray(Charsets.UTF_8).size>32768) invalid()
            return try {
                val json=JsonParser.parseString(source).asJsonObject
                if(json.keySet()!=setOf("method","url","headersJson","body","timeoutMs","maxResponseBytes")) invalid()
                fun string(key:String,max:Int):String {
                    val value=json[key];if(!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid()
                    return value.asString.also {if(it.toByteArray(Charsets.UTF_8).size>max) invalid()}
                }
                fun integer(key:String,min:Int,max:Int):Int {
                    val value=json[key];if(!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) invalid()
                    return value.toString().toIntOrNull()?.takeIf {it in min..max} ?: invalid()
                }
                val method=string("method",8)
                if(method !in setOf("GET","POST","PUT","DELETE","HEAD","OPTIONS")) invalid()
                val uri=url(string("url",2048));if(uri.rawFragment!=null) invalid()
                val body=string("body",8192).toByteArray(Charsets.UTF_8)
                if(method in setOf("GET","HEAD") && body.isNotEmpty()) invalid()
                val headerObject=JsonParser.parseString(string("headersJson",4096)).asJsonObject
                if(headerObject.size()>32) invalid()
                val headers=linkedMapOf<String,String>()
                val names=mutableSetOf<String>()
                for((name,value) in headerObject.entrySet()) {
                    if(name.length !in 1..64 || name.any {it.code !in 33..126 || !(it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~")} || !names.add(name.lowercase(java.util.Locale.ROOT))) invalid()
                    val key=name.lowercase(java.util.Locale.ROOT)
                    if(key in setOf("host","connection","content-length","transfer-encoding","accept-encoding","expect","upgrade","te","trailer") || key.startsWith("proxy-")) invalid()
                    if(!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid()
                    val text=value.asString
                    if(text.toByteArray(Charsets.UTF_8).size>1024 || text.any {it.isISOControl()}) invalid()
                    headers[name]=text
                }
                RuntimeHttpSpec(method,uri,headers,body,integer("timeoutMs",100,30000),integer("maxResponseBytes",1,16384))
            } catch(error:RuntimeHttpFailure) {throw error} catch(_:Exception) {invalid()}
        }
    }
}

/** One active and one queued request; no cookies, redirect following, downloads or request logging. */
internal class RuntimeHttpRequests(
    private val encode:(ByteArray)->String=StrictBase64::encode,
    private val connect:(URI)->HttpURLConnection={it.toURL().openConnection() as HttpURLConnection},
    private val clock:()->Long=System::nanoTime,
) {
    private class Job {val cancelled=AtomicBoolean();val connection=AtomicReference<HttpURLConnection?>()}
    private val jobs=ConcurrentHashMap<Long,Job>()
    private val admission=Any()
    private val worker=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1))
    private val closer=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(2))
    fun submit(work:Long,source:String,valid:()->Boolean,complete:(Result<String>)->Unit) {
        val spec=runCatching {RuntimeHttpSpec.parse(source)}.getOrElse {complete(Result.failure(it));return}
        val job=Job()
        val start=clock();val deadline=start+spec.timeoutMs*1_000_000L
        synchronized(admission) {
            if(work<=0 || jobs.size>=2 || jobs.putIfAbsent(work,job)!=null) {complete(Result.failure(RuntimeHttpFailure("HTTP_BUSY")));return}
        }
        fun check() {
            if(job.cancelled.get() || !valid()) throw RuntimeHttpFailure("HTTP_CANCELLED")
            if(clock()-deadline>=0) throw RuntimeHttpFailure("HTTP_TIMEOUT")
        }
        fun remaining():Int {check();return ((deadline-clock())/1_000_000).coerceIn(1,spec.timeoutMs.toLong()).toInt()}
        try {worker.execute {
            val result=runCatching {
                check()
                if(java.net.CookieHandler.getDefault()!=null) throw RuntimeHttpFailure("HTTP_COOKIE_HANDLER_UNAVAILABLE")
                val connection=connect(spec.url)
                job.connection.set(connection)
                check()
                connection.instanceFollowRedirects=false
                connection.useCaches=false
                connection.connectTimeout=remaining();connection.readTimeout=remaining()
                connection.requestMethod=spec.method
                connection.setRequestProperty("Accept-Encoding","identity")
                spec.headers.forEach { (name,value)->connection.setRequestProperty(name,value) }
                if(spec.body.isNotEmpty()) {
                    connection.doOutput=true
                    if(spec.headers.keys.none {it.equals("content-type",true)}) connection.setRequestProperty("Content-Type","text/plain; charset=utf-8")
                    connection.setFixedLengthStreamingMode(spec.body.size)
                    connection.outputStream.use {output->
                        var offset=0
                        while(offset<spec.body.size) {check();val length=minOf(1024,spec.body.size-offset);output.write(spec.body,offset,length);offset+=length}
                    }
                }
                check();connection.readTimeout=remaining()
                val status=connection.responseCode
                if(status !in 100..599) throw RuntimeHttpFailure("HTTP_RESPONSE_INVALID")
                val headers=JsonObject();var headerBytes=0
                for((name,values) in connection.headerFields) {
                    if(name==null) continue
                    if(headers.size()>=32 || name.length !in 1..64 || values.size>8) throw RuntimeHttpFailure("HTTP_HEADERS_TOO_LARGE")
                    val array=com.google.gson.JsonArray()
                    headerBytes+=name.toByteArray(Charsets.UTF_8).size
                    for(value in values) {headerBytes+=value.toByteArray(Charsets.UTF_8).size;if(headerBytes>1024) throw RuntimeHttpFailure("HTTP_HEADERS_TOO_LARGE");array.add(value)}
                    headers.add(name.lowercase(java.util.Locale.ROOT),array)
                }
                val output=ByteArrayOutputStream(minOf(spec.maxResponseBytes,1024))
                if(spec.method!="HEAD" && status !in setOf(204,304)) {
                    val stream=if(status>=400) connection.errorStream else connection.inputStream
                    stream?.use {input->
                        val buffer=ByteArray(1024)
                        while(true) {
                            connection.readTimeout=remaining()
                            val count=input.read(buffer,0,minOf(buffer.size,spec.maxResponseBytes-output.size()+1))
                            check()
                            if(count<0) break
                            if(output.size()+count>spec.maxResponseBytes) throw RuntimeHttpFailure("HTTP_BODY_TOO_LARGE")
                            output.write(buffer,0,count)
                        }
                    }
                }
                check()
                JsonObject().apply {
                    addProperty("status",status);add("headers",headers);addProperty("bodyBase64",encode(output.toByteArray()))
                    addProperty("elapsedMs",(clock()-start)/1_000_000)
                }.toString().also {if(it.toByteArray(Charsets.UTF_8).size>32768) throw RuntimeHttpFailure("HTTP_RESPONSE_TOO_LARGE")}
            }.recoverCatching {error->
                when {
                    error is RuntimeHttpFailure->throw error
                    job.cancelled.get() || !valid()->throw RuntimeHttpFailure("HTTP_CANCELLED")
                    clock()-deadline>=0 || error is java.net.SocketTimeoutException->throw RuntimeHttpFailure("HTTP_TIMEOUT")
                    error is javax.net.ssl.SSLException->throw RuntimeHttpFailure("HTTP_TLS_FAILED")
                    else->throw RuntimeHttpFailure("HTTP_IO_FAILED")
                }
            }
            runCatching {job.connection.getAndSet(null)?.disconnect()}
            jobs.remove(work,job)
            complete(result)
        }} catch(_:java.util.concurrent.RejectedExecutionException) {jobs.remove(work,job);complete(Result.failure(RuntimeHttpFailure("HTTP_BUSY")))}
    }
    fun cancel(work:Long) {jobs[work]?.let {job->
        job.cancelled.set(true)
        val connection=job.connection.get() ?: return
        runCatching {closer.execute {if(job.connection.compareAndSet(connection,null)) runCatching {connection.disconnect()}}}
    }}
    fun reset() {jobs.keys.toList().forEach(::cancel)}
    fun close() {reset();worker.shutdown();closer.shutdown(); /* cancelled queued jobs still complete and release ownership */}
}
