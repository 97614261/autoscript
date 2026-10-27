package com.autoscript.runtime.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RuntimeHttpRequestsTest {
    private fun request(url:String,method:String="GET",body:String="",headers:String="{}",timeout:Int=2000,limit:Int=8192)=JsonObject().apply {
        addProperty("url",url);addProperty("method",method);addProperty("body",body);addProperty("headersJson",headers)
        addProperty("timeoutMs",timeout);addProperty("maxResponseBytes",limit)
    }.toString()
    private fun execute(source:String):Result<String> {
        val client=RuntimeHttpRequests();val done=CountDownLatch(1);val result=AtomicReference<Result<String>>()
        try {client.submit(1,source,{true}) {result.set(it);done.countDown()};assertTrue(done.await(6,TimeUnit.SECONDS));return result.get()}
        finally {client.close()}
    }
    private fun server(action:(java.net.Socket)->Unit,test:(String)->Unit) {
        val listener=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));val failure=AtomicReference<Throwable?>()
        val thread=Thread {
            try {listener.accept().use {it.soTimeout=3000;action(it)}} catch(error:Throwable) {if(!listener.isClosed) failure.set(error)}
        }.apply {isDaemon=true;start()}
        try {test("http://127.0.0.1:${listener.localPort}/public-qa")}
        finally {listener.close();thread.join(4000);assertFalse(thread.isAlive);failure.get()?.let {throw AssertionError(it)}}
    }
    private fun readHeaders(socket:java.net.Socket):List<String> {
        val input=socket.getInputStream().bufferedReader(Charsets.US_ASCII);val lines=mutableListOf<String>()
        while(true) {val line=input.readLine() ?: break;if(line.isEmpty()) break;lines.add(line)}
        return lines
    }
    @Test fun strictSpecRejectsCredentialsOtherSchemesInvalidHeadersAndWrongTypes() {
        for(url in listOf("file:///data/private","https://user:secret@example.com","http://","https://example.com/a\r\nb","https://example.com:0/"))
            assertTrue(runCatching {RuntimeHttpSpec.parse(request(url))}.isFailure)
        for(headers in listOf("{\"Host\":\"bad\"}","{\"X-Test\":\"a\\r\\nb\"}","{\"x\":1}","[]","{\"X\":\"a\",\"x\":\"b\"}"))
            assertTrue(runCatching {RuntimeHttpSpec.parse(request("https://example.com",headers=headers))}.isFailure)
        assertTrue(runCatching {RuntimeHttpSpec.parse(request("https://example.com",body="x"))}.isFailure)
        assertTrue(runCatching {RuntimeHttpSpec.parse(request("https://example.com",timeout=30001))}.isFailure)
        assertTrue(runCatching {RuntimeHttpSpec.parse(request("https://example.com").replace("2000","2000.5"))}.isFailure)
    }
    @Test fun loopbackReturnsBinaryBase64StatusHeadersAndNeverFollowsRedirect() {
        server({socket->
            val headers=readHeaders(socket);assertTrue(headers.first().startsWith("GET /public-qa "));assertTrue(headers.any {it.equals("Accept-Encoding: identity",true)})
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\nX-Public: yes\r\n\r\n中A".toByteArray(Charsets.UTF_8))
        }) {url->
            val result=JsonParser.parseString(execute(request(url)).getOrThrow()).asJsonObject
            assertEquals(200,result["status"].asInt);assertEquals("中A",StrictBase64.decode(result["bodyBase64"].asString).toString(Charsets.UTF_8))
            assertEquals("yes",result["headers"].asJsonObject["x-public"].asJsonArray[0].asString)
        }
        server({socket->readHeaders(socket);socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/never\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())}) {url->
            assertEquals(302,JsonParser.parseString(execute(request(url)).getOrThrow()).asJsonObject["status"].asInt)
        }
    }
    @Test fun bodyOverflowIsAnErrorNotSilentTruncation() {
        server({socket->readHeaders(socket);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n"+"x".repeat(100)).toByteArray())}) {url->
            assertEquals("HTTP_BODY_TOO_LARGE",(execute(request(url,limit=10)).exceptionOrNull() as RuntimeHttpFailure).code)
        }
    }
    @Test fun cancellationReturnsPromptlyWithoutClosingOnCallerThread() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        server({socket->readHeaders(socket);entered.countDown();release.await(3,TimeUnit.SECONDS)}) {url->
            val client=RuntimeHttpRequests();val done=CountDownLatch(1);val result=AtomicReference<Result<String>>()
            try {
                client.submit(9,request(url,timeout=1000),{true}) {result.set(it);done.countDown()}
                assertTrue(entered.await(2,TimeUnit.SECONDS));val start=System.nanoTime();client.cancel(9)
                assertTrue((System.nanoTime()-start)/1_000_000<250)
                release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS))
                assertEquals("HTTP_CANCELLED",(result.get().exceptionOrNull() as RuntimeHttpFailure).code)
            } finally {release.countDown();client.close()}
        }
    }
}
