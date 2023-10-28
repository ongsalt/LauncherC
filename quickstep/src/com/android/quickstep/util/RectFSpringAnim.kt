/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep.util

import android.animation.Animator
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import androidx.annotation.IntDef
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.DynamicAnimation.OnAnimationEndListener
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.anim.FlingSpringAnim
import com.android.launcher3.util.DynamicResource
import com.android.quickstep.RemoteAnimationTargets.ReleaseCheck

/**
 * Applies spring forces to animate from a starting rect to a target rect,
 * while providing update callbacks to the caller.
 */
open class RectFSpringAnim(
    private val mStartRect: RectF, private val mTargetRect: RectF, context: Context?,
    deviceProfile: DeviceProfile?
) : ReleaseCheck() {
    private val mCurrentRect = RectF()
    private val mOnUpdateListeners: MutableList<OnUpdateListener> = ArrayList()
    private val mAnimatorListeners: MutableList<Animator.AnimatorListener> = ArrayList()
    private var mCurrentCenterX: Float
    private var mCurrentY: Float

    // If true, tracking the bottom of the rects, else tracking the top.
    private var mCurrentScaleProgress = 0f

    private var mRectXAnim: FlingSpringAnim? = null
    private var mRectYAnim: FlingSpringAnim? = null
    private var mRectScaleAnim: SpringAnimation? = null
    private var mAnimsStarted = false
    private var mRectXAnimEnded = false
    private var mRectYAnimEnded = false
    private var mRectScaleAnimEnded = false
    private val mMinVisChange: Float
    private val mMaxVelocityPxPerS: Int

    @Retention(AnnotationRetention.SOURCE)
    @IntDef(value = [TRACKING_TOP, TRACKING_CENTER, TRACKING_BOTTOM])
    annotation class Tracking

    @Tracking
    var mTracking = 0

    init {
        mCurrentCenterX = mStartRect.centerX()
        val rp = DynamicResource.provider(context)
        mMinVisChange = rp.getDimension(R.dimen.swipe_up_fling_min_visible_change)
        mMaxVelocityPxPerS = rp.getDimension(R.dimen.swipe_up_max_velocity).toInt()
        setCanRelease(true)
        mTracking = if (deviceProfile == null) {
            if (mStartRect.bottom < mTargetRect.bottom) TRACKING_BOTTOM else TRACKING_TOP
        } else {
            val heightPx = deviceProfile.heightPx
            val padding = deviceProfile.workspacePadding
            val topThreshold = heightPx / 3f
            val bottomThreshold = (deviceProfile.heightPx - padding.bottom).toFloat()
            if (mTargetRect.bottom > bottomThreshold) {
                TRACKING_BOTTOM
            } else if (mTargetRect.top < topThreshold) {
                TRACKING_TOP
            } else {
                TRACKING_CENTER
            }
        }
        mCurrentY = getTrackedYFromRect(mStartRect)
    }

    private fun getTrackedYFromRect(rect: RectF): Float {
        return when (mTracking) {
            TRACKING_TOP -> rect.top
            TRACKING_BOTTOM -> rect.bottom
            TRACKING_CENTER -> rect.centerY()
            else -> rect.centerY()
        }
    }

    fun onTargetPositionChanged() {
        if (mRectXAnim != null && mRectXAnim!!.targetPosition != mTargetRect.centerX()) {
            mRectXAnim!!.updatePosition(mCurrentCenterX, mTargetRect.centerX())
        }
        if (mRectYAnim != null) {
            when (mTracking) {
                TRACKING_TOP -> if (mRectYAnim!!.targetPosition != mTargetRect.top) {
                    mRectYAnim!!.updatePosition(mCurrentY, mTargetRect.top)
                }

                TRACKING_BOTTOM -> if (mRectYAnim!!.targetPosition != mTargetRect.bottom) {
                    mRectYAnim!!.updatePosition(mCurrentY, mTargetRect.bottom)
                }

                TRACKING_CENTER -> if (mRectYAnim!!.targetPosition != mTargetRect.centerY()) {
                    mRectYAnim!!.updatePosition(mCurrentY, mTargetRect.centerY())
                }
            }
        }
    }

    fun addOnUpdateListener(onUpdateListener: OnUpdateListener) {
        mOnUpdateListeners.add(onUpdateListener)
    }

    fun addAnimatorListener(animatorListener: Animator.AnimatorListener) {
        mAnimatorListeners.add(animatorListener)
    }

    /**
     * Starts the fling/spring animation.
     *
     * @param context         The activity context.
     * @param velocityPxPerMs Velocity of swipe in px/ms.
     */
    fun start(context: Context?, profile: DeviceProfile?, velocityPxPerMs: PointF) {
        // Only tell caller that we ended if both x and y animations have ended.
        val onXEndListener =
            OnAnimationEndListener { animation: DynamicAnimation<*>?, canceled: Boolean, centerX: Float, velocityX: Float ->
                mRectXAnimEnded = true
                maybeOnEnd()
            }
        val onYEndListener =
            OnAnimationEndListener { animation: DynamicAnimation<*>?, canceled: Boolean, centerY: Float, velocityY: Float ->
                mRectYAnimEnded = true
                maybeOnEnd()
            }

        // We dampen the user velocity here to keep the natural feeling and to prevent the
        // rect from straying too from a linear path.
        val xVelocityPxPerS = velocityPxPerMs.x * 1000
        val yVelocityPxPerS = velocityPxPerMs.y * 1000

        // TODO: Change scroll damping logic
        Log.d("Closing", "velocity start: $xVelocityPxPerS $yVelocityPxPerS")
        Log.d("Closing", "velocity damped: $xVelocityPxPerS $yVelocityPxPerS")
        val startX = mCurrentCenterX
        val endX = mTargetRect.centerX()
        val minXValue = Math.min(startX, endX)
        val maxXValue = Math.max(startX, endX)
        mRectXAnim = FlingSpringAnim(
            this, context, RECT_CENTER_X, startX, endX,
            xVelocityPxPerS, mMinVisChange, minXValue, maxXValue, onXEndListener
        )
        val startY = mCurrentY
        val endY = getTrackedYFromRect(mTargetRect)
        val minYValue = Math.min(startY, endY)
        val maxYValue = Math.max(startY, endY)
        mRectYAnim = FlingSpringAnim(
            this, context, RECT_Y, startY, endY, yVelocityPxPerS,
            mMinVisChange, minYValue, maxYValue, onYEndListener
        )
        val minVisibleChange = Math.abs(1f / mStartRect.height())
        Log.d("Closing", "minVisibleChange $minVisibleChange")
        val rp = DynamicResource.provider(context)
        val damping = rp.getFloat(R.dimen.swipe_up_rect_scale_damping_ratio)

        // Increase the stiffness for devices where we want the window size to transform quicker.
        val shouldUseHigherStiffness = (profile != null
                && (profile.isLandscape || profile.isTablet))
        val stiffness =
            if (shouldUseHigherStiffness) rp.getFloat(R.dimen.swipe_up_rect_scale_higher_stiffness) else rp.getFloat(
                R.dimen.swipe_up_rect_scale_stiffness
            )
        mRectScaleAnim = SpringAnimation(this, RECT_SCALE_PROGRESS)
            .setSpring(
                SpringForce(1f)
                    .setDampingRatio(damping)
                    .setStiffness(stiffness)
            )
            .setStartVelocity(velocityPxPerMs.y * minVisibleChange)
            .setMaxValue(4f)
            .setMinimumVisibleChange(minVisibleChange)
            .addEndListener { animation: DynamicAnimation<*>?, canceled: Boolean, value: Float, velocity: Float ->
                Log.d("Closing", "value $value, velocity$velocity")
                mRectScaleAnimEnded = true
                maybeOnEnd()
            }

//        float startScale = mCurrentScaleProgress;
//        float endScale = 1f;
//        float minScaleValue = Math.min(startScale, endScale);
//        float maxScaleValue = Math.max(startScale, endScale);

//        mRectScaleAnim = new FlingSpringAnim(this, context, RECT_SCALE_PROGRESS, startScale, endScale,
//                velocityPxPerMs.y * minVisibleChange, minVisibleChange, minScaleValue, maxScaleValue,
//                (animation, canceled, value, velocity) -> {
//                    Log.d("Closing", "value " + value + ", velocity " + velocity);
//                    mRectScaleAnimEnded = true;
//                    maybeOnEnd();
//                });
        setCanRelease(false)
        mAnimsStarted = true
        mRectXAnim!!.start()
        mRectYAnim!!.start()
        mRectScaleAnim!!.start()
        for (animatorListener in mAnimatorListeners) {
            animatorListener.onAnimationStart(null)
        }
    }

    fun end() {
        if (mAnimsStarted) {
            mRectXAnim!!.end()
            mRectYAnim!!.end()
            //            mRectScaleAnim.end();
            if (mRectScaleAnim!!.canSkipToEnd()) {
                mRectScaleAnim!!.skipToEnd()
            }
        }
        mRectXAnimEnded = true
        mRectYAnimEnded = true
        mRectScaleAnimEnded = true
        maybeOnEnd()
    }

    private val isEnded: Boolean
        private get() = mRectXAnimEnded && mRectYAnimEnded && mRectScaleAnimEnded

    private fun onUpdate() {
        if (isEnded) {
            // Prevent further updates from being called. This can happen between callbacks for
            // ending the x/y/scale animations.
            return
        }
        if (!mOnUpdateListeners.isEmpty()) {
            val currentWidth = Utilities.mapRange(
                mCurrentScaleProgress, mStartRect.width(),
                mTargetRect.width()
            )
            val currentHeight = Utilities.mapRange(
                mCurrentScaleProgress, mStartRect.height(),
                mTargetRect.height()
            )
            when (mTracking) {
                TRACKING_TOP -> mCurrentRect[mCurrentCenterX - currentWidth / 2, mCurrentY, mCurrentCenterX + currentWidth / 2] =
                    mCurrentY + currentHeight

                TRACKING_BOTTOM -> mCurrentRect[mCurrentCenterX - currentWidth / 2, mCurrentY - currentHeight, mCurrentCenterX + currentWidth / 2] =
                    mCurrentY

                TRACKING_CENTER -> mCurrentRect[mCurrentCenterX - currentWidth / 2, mCurrentY - currentHeight / 2, mCurrentCenterX + currentWidth / 2] =
                    mCurrentY + currentHeight / 2
            }
            for (onUpdateListener in mOnUpdateListeners) {
                onUpdateListener.onUpdate(mCurrentRect, mCurrentScaleProgress)
            }
        }
    }

    private fun maybeOnEnd() {
        if (mAnimsStarted && isEnded) {
            mAnimsStarted = false
            setCanRelease(true)
            for (animatorListener in mAnimatorListeners) {
                animatorListener.onAnimationEnd(null)
            }
        }
    }

    fun cancel() {
        if (mAnimsStarted) {
            for (onUpdateListener in mOnUpdateListeners) {
                onUpdateListener.onCancel()
            }
        }
        end()
    }

    fun interface OnUpdateListener {
        /**
         * Called when an update is made to the animation.
         *
         * @param currentRect The rect of the window.
         * @param progress    [0, 1] The progress of the rect scale animation.
         */
        fun onUpdate(currentRect: RectF?, progress: Float)
        fun onCancel() {

        }
    }

    companion object {
        private val RECT_CENTER_X: FloatPropertyCompat<RectFSpringAnim> =
            object : FloatPropertyCompat<RectFSpringAnim>("rectCenterXSpring") {
                override fun getValue(anim: RectFSpringAnim): Float {
                    return anim.mCurrentCenterX
                }

                override fun setValue(anim: RectFSpringAnim, currentCenterX: Float) {
                    anim.mCurrentCenterX = currentCenterX
                    anim.onUpdate()
                }
            }
        private val RECT_Y: FloatPropertyCompat<RectFSpringAnim> =
            object : FloatPropertyCompat<RectFSpringAnim>("rectYSpring") {
                override fun getValue(anim: RectFSpringAnim): Float {
                    return anim.mCurrentY
                }

                override fun setValue(anim: RectFSpringAnim, y: Float) {
                    anim.mCurrentY = y
                    anim.onUpdate()
                }
            }
        private val RECT_SCALE_PROGRESS: FloatPropertyCompat<RectFSpringAnim> =
            object : FloatPropertyCompat<RectFSpringAnim>("rectScaleProgress") {
                override fun getValue(`object`: RectFSpringAnim): Float {
                    return `object`.mCurrentScaleProgress
                }

                override fun setValue(`object`: RectFSpringAnim, value: Float) {
                    `object`.mCurrentScaleProgress = value
                    `object`.onUpdate()
                }
            }

        /**
         * Indicates which part of the start & target rects we are interpolating between.
         */
        const val TRACKING_TOP = 0
        const val TRACKING_CENTER = 1
        const val TRACKING_BOTTOM = 2
    }
}