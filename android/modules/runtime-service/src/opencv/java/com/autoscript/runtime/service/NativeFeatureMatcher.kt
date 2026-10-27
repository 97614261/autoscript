package com.autoscript.runtime.service

import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Explicit bounded ORB/RANSAC, never a fallback for pixel or grayscale matching. */
internal class NativeFeatureMatcher:NativeVisionBackend {
    private var orb:ORB?=null
    private var matcher:BFMatcher?=null
    private data class TemplateKey(val width:Int,val height:Int,val stride:Int,val format:Int,val digest:List<Byte>)
    private data class TemplateFeatures(val points:Array<KeyPoint>,val descriptors:Mat) {
        val accountedBytes:Long get()=descriptors.total()*descriptors.elemSize()+points.size*64L
    }
    // Adapter ownership serializes execute/close. No scene features or screen buffer are retained.
    private val templates=LinkedHashMap<TemplateKey,TemplateFeatures>(4,.75f,true)
    private var templateBytes=0L

    private fun preparedTemplate(pixels:ByteBuffer,metadata:IntArray,detector:ORB,work:NativeVisionWork):TemplateFeatures {
        val bytes=Math.multiplyExact(metadata[2],metadata[1])
        val input=pixels.asReadOnlyBuffer().apply {position(0);limit(bytes)}
        val digest=MessageDigest.getInstance("SHA-256")
        val chunk=ByteArray(minOf(16*1024,bytes))
        while(input.hasRemaining()) {
            work.checkActive()
            val count=minOf(chunk.size,input.remaining());input.get(chunk,0,count);digest.update(chunk,0,count)
        }
        val key=TemplateKey(metadata[0],metadata[1],metadata[2],metadata[3],digest.digest().toList())
        work.checkActive()
        templates[key]?.let {return it}
        val template=NativeCvBuffers.image(pixels,metadata)
        val gray=Mat();val alpha=Mat();val empty=Mat();val keys=MatOfKeyPoint();val descriptors=Mat()
        var retained=false
        try {
            Core.extractChannel(template,alpha,3)
            require(Core.minMaxLoc(alpha).minVal==255.0) {"FEATURE_REQUIRES_OPAQUE_TEMPLATE"}
            Imgproc.cvtColor(template,gray,if(metadata[3]==1) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGRA2GRAY)
            work.checkActive();detector.detectAndCompute(gray,empty,keys,descriptors);work.checkActive()
            require(descriptors.rows()<=512)
            val prepared=TemplateFeatures(keys.toArray(),descriptors)
            require(prepared.accountedBytes<=128*1024)
            work.checkActive()
            while(templates.isNotEmpty() && (templates.size>=2 || templateBytes+prepared.accountedBytes>128*1024)) {
                val iterator=templates.entries.iterator();val evicted=iterator.next().value
                iterator.remove();templateBytes-=evicted.accountedBytes;evicted.descriptors.release()
            }
            templates[key]=prepared;templateBytes+=prepared.accountedBytes;retained=true
            return prepared
        } finally {
            releaseNativeVisionResources({if(!retained) descriptors.release()},{keys.release()},{empty.release()},
                {alpha.release()},{gray.release()},{template.release()})
        }
    }
    override fun execute(work:NativeVisionWork,pixels:ByteBuffer,metadata:IntArray,
        templatePixels:ByteBuffer?,templateMetadata:IntArray,options:IntArray):ByteArray {
        require(templatePixels!=null);NativeCvBuffers.load();work.checkActive()
        val roi=checkedVisionRect(metadata[0],metadata[1],options)
        require(roi.width.toLong()*roi.height<=1_048_576 && templateMetadata[0].toLong()*templateMetadata[1]<=262_144)
        if(roi.width<32 || roi.height<32 || templateMetadata[0]<32 || templateMetadata[1]<32) return byteArrayOf()
        val detector=orb ?: ORB.create(512,1.2f,8,15,0,2,ORB.HARRIS_SCORE,31,20).also {orb=it}
        val matching=matcher ?: BFMatcher.create(Core.NORM_HAMMING,false).also {matcher=it}
        val prepared=preparedTemplate(templatePixels,templateMetadata,detector,work)
        val source=NativeCvBuffers.image(pixels,metadata)
        val clipped=source.submat(Rect(roi.x,roi.y,roi.width,roi.height))
        val gray=Mat();val alpha=Mat();val empty=Mat()
        val sourceKeys=MatOfKeyPoint();val sourceDesc=Mat();val templateDesc=prepared.descriptors
        val before=MatOfPoint2f();val after=MatOfPoint2f();val inlierMask=Mat();val corners=MatOfPoint2f();val mapped=MatOfPoint2f()
        val matches=mutableListOf<MatOfDMatch>();var transform:Mat?=null
        try {
            // Transparent filter/mask edges must not become artificial keypoints.
            Core.extractChannel(clipped,alpha,3);require(Core.minMaxLoc(alpha).minVal==255.0) {"FEATURE_REQUIRES_OPAQUE_SOURCE"}
            Imgproc.cvtColor(clipped,gray,if(metadata[3]==1) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGRA2GRAY)
            work.checkActive();detector.detectAndCompute(gray,empty,sourceKeys,sourceDesc)
            work.checkActive()
            if(templateDesc.empty() || sourceDesc.rows()<2) return byteArrayOf()
            require(templateDesc.rows()<=512 && sourceDesc.rows()<=512)
            matching.knnMatch(templateDesc,sourceDesc,matches,2);work.checkActive()
            val sourcePoints=sourceKeys.toArray();val templatePoints=prepared.points
            val selected=mutableListOf<DMatch>();val used=mutableSetOf<Int>()
            for((index,group) in matches.withIndex()) {
                if(index%16==0) work.checkActive()
                val pair=group.toArray()
                if(pair.size==2 && pair[0].distance.isFinite() && pair[1].distance.isFinite() && pair[1].distance>0 && pair[0].distance<pair[1].distance*.75f &&
                    pair[0].queryIdx in templatePoints.indices && pair[0].trainIdx in sourcePoints.indices && used.add(pair[0].trainIdx)) selected.add(pair[0])
            }
            if(selected.size<8) return byteArrayOf()
            before.fromArray(*selected.map {templatePoints[it.queryIdx].pt}.toTypedArray())
            after.fromArray(*selected.map {sourcePoints[it.trainIdx].pt}.toTypedArray())
            transform=Calib3d.findHomography(before,after,Calib3d.RANSAC,3.0,inlierMask,256,.995)
            work.checkActive()
            if(transform.empty()) return byteArrayOf()
            val inliers=Core.countNonZero(inlierMask);val score=inliers*1000/selected.size
            if(inliers<8 || score<options[4]) return byteArrayOf()
            val w=templateMetadata[0].toDouble();val h=templateMetadata[1].toDouble()
            corners.fromArray(Point(0.0,0.0),Point(w,0.0),Point(w,h),Point(0.0,h))
            Core.perspectiveTransform(corners,mapped,transform);work.checkActive()
            val points=mapped.toArray()
            if(points.size!=4 || points.any {!it.x.isFinite() || !it.y.isFinite() || it.x<0 || it.y<0 || it.x>roi.width || it.y>roi.height}) return byteArrayOf()
            for(i in 0..3) {
                val a=points[i];val b=points[(i+1)%4];val c=points[(i+2)%4]
                if((b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)<=1.0) return byteArrayOf()
                val scale=hypot(b.x-a.x,b.y-a.y)/(if(i%2==0) w else h)
                if(scale !in .25..4.0) return byteArrayOf()
            }
            val left=floor(points.minOf {it.x}).toInt();val top=floor(points.minOf {it.y}).toInt()
            val right=ceil(points.maxOf {it.x}).toInt();val bottom=ceil(points.maxOf {it.y}).toInt()
            if(right<=left || bottom<=top) return byteArrayOf()
            return ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putInt(left+roi.x).putInt(top+roi.y)
                .putInt(right-left).putInt(bottom-top).putShort(score.toShort()).array()
        } finally {
            releaseNativeVisionResources({matches.forEach {it.release()}},{transform?.release()},{mapped.release()},{corners.release()},
                {inlierMask.release()},{after.release()},{before.release()},{sourceDesc.release()},
                {sourceKeys.release()},{empty.release()},{alpha.release()},{gray.release()},
                {clipped.release()},{source.release()})
        }
    }
    override fun close() {
        val entries=templates.values.toList();templates.clear();templateBytes=0
        val detector=orb;val matching=matcher;orb=null;matcher=null
        releaseNativeVisionResources(*entries.map {entry -> {entry.descriptors.release()}}.toTypedArray(),
            {detector?.clear()},{matching?.clear()})
    }
}
