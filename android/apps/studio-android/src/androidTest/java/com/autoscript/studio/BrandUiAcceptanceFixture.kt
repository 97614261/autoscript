package com.autoscript.studio

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.autoscript.brand.ui.BrandState
import com.autoscript.brand.ui.BrandSurface
import com.autoscript.brand.ui.ZhigouMarkView

/** Draws only our own synthetic brand surface; never captures another app or input. */
internal object BrandUiAcceptanceFixture {
    fun run(test: Instrumentation, activity: Activity, record: (String, () -> Unit) -> Unit) {
        lateinit var stage: FrameLayout
        lateinit var mark: ZhigouMarkView
        test.runOnMainSync {
            stage = FrameLayout(activity)
            mark = ZhigouMarkView(activity).apply { surface = BrandSurface.FLOATING }
            stage.addView(mark, FrameLayout.LayoutParams(256, 256))
            activity.setContentView(stage)
        }
        fun pixels(): IntArray {
            lateinit var result: IntArray
            test.runOnMainSync {
                check(mark.width == 256 && mark.height == 256) { "BRAND_VIEW_GEOMETRY" }
                val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                try {
                    mark.draw(Canvas(image))
                    result = IntArray(256 * 256)
                    image.getPixels(result, 0, 256, 0, 0, 256, 256)
                } finally { image.recycle() }
            }
            return result
        }
        try {
            SystemClock.sleep(200)
            record("brand-launcher-and-themed-mask-resolve") {
                test.runOnMainSync {
                    val app = activity.applicationInfo
                    check(activity.getDrawable(app.icon) != null) { "BRAND_LAUNCHER_MISSING" }
                    check(activity.getDrawable(com.autoscript.brand.ui.R.drawable.zhigou_brand_monochrome) != null) { "BRAND_MASK_MISSING" }
                }
            }
            record("brand-floating-pastel-logo-and-external-halo") {
                val sample = pixels()
                val center = sample[128 * 256 + 128]
                check(Color.green(center) >= 175 && Color.red(center) >= 185) { "BRAND_LOGO_NOT_PASTEL" }
                // Radius .453 lies outside the opaque sphere (.418) but inside its halo.
                check(Color.alpha(sample[12 * 256 + 128]) >= 12) { "BRAND_OUTER_HALO_MISSING" }
                check(Color.alpha(sample[0]) == 0) { "BRAND_TRANSPARENT_CORNER_LOST" }
            }
            record("brand-idle-executing-running-and-one-shot-success") {
                for (state in listOf(BrandState.IDLE, BrandState.EXECUTING, BrandState.RUNNING)) {
                    test.runOnMainSync { mark.brandState = state }
                    val before = pixels(); SystemClock.sleep(240)
                    check(!before.contentEquals(pixels())) { "BRAND_ACTIVE_LIGHT_STATIC" }
                }
                test.runOnMainSync { mark.brandState = BrandState.SUCCESS }
                SystemClock.sleep(1_500)
                val completed = pixels(); SystemClock.sleep(200)
                check(completed.contentEquals(pixels())) { "BRAND_SUCCESS_REPEATS" }
            }
            record("brand-paused-and-failed-remain-static") {
                for (state in listOf(BrandState.PAUSED, BrandState.FAILED)) {
                    test.runOnMainSync { mark.brandState = state }
                    val before = pixels(); SystemClock.sleep(200)
                    check(before.contentEquals(pixels())) { "BRAND_INACTIVE_LIGHT_MOVED" }
                }
            }
            record("brand-hidden-and-detached-stop-animation") {
                test.runOnMainSync { mark.brandState = BrandState.RUNNING; mark.visibility = View.INVISIBLE }
                SystemClock.sleep(80)
                val hidden = pixels(); SystemClock.sleep(200)
                check(hidden.contentEquals(pixels())) { "BRAND_HIDDEN_ANIMATES" }
                test.runOnMainSync { mark.visibility = View.VISIBLE }
                SystemClock.sleep(100)
                test.runOnMainSync { stage.removeView(mark) }
                val detached = pixels(); SystemClock.sleep(200)
                check(detached.contentEquals(pixels())) { "BRAND_DETACHED_ANIMATES" }
            }
        } finally { test.runOnMainSync { stage.removeAllViews() } }
    }
}
