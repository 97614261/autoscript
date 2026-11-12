package com.autoscript.runtime.service

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class PolygonBackendCapabilityTest {
    private open class Backend : NativeVisionBackend {
        var options= intArrayOf()
        override fun execute(work:NativeVisionWork,pixels:ByteBuffer,metadata:IntArray,templatePixels:ByteBuffer?,templateMetadata:IntArray,options:IntArray):ByteArray {
            this.options=options;return byteArrayOf(7)
        }
        override fun close() {}
    }
    private class CurrentBackend : Backend(),NativePolygonSearchBackend
    private fun dispatch(backend:NativeVisionBackend,operation:Int=5108,polygon:Boolean=true):ByteArray {
        val source=object:NativeVisionBackendSource {
            override fun resolve(operation:Int,work:NativeVisionWork)=backend
            override fun close() {}
        }
        return NativeVisionAdapter(source).use {
            val options=intArrayOf(0,0,4,4,900)+(if(polygon) intArrayOf(-203,3,0,0,4,0,0,4) else intArrayOf())
            it.dispatch(1,operation,ByteBuffer.allocateDirect(64),intArrayOf(4,4,16,1),ByteBuffer.allocateDirect(64),intArrayOf(4,4,16,1),options)
        }
    }
    @Test fun oldProviderIsRejectedForPolygonButRectangleStillWorks() {
        val old=Backend()
        assertEquals("POLYGON_REGION_UNSUPPORTED",String(dispatch(old).drop(1).toByteArray(),Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0,7),dispatch(old,polygon=false))
    }
    @Test fun currentCapabilitySurvivesProviderAndSessionWrappersAndFeatureFlag() {
        val current=CurrentBackend()
        val loaded=LoadedVisionProvider(current,3)
        val session=VisionRuntimeSession(loaded)
        assertArrayEquals(byteArrayOf(0,7),dispatch(session,5109))
        assertArrayEquals(intArrayOf(0,0,4,4,900,1,-203,3,0,0,4,0,0,4),current.options)
        assertFalse(LoadedVisionProvider(Backend(),3).supportsPolygonSearch())
    }
}
