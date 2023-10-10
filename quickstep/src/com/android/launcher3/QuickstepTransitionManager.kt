/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.launcher3

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.WindowConfiguration
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings.Secure
import android.util.Log
import android.util.Pair
import android.util.Size
import android.view.CrossWindowBlurListeners
import android.view.RemoteAnimationAdapter
import android.view.RemoteAnimationDefinition
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver.OnDrawListener
import android.view.WindowInsetsAnimation.Bounds
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.window.RemoteTransition
import android.window.StartingWindowInfo
import android.window.TransitionFilter
import android.window.TransitionFilter.Requirement
import androidx.core.graphics.ColorUtils
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener
import com.android.launcher3.LauncherAnimationRunner.AnimationResult
import com.android.launcher3.LauncherAnimationRunner.RemoteAnimationFactory
import com.android.launcher3.anim.AnimationSuccessListener
import com.android.launcher3.anim.AnimatorListeners
import com.android.launcher3.anim.Interpolators
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.icons.FastBitmapDrawable
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.shortcuts.DeepShortcutView
import com.android.launcher3.statehandlers.DepthController
import com.android.launcher3.testing.shared.ResourceUtils
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.util.ActivityOptionsWrapper
import com.android.launcher3.util.DisplayController
import com.android.launcher3.util.DynamicResource
import com.android.launcher3.util.MultiPropertyFactory
import com.android.launcher3.util.ObjectWrapper
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.Themes
import com.android.launcher3.util.window.RefreshRateTracker
import com.android.launcher3.views.FloatingIconView
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.quickstep.LauncherBackAnimationController
import com.android.quickstep.RemoteAnimationTargets
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.TaskViewUtils
import com.android.quickstep.util.MultiValueUpdateListener
import com.android.quickstep.util.RectFSpringAnim
import com.android.quickstep.util.RemoteAnimationProvider
import com.android.quickstep.util.StaggeredWorkspaceAnim
import com.android.quickstep.util.SurfaceTransaction
import com.android.quickstep.util.SurfaceTransactionApplier
import com.android.quickstep.util.WorkspaceRevealAnim
import com.android.quickstep.views.FloatingWidgetView
import com.android.quickstep.views.RecentsView
import com.android.systemui.shared.system.BlurUtils
import com.android.systemui.shared.system.InteractionJankMonitorWrapper
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.shared.system.RemoteAnimationRunnerCompat
import com.android.wm.shell.startingsurface.IStartingWindowListener
import java.util.function.Consumer

/**
 * Manages the opening and closing app transitions from Launcher
 */
class QuickstepTransitionManager(context: Context?) : OnDeviceProfileChangeListener {
    protected lateinit var mLauncher: QuickstepLauncher
    private val mDragLayer: DragLayer
    val mHandler: Handler
    private val mClosingWindowTransY: Float
    private val mMaxShadowRadius: Float
    private val mStartingWindowListener = StartingWindowListener()
    private var mDeviceProfile: DeviceProfile
    private var mRemoteAnimationProvider: RemoteAnimationProvider? = null

    // Strong refs to runners which are cleared when the launcher activity is destroyed
    private var mWallpaperOpenRunner: RemoteAnimationFactory? = null
    private var mAppLaunchRunner: RemoteAnimationFactory? = null
    private var mKeyguardGoingAwayRunner: RemoteAnimationFactory? = null
    private var mWallpaperOpenTransitionRunner: RemoteAnimationFactory? = null
    private var mLauncherOpenTransition: RemoteTransition? = null
    private var mBackAnimationController: LauncherBackAnimationController?
    private val mForceInvisibleListener: AnimatorListenerAdapter =
        object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                mLauncher.addForceInvisibleFlag(BaseActivity.INVISIBLE_BY_APP_TRANSITIONS)
            }

            override fun onAnimationEnd(animation: Animator) {
                mLauncher.clearForceInvisibleFlag(BaseActivity.INVISIBLE_BY_APP_TRANSITIONS)
            }
        }

    // Pairs of window starting type and starting window background color for starting tasks
    // Will never be larger than MAX_NUM_TASKS
    private var mTaskStartParams: LinkedHashMap<Int, Pair<Int, Int>>? = null
    private val mOpeningInterpolator: Interpolator = Interpolators.APPLE_EASE
//    private val mOpeningXInterpolator: Interpolator

    init {
        mLauncher = Launcher.cast(Launcher.getLauncher(context))
        mDragLayer = mLauncher.dragLayer
        mHandler = Handler(Looper.getMainLooper())
        mDeviceProfile = mLauncher.deviceProfile
        mBackAnimationController = LauncherBackAnimationController(mLauncher, this)
        val res = mLauncher.resources
        mClosingWindowTransY = res.getDimensionPixelSize(R.dimen.closing_window_trans_y).toFloat()
        mMaxShadowRadius = res.getDimensionPixelSize(R.dimen.max_shadow_radius).toFloat()
        mLauncher.addOnDeviceProfileChangeListener(this)
        if (supportsSSplashScreen()) {
            mTaskStartParams = object : LinkedHashMap<Int, Pair<Int, Int>>(MAX_NUM_TASKS) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Int, Int>>?): Boolean {
                    return size > MAX_NUM_TASKS
                }
//                 fun removeEldestEntry(entry: Map.Entry<Int?, Pair<Int?, Int?>?>): Boolean {
//                     return size > MAX_NUM_TASKS
//                }
            }
            mStartingWindowListener.setTransitionManager(this)
            SystemUiProxy.INSTANCE[mLauncher].setStartingWindowListener(
                mStartingWindowListener
            )
        }
    }

    override fun onDeviceProfileChanged(dp: DeviceProfile) {
        mDeviceProfile = dp
    }

    /**
     * @return ActivityOptions with remote animations that controls how the window of the opening
     * targets are displayed.
     */
    fun getActivityLaunchOptions(v: View): ActivityOptionsWrapper {
        val fromRecents = isLaunchingFromRecents(v, null /* targets */)
        val onEndCallback = RunnableList()
        mAppLaunchRunner = AppLaunchAnimationRunner(v, onEndCallback)
        val runner: RemoteAnimationRunnerCompat = LauncherAnimationRunner(
            mHandler, mAppLaunchRunner, true /* startAtFrontOfQueue */
        )

        // Note that this duration is a guess as we do not know if the animation will be a
        // recents launch or not for sure until we know the opening app targets.
        val duration = if (fromRecents) RECENTS_LAUNCH_DURATION.toLong() else APP_LAUNCH_DURATION
        val statusBarTransitionDelay = (duration - STATUS_BAR_TRANSITION_DURATION
                - STATUS_BAR_TRANSITION_PRE_DELAY)
        val options = if (Utilities.ATLEAST_T) ActivityOptions.makeRemoteAnimation(
            RemoteAnimationAdapter(runner, duration, statusBarTransitionDelay),
            RemoteTransition(
                runner.toRemoteTransition(),
                mLauncher.iApplicationThread
            )
        ) else ActivityOptions.makeRemoteAnimation(
            RemoteAnimationAdapter(runner, duration, statusBarTransitionDelay)
        )
        return ActivityOptionsWrapper(options, onEndCallback)
    }

    /**
     * Whether the launch is a recents app transition and we should do a launch animation
     * from the recents view. Note that if the remote animation targets are not provided, this
     * may not always be correct as we may resolve the opening app to a task when the animation
     * starts.
     *
     * @param v       the view to launch from
     * @param targets apps that are opening/closing
     * @return true if the app is launching from recents, false if it most likely is not
     */
    protected fun isLaunchingFromRecents(
        v: View,
        targets: Array<RemoteAnimationTarget>?
    ): Boolean {
        return (mLauncher.stateManager.state.overviewUi
                && TaskViewUtils.findTaskViewToLaunch(
            mLauncher.getOverviewPanel(),
            v,
            targets
        ) != null)
    }

    /**
     * Composes the animations for a launch from the recents list.
     *
     * @param anim            the animator set to add to
     * @param v               the launching view
     * @param appTargets      the apps that are opening/closing
     * @param launcherClosing true if the launcher app is closing
     */
    protected fun composeRecentsLaunchAnimator(
        anim: AnimatorSet, v: View,
        appTargets: Array<RemoteAnimationTarget>,
        wallpaperTargets: Array<RemoteAnimationTarget>,
        nonAppTargets: Array<RemoteAnimationTarget>, launcherClosing: Boolean
    ) {
        TaskViewUtils.composeRecentsLaunchAnimator(
            anim, v, appTargets, wallpaperTargets,
            nonAppTargets, launcherClosing, mLauncher.stateManager,
            mLauncher.getOverviewPanel(), mLauncher.depthController
        )
    }

    private fun areAllTargetsTranslucent(targets: Array<RemoteAnimationTarget>): Boolean {
        var isAllOpeningTargetTrs = true
        for (i in targets.indices) {
            val target = targets[i]
            if (target.mode == RemoteAnimationTarget.MODE_OPENING) {
                isAllOpeningTargetTrs = isAllOpeningTargetTrs and target.isTranslucent
            }
            if (!isAllOpeningTargetTrs) break
        }
        return isAllOpeningTargetTrs
    }

    /**
     * Compose the animations for a launch from the app icon.
     *
     * @param anim            the animation to add to
     * @param v               the launching view with the icon
     * @param appTargets      the list of opening/closing apps
     * @param launcherClosing true if launcher is closing
     */
    private fun composeIconLaunchAnimator(
        anim: AnimatorSet, v: View,
        appTargets: Array<RemoteAnimationTarget>,
        wallpaperTargets: Array<RemoteAnimationTarget>,
        nonAppTargets: Array<RemoteAnimationTarget>,
        launcherClosing: Boolean
    ) {
        // Set the state animation first so that any state listeners are called
        // before our internal listeners.
        mLauncher.stateManager.setCurrentAnimation(anim)

        // Note: the targetBounds are relative to the launcher
        val startDelay = RefreshRateTracker.getSingleFrameMs(mLauncher)
        val windowAnimator = getOpeningWindowAnimators(
            v, appTargets, wallpaperTargets, nonAppTargets, launcherClosing
        )
        windowAnimator.startDelay = startDelay.toLong()
        anim.play(windowAnimator)
        if (launcherClosing) {
            // Delay animation by a frame to avoid jank.
            val launcherContentAnimator =
                getLauncherContentAnimator(true /* isAppOpening */, startDelay, false)
            anim.play(launcherContentAnimator.first)
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    launcherContentAnimator.second.run()
                }
            })
        }
    }

    private fun composeWidgetLaunchAnimator(
        anim: AnimatorSet,
        v: LauncherAppWidgetHostView,
        appTargets: Array<RemoteAnimationTarget>,
        wallpaperTargets: Array<RemoteAnimationTarget>,
        nonAppTargets: Array<RemoteAnimationTarget>,
        launcherClosing: Boolean
    ) {
        mLauncher.stateManager.setCurrentAnimation(anim)
        anim.play(
            getOpeningWindowAnimatorsForWidget(
                v, appTargets, wallpaperTargets, nonAppTargets, launcherClosing
            )
        )
    }

    /**
     * Return the window bounds of the opening target.
     * In multiwindow mode, we need to get the final size of the opening app window target to help
     * figure out where the floating view should animate to.
     */
    private fun getWindowTargetBounds(
        appTargets: Array<RemoteAnimationTarget>,
        rotationChange: Int
    ): Rect {
        var target: RemoteAnimationTarget? = null
        for (t in appTargets) {
            if (t.mode != RemoteAnimationTarget.MODE_OPENING) continue
            target = t
            break
        }
        if (target == null) return Rect(0, 0, mDeviceProfile.widthPx, mDeviceProfile.heightPx)
        val bounds =
            Rect(if (Utilities.ATLEAST_R) target.screenSpaceBounds else target.sourceContainerBounds)
        if (Utilities.ATLEAST_R && target.localBounds != null) {
            bounds.set(target.localBounds)
        } else {
            bounds.offsetTo(target.position.x, target.position.y)
        }
        if (rotationChange != 0) {
            if (rotationChange % 2 == 1) {
                // undoing rotation, so our "original" parent size is actually flipped
                Utilities.rotateBounds(
                    bounds, mDeviceProfile.heightPx, mDeviceProfile.widthPx,
                    4 - rotationChange
                )
            } else {
                Utilities.rotateBounds(
                    bounds, mDeviceProfile.widthPx, mDeviceProfile.heightPx,
                    4 - rotationChange
                )
            }
        }
        if (mDeviceProfile.isTaskbarPresentInApps
            && !target.willShowImeOnTarget
            && !DisplayController.isTransientTaskbar(mLauncher)
        ) {
            // Animate to above the taskbar.
            bounds.bottom -= target.contentInsets.bottom
        }
        return bounds
    }

    fun setRemoteAnimationProvider(
        animationProvider: RemoteAnimationProvider,
        cancellationSignal: CancellationSignal
    ) {
        mRemoteAnimationProvider = animationProvider
        cancellationSignal.setOnCancelListener {
            if (animationProvider === mRemoteAnimationProvider) {
                mRemoteAnimationProvider = null
            }
        }
    }

    /**
     * Content is everything on screen except the background and the floating view (if any).
     *
     * @param isAppOpening True when this is called when an app is opening.
     * False when this is called when an app is closing.
     * @param startDelay   Start delay duration.
     * @param skipAllAppsScale True if we want to avoid scaling All Apps
     */
    private fun getLauncherContentAnimator(
        isAppOpening: Boolean,
        startDelay: Int, skipAllAppsScale: Boolean
    ): Pair<AnimatorSet, Runnable> {
        val launcherAnimator = AnimatorSet()
        val endListener: Runnable
        var alphas = if (isAppOpening) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        val scales = if (isAppOpening) floatArrayOf(
            1f,
            mDeviceProfile.workspaceContentScale
        ) else floatArrayOf(mDeviceProfile.workspaceContentScale, 1f)

        // Pause expensive view updates as they can lead to layer thrashing and skipped frames.
        mLauncher.pauseExpensiveViewUpdates()
        if (mLauncher.isInState(LauncherState.ALL_APPS)) {
            // All Apps in portrait mode is full screen, so we only animate AllAppsContainerView.
            val appsView: View = mLauncher.appsView
            val startAlpha = appsView.alpha
            val startScale = LauncherAnimUtils.SCALE_PROPERTY[appsView]
            if (mDeviceProfile.isTablet) {
                // AllApps should not fade at all in tablets.
                alphas = floatArrayOf(1f, 1f)
            }
            appsView.alpha = alphas[0]
            val alpha = ObjectAnimator.ofFloat(appsView, View.ALPHA, *alphas)
            alpha.duration = CONTENT_ALPHA_DURATION.toLong()
            alpha.interpolator = Interpolators.LINEAR
            appsView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            alpha.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    appsView.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            if (!skipAllAppsScale) {
                LauncherAnimUtils.SCALE_PROPERTY[appsView] = scales[0]
                val scale =
                    ObjectAnimator.ofFloat(appsView, LauncherAnimUtils.SCALE_PROPERTY, *scales)
                scale.interpolator = Interpolators.AGGRESSIVE_EASE
                scale.duration = CONTENT_SCALE_DURATION.toLong()
                launcherAnimator.play(scale)
            }
            launcherAnimator.play(alpha)
            endListener = Runnable {
                appsView.alpha = startAlpha
                LauncherAnimUtils.SCALE_PROPERTY[appsView] = startScale
                appsView.setLayerType(View.LAYER_TYPE_NONE, null)
                mLauncher.resumeExpensiveViewUpdates()
            }
        } else if (mLauncher.isInState(LauncherState.OVERVIEW)) {
            endListener = composeViewContentAnimator(launcherAnimator, alphas, scales)
        } else {
            val viewsToAnimate: MutableList<View> = ArrayList()
            val workspace = mLauncher.workspace
            workspace.forEachVisiblePage { view: View -> viewsToAnimate.add((view as CellLayout).shortcutsAndWidgets) }

            // Do not scale hotseat as a whole when taskbar is present, and scale QSB only if it's
            // not inline.
            if (mDeviceProfile.isTaskbarPresent) {
                if (!mDeviceProfile.isQsbInline) {
                    viewsToAnimate.add(mLauncher.hotseat.qsb)
                }
            } else {
                viewsToAnimate.add(mLauncher.hotseat)
            }
            viewsToAnimate.forEach(Consumer { view: View ->
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                val scaleAnim =
                    ObjectAnimator.ofFloat(view, LauncherAnimUtils.SCALE_PROPERTY, *scales)
                        .setDuration(CONTENT_SCALE_DURATION.toLong())
                scaleAnim.interpolator = Interpolators.DEACCEL_1_5
                launcherAnimator.play(scaleAnim)
            })
            val scrimEnabled = FeatureFlags.ENABLE_SCRIM_FOR_APP_LAUNCH.get()
            if (scrimEnabled) {
                val useTaskbarColor = (mDeviceProfile.isTaskbarPresentInApps
                        && !FeatureFlags.ENABLE_TASKBAR_IN_OVERVIEW.get())
                val scrimColor =
                    if (useTaskbarColor) mLauncher.resources.getColor(R.color.taskbar_background) else Themes.getAttrColor(
                        mLauncher,
                        R.attr.overviewScrimColor
                    )
                val scrimColorTrans = ColorUtils.setAlphaComponent(scrimColor, 0)
                val colors =
                    if (isAppOpening) intArrayOf(scrimColorTrans, scrimColor) else intArrayOf(
                        scrimColor,
                        scrimColorTrans
                    )
                val scrimView = mLauncher.scrimView
                if (scrimView.background is ColorDrawable) {
                    scrimView.setBackgroundColor(colors[0])
                    val scrim = ObjectAnimator.ofArgb(
                        scrimView, LauncherAnimUtils.VIEW_BACKGROUND_COLOR,
                        *colors
                    )
                    scrim.duration = CONTENT_SCRIM_DURATION.toLong()
                    scrim.interpolator = Interpolators.DEACCEL_1_5
                    if (useTaskbarColor) {
                        // Hide the taskbar background color since it would duplicate the scrim.
                        scrim.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationStart(animation: Animator) {
                                val taskbarUIController = mLauncher.taskbarUIController
                                taskbarUIController?.forceHideBackground(true)
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                val taskbarUIController = mLauncher.taskbarUIController
                                taskbarUIController?.forceHideBackground(false)
                            }
                        })
                    }
                    launcherAnimator.play(scrim)
                }
            }
            endListener = Runnable {
                viewsToAnimate.forEach(Consumer { view: View ->
                    LauncherAnimUtils.SCALE_PROPERTY[view] = 1f
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                })
                if (scrimEnabled) {
                    mLauncher.scrimView.setBackgroundColor(Color.TRANSPARENT)
                }
                mLauncher.resumeExpensiveViewUpdates()
            }
        }
        launcherAnimator.startDelay = startDelay.toLong()
        return Pair(launcherAnimator, endListener)
    }

    /**
     * Compose recents view alpha and translation Y animation when launcher opens/closes apps.
     *
     * @param anim   the animator set to add to
     * @param alphas the alphas to animate to over time
     * @param scales the scale values to animator to over time
     * @return listener to run when the animation ends
     */
    protected fun composeViewContentAnimator(
        anim: AnimatorSet,
        alphas: FloatArray, scales: FloatArray
    ): Runnable {
        val overview = mLauncher.getOverviewPanel<RecentsView<*, *>>()
        val alpha = ObjectAnimator.ofFloat(
            overview,
            RecentsView.CONTENT_ALPHA, *alphas
        )
        alpha.duration = CONTENT_ALPHA_DURATION.toLong()
        alpha.interpolator = Interpolators.LINEAR
        anim.play(alpha)
        overview.setFreezeViewVisibility(true)
        val scaleAnim = ObjectAnimator.ofFloat(overview, LauncherAnimUtils.SCALE_PROPERTY, *scales)
        scaleAnim.interpolator = Interpolators.AGGRESSIVE_EASE
        scaleAnim.duration = CONTENT_SCALE_DURATION.toLong()
        anim.play(scaleAnim)
        return Runnable {
            overview.setFreezeViewVisibility(false)
            LauncherAnimUtils.SCALE_PROPERTY[overview] = 1f
            mLauncher.stateManager.reapplyState()
            mLauncher.resumeExpensiveViewUpdates()
        }
    }

    /**
     * @return Animator that controls the window of the opening targets from app icons.
     */
    private fun getOpeningWindowAnimators(
        v: View,
        appTargets: Array<RemoteAnimationTarget>,
        wallpaperTargets: Array<RemoteAnimationTarget>,
        nonAppTargets: Array<RemoteAnimationTarget>,
        launcherClosing: Boolean
    ): Animator {
        val rotationChange = getRotationChange(appTargets)
        val windowTargetBounds = getWindowTargetBounds(appTargets, rotationChange)
        val appTargetsAreTranslucent = areAllTargetsTranslucent(appTargets)
        val launcherIconBounds = RectF()
        val floatingView = FloatingIconView.getFloatingIconView(
            mLauncher, v,
            !appTargetsAreTranslucent, launcherIconBounds, true /* isOpening */
        )
        val crop = Rect()
        val matrix = Matrix()
        val openingTargets = RemoteAnimationTargets(
            appTargets,
            wallpaperTargets, nonAppTargets, RemoteAnimationTarget.MODE_OPENING
        )
        val surfaceApplier = SurfaceTransactionApplier(floatingView)
        openingTargets.addReleaseCheck(surfaceApplier)
        val navBarTarget = openingTargets.navBarRemoteAnimationTarget
        val dragLayerBounds = IntArray(2)
        mDragLayer.getLocationOnScreen(dragLayerBounds)
        val hasSplashScreen: Boolean
        hasSplashScreen = if (supportsSSplashScreen()) {
            val taskId = openingTargets.firstAppTargetTaskId
            val defaultParams = Pair.create(StartingWindowInfo.STARTING_WINDOW_TYPE_NONE, 0)
            val taskParams = mTaskStartParams!!.getOrDefault(taskId, defaultParams)
            mTaskStartParams!!.remove(taskId)
            taskParams.first == StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN
        } else {
            false
        }
        val prop = AnimOpenProperties(
            mLauncher.resources, mDeviceProfile,
            windowTargetBounds, launcherIconBounds, v, dragLayerBounds[0], dragLayerBounds[1],
            hasSplashScreen, floatingView.isDifferentFromAppIcon
        )
        val left = prop.cropCenterXStart - prop.cropWidthStart / 2
        val top = prop.cropCenterYStart - prop.cropHeightStart / 2
        val right = left + prop.cropWidthStart
        val bottom = top + prop.cropHeightStart
        // Set the crop here so we can calculate the corner radius below.
        crop.set(left, top, right, bottom)
        val floatingIconBounds = RectF()
        val tmpRectF = RectF()
        val tmpPos = Point()
        val animatorSet = AnimatorSet()
        val appAnimator = ValueAnimator.ofFloat(0f, 1f)
        appAnimator.duration = APP_LAUNCH_DURATION
        appAnimator.interpolator = Interpolators.LINEAR
        appAnimator.addListener(floatingView)
        appAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                val taskbarController = mLauncher.taskbarUIController
                if (taskbarController != null && taskbarController.shouldShowEdu()) {
                    // LAUNCHER_TASKBAR_EDUCATION_SHOWING is set to true here, when the education
                    // flow is about to start, to avoid a race condition with other components
                    // that would show something else to the user as soon as the app is opened.
                    Secure.putInt(
                        mLauncher.contentResolver,
                        Secure.LAUNCHER_TASKBAR_EDUCATION_SHOWING, 1
                    )
                }
            }

            override fun onAnimationEnd(animation: Animator) {
                if (v is BubbleTextView) {
                    v.setStayPressed(false)
                }
                val taskbarController = mLauncher.taskbarUIController
                taskbarController?.showEdu()
                openingTargets.release()
            }
        })
        // TODO: Should depend on icon shape
        val initialWindowRadius =
            if (QuickStepContract.supportsRoundedCornersOnWindows(mLauncher.resources)) Math.max(
                crop.width(),
                crop.height()
            ) / 2f else 0f
//        val initialWindowRadius = 0f
        val finalWindowRadius: Float =
            if (mDeviceProfile.isMultiWindowMode) 0f else QuickStepContract.getWindowCornerRadius(
                mLauncher
            )
        val finalShadowRadius: Float = if (appTargetsAreTranslucent) 0f else mMaxShadowRadius
        val listener: MultiValueUpdateListener = object : MultiValueUpdateListener() {
            var mDx = FloatProp(
                0f, prop.dX, 0f, APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )
            var mDy = FloatProp(
                0f, prop.dY, 0f, APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )
            var mAnchorPad = FloatProp(
                prop.anchorPad, 0f, 0f, APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )
            var mIconScaleToFitScreen = FloatProp(
                prop.initialAppIconScale,
                prop.finalAppIconScale, 0f, APP_LAUNCH_DURATION.toFloat(), mOpeningInterpolator
            )

            var mIconAlpha = FloatProp(
                prop.iconAlphaStart,
                0f,
                APP_LAUNCH_ALPHA_START_DELAY.toFloat(),
                APP_LAUNCH_ALPHA_DURATION.toFloat(),
                Interpolators.LINEAR
            )

            // TODO: Should depend on scale
            var mWindowRadius = FloatProp(
                initialWindowRadius, finalWindowRadius, 0f,
                APP_LAUNCH_DURATION.toFloat(), mOpeningInterpolator
            )
            var mShadowRadius = FloatProp(
                0f, finalShadowRadius, 0f,
                APP_LAUNCH_DURATION.toFloat(), mOpeningInterpolator
            )
            var mCropRectWidth = FloatProp(
                prop.cropWidthStart.toFloat(), prop.cropWidthEnd.toFloat(), 0f,
                APP_LAUNCH_DURATION.toFloat(), mOpeningInterpolator
            )
            var mCropRectHeight = FloatProp(
                prop.cropHeightStart.toFloat(), prop.cropHeightEnd.toFloat(), 0f,
                APP_LAUNCH_DURATION.toFloat(), mOpeningInterpolator
            )
            var mNavFadeOut = FloatProp(
                1f, 0f, 0f, ANIMATION_NAV_FADE_OUT_DURATION.toFloat(),
                NAV_FADE_OUT_INTERPOLATOR
            )
            var mNavFadeIn = FloatProp(
                0f, 1f, ANIMATION_DELAY_NAV_FADE_IN.toFloat(),
                ANIMATION_NAV_FADE_IN_DURATION.toFloat(), NAV_FADE_IN_INTERPOLATOR
            )

            var mWindowScale = FloatProp(
                prop.initialWindowScale,
                prop.finalWindowScale, 0f, APP_LAUNCH_DURATION.toFloat(), mOpeningInterpolator
            )


            override fun onUpdate(percent: Float, initOnly: Boolean) {
                // calculate clip height with anchor
                crop.bottom = mCropRectHeight.value.toInt()
//                crop.bottom = 0
                // calculate app scale with anchor
                val scale = mWindowScale.value
                val scaledCropWidth = mCropRectWidth.value
                val scaledCropHeight = mCropRectHeight.value

                val centerAnchorOffsetX = windowTargetBounds.width() * scale / 2
                val centerAnchorOffsetY = windowTargetBounds.height() * scale / 2

                // windows position
                val initX = launcherIconBounds.centerX()
                val initY = launcherIconBounds.centerY()

                val windowTransX0 =
                    initX + mDx.value - centerAnchorOffsetX + dragLayerBounds[0].toFloat()
                val windowTransY0 =
                    initY + mDy.value - centerAnchorOffsetY + dragLayerBounds[1].toFloat() + mAnchorPad.value

                val scaleY = mCropRectHeight.value / mCropRectWidth.value

                tmpRectF.set(launcherIconBounds)
                Utilities.scaleRectFAboutCenter(
                    tmpRectF,
                    mIconScaleToFitScreen.value,
                    mIconScaleToFitScreen.value * scaleY
                )
                tmpRectF.offset(dragLayerBounds[0].toFloat(), dragLayerBounds[1].toFloat())
                val shift = tmpRectF.top - windowTransY0
                tmpRectF.offset(mDx.value, -shift)
                Log.d("ongsalt", "${tmpRectF.top} $windowTransY0")

                // transform icon
                floatingIconBounds.set(tmpRectF)

                if (initOnly) {
                    // For the init pass, we want full alpha since the window is not yet ready.
                    floatingView.update(
                        1f, 255, floatingIconBounds, percent, 0f,
                        mWindowRadius.value * scale, true /* isOpening */
                    )
                    return
                }

                // Transaction
                val transaction = SurfaceTransaction()

                for (i in appTargets.indices.reversed()) {
                    val target = appTargets[i]
                    val builder = transaction.forSurface(target.leash)
                    if (target.mode == RemoteAnimationTarget.MODE_OPENING) {
                        matrix.setScale(scale, scale)
                        if (rotationChange == 1) {
                            matrix.postTranslate(
                                windowTransY0,
                                mDeviceProfile.widthPx - (windowTransX0 + scaledCropWidth)
                            )
                        } else if (rotationChange == 2) {
                            matrix.postTranslate(
                                mDeviceProfile.widthPx - (windowTransX0 + scaledCropWidth),
                                mDeviceProfile.heightPx - (windowTransY0 + scaledCropHeight)
                            )
                        } else if (rotationChange == 3) {
                            matrix.postTranslate(
                                mDeviceProfile.heightPx - (windowTransY0 + scaledCropHeight),
                                windowTransX0
                            )
                        } else {
                            matrix.postTranslate(windowTransX0, windowTransY0)
                        }
                        floatingView.update(
                            mIconAlpha.value, 255, floatingIconBounds, percent, 0f,
                            mWindowRadius.value * scale, true /* isOpening */
                        )
                        builder.setMatrix(matrix)
                            .setWindowCrop(crop)
                            .setAlpha(1f - mIconAlpha.value)
                            .setCornerRadius(mWindowRadius.value)
                            .setShadowRadius(mShadowRadius.value)
                    } else if (target.mode == RemoteAnimationTarget.MODE_CLOSING) {
                        if (Utilities.ATLEAST_R && target.localBounds != null) {
                            tmpPos[target.localBounds.left] = target.localBounds.top
                        } else {
                            tmpPos[target.position.x] = target.position.y
                        }
                        val crop =
                            Rect(if (Utilities.ATLEAST_R) target.screenSpaceBounds else target.sourceContainerBounds)
                        crop.offsetTo(0, 0)
                        if (rotationChange % 2 == 1) {
                            var tmp = crop.right
                            crop.right = crop.bottom
                            crop.bottom = tmp
                            tmp = tmpPos.x
                            tmpPos.x = tmpPos.y
                            tmpPos.y = tmp
                        }
                        matrix.setTranslate(tmpPos.x.toFloat(), tmpPos.y.toFloat())
                        builder.setMatrix(matrix)
                            .setWindowCrop(crop)
                            .setAlpha(1f)
                    }
                }
                if (navBarTarget != null) {
                    val navBuilder = transaction.forSurface(navBarTarget.leash)
                    if (mNavFadeIn.value > mNavFadeIn.startValue) {
                        matrix.setScale(scale, scale)
                        matrix.postTranslate(windowTransX0, windowTransY0)
                        navBuilder.setMatrix(matrix)
                            .setWindowCrop(crop)
                            .setAlpha(mNavFadeIn.value)
                    } else {
                        navBuilder.setAlpha(mNavFadeOut.value)
                    }
                }
                surfaceApplier.scheduleApply(transaction)
            }

        }
        appAnimator.addUpdateListener(listener)
        // Since we added a start delay, call update here to init the FloatingIconView properly.
        listener.onUpdate(0f, true /* initOnly */)

        // If app targets are translucent, do not animate the background as it causes a visible
        // flicker when it resets itself at the end of its animation.
        if (appTargetsAreTranslucent || !launcherClosing) {
            animatorSet.play(appAnimator)
        } else {
            animatorSet.playTogether(appAnimator, backgroundAnimator)
        }
        return animatorSet
    }

    private fun getOpeningWindowAnimatorsForWidget(
        v: LauncherAppWidgetHostView,
        appTargets: Array<RemoteAnimationTarget>,
        wallpaperTargets: Array<RemoteAnimationTarget>,
        nonAppTargets: Array<RemoteAnimationTarget>, launcherClosing: Boolean
    ): Animator {
        val windowTargetBounds = getWindowTargetBounds(appTargets, getRotationChange(appTargets))
        val appTargetsAreTranslucent = areAllTargetsTranslucent(appTargets)
        val widgetBackgroundBounds = RectF()
        val appWindowCrop = Rect()
        val matrix = Matrix()
        val openingTargets = RemoteAnimationTargets(
            appTargets,
            wallpaperTargets, nonAppTargets, RemoteAnimationTarget.MODE_OPENING
        )
        val openingTarget = openingTargets.firstAppTarget
        var fallbackBackgroundColor = 0
        if (openingTarget != null && supportsSSplashScreen()) {
            fallbackBackgroundColor =
                if (mTaskStartParams!!.containsKey(openingTarget.taskId)) mTaskStartParams!![openingTarget.taskId]!!.second else 0
            mTaskStartParams!!.remove(openingTarget.taskId)
        }
        if (fallbackBackgroundColor == 0) {
            fallbackBackgroundColor =
                FloatingWidgetView.getDefaultBackgroundColor(mLauncher, openingTarget)
        }
        val finalWindowRadius: Float =
            if (mDeviceProfile.isMultiWindowMode) 0f else QuickStepContract.getWindowCornerRadius(
                mLauncher
            )
        val floatingView = FloatingWidgetView.getFloatingWidgetView(
            mLauncher,
            v, widgetBackgroundBounds,
            Size(windowTargetBounds.width(), windowTargetBounds.height()),
            finalWindowRadius, appTargetsAreTranslucent, fallbackBackgroundColor
        )
        val initialWindowRadius: Float =
            if (QuickStepContract.supportsRoundedCornersOnWindows(mLauncher.resources)) floatingView.initialCornerRadius else 0f
        val surfaceApplier = SurfaceTransactionApplier(floatingView)
        openingTargets.addReleaseCheck(surfaceApplier)
        val navBarTarget = openingTargets.navBarRemoteAnimationTarget
        val animatorSet = AnimatorSet()
        val appAnimator = ValueAnimator.ofFloat(0f, 1f)
        appAnimator.duration = APP_LAUNCH_DURATION
        appAnimator.interpolator = Interpolators.LINEAR
        appAnimator.addListener(floatingView)
        appAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                openingTargets.release()
            }
        })
        floatingView.setFastFinishRunnable { animatorSet.end() }
        appAnimator.addUpdateListener(object : MultiValueUpdateListener() {
            var mAppWindowScale = 1f
            val mWidgetForegroundAlpha = FloatProp(
                1f /* start */,
                0f /* end */,
                0f /* delay */,
                (
                        WIDGET_CROSSFADE_DURATION_MILLIS / 2 /* duration */).toFloat(),
                Interpolators.LINEAR
            )
            val mWidgetFallbackBackgroundAlpha = FloatProp(
                0f /* start */,
                1f /* end */, 0f /* delay */, 75f /* duration */, Interpolators.LINEAR
            )
            val mPreviewAlpha = FloatProp(
                0f /* start */,
                1f /* end */,
                (
                        WIDGET_CROSSFADE_DURATION_MILLIS / 2 /* delay */).toFloat(),
                (
                        WIDGET_CROSSFADE_DURATION_MILLIS / 2 /* duration */).toFloat(),
                Interpolators.LINEAR
            )
            val mWindowRadius = FloatProp(
                initialWindowRadius, finalWindowRadius,
                0f /* start */, APP_LAUNCH_DURATION.toFloat(), mOpeningInterpolator
            )
            val mCornerRadiusProgress = FloatProp(
                0f, 1f, 0f, APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )

            // Window & widget background positioning bounds
            val mDx = FloatProp(
                widgetBackgroundBounds.centerX(),
                windowTargetBounds.centerX().toFloat(),
                0f /* delay */,
                APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )
            val mDy = FloatProp(
                widgetBackgroundBounds.centerY(),
                windowTargetBounds.centerY().toFloat(),
                0f /* delay */,
                APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )
            val mWidth = FloatProp(
                widgetBackgroundBounds.width(),
                windowTargetBounds.width().toFloat(), 0f /* delay */, APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )
            val mHeight = FloatProp(
                widgetBackgroundBounds.height(),
                windowTargetBounds.height().toFloat(),
                0f /* delay */,
                APP_LAUNCH_DURATION.toFloat(),
                mOpeningInterpolator
            )
            val mNavFadeOut = FloatProp(
                1f, 0f, 0f, ANIMATION_NAV_FADE_OUT_DURATION.toFloat(),
                NAV_FADE_OUT_INTERPOLATOR
            )
            val mNavFadeIn = FloatProp(
                0f, 1f, ANIMATION_DELAY_NAV_FADE_IN.toFloat(),
                ANIMATION_NAV_FADE_IN_DURATION.toFloat(), NAV_FADE_IN_INTERPOLATOR
            )

            override fun onUpdate(percent: Float, initOnly: Boolean) {
                widgetBackgroundBounds[mDx.value - mWidth.value / 2f, mDy.value - mHeight.value / 2f, mDx.value + mWidth.value / 2f] =
                    mDy.value + mHeight.value / 2f
                // Set app window scaling factor to match widget background width
                mAppWindowScale = widgetBackgroundBounds.width() / windowTargetBounds.width()
                // Crop scaled app window to match widget
                appWindowCrop[0, 0, Math.round(windowTargetBounds.width().toFloat())] =
                    Math.round(widgetBackgroundBounds.height() / mAppWindowScale)
                matrix.setTranslate(widgetBackgroundBounds.left, widgetBackgroundBounds.top)
                matrix.postScale(
                    mAppWindowScale, mAppWindowScale, widgetBackgroundBounds.left,
                    widgetBackgroundBounds.top
                )
                val transaction = SurfaceTransaction()
                val floatingViewAlpha: Float =
                    if (appTargetsAreTranslucent) 1 - mPreviewAlpha.value else 1f
                for (i in appTargets.indices.reversed()) {
                    val target = appTargets[i]
                    val builder = transaction.forSurface(target.leash)
                    if (target.mode == RemoteAnimationTarget.MODE_OPENING) {
                        floatingView.update(
                            widgetBackgroundBounds, floatingViewAlpha,
                            mWidgetForegroundAlpha.value, mWidgetFallbackBackgroundAlpha.value,
                            mCornerRadiusProgress.value
                        )
                        builder.setMatrix(matrix)
                            .setWindowCrop(appWindowCrop)
                            .setAlpha(mPreviewAlpha.value)
                            .setCornerRadius(mWindowRadius.value / mAppWindowScale)
                    }
                }
                if (navBarTarget != null) {
                    val navBuilder = transaction.forSurface(navBarTarget.leash)
                    if (mNavFadeIn.value > mNavFadeIn.startValue) {
                        navBuilder.setMatrix(matrix)
                            .setWindowCrop(appWindowCrop)
                            .setAlpha(mNavFadeIn.value)
                    } else {
                        navBuilder.setAlpha(mNavFadeOut.value)
                    }
                }
                surfaceApplier.scheduleApply(transaction)
            }
        })

        // If app targets are translucent, do not animate the background as it causes a visible
        // flicker when it resets itself at the end of its animation.
        if (appTargetsAreTranslucent || !launcherClosing) {
            animatorSet.play(appAnimator)
        } else {
            animatorSet.playTogether(appAnimator, backgroundAnimator)
        }
        return animatorSet
    }

    private val backgroundAnimator: ObjectAnimator
        /**
         * Returns animator that controls depth/blur of the background.
         */
        private get() {
            // When launching an app from overview that doesn't map to a task, we still want to just
            // blur the wallpaper instead of the launcher surface as well
            val allowBlurringLauncher = (mLauncher.stateManager.state !== LauncherState.OVERVIEW
                    && BlurUtils.supportsBlursOnWindows())
            val depthController = MyDepthController(mLauncher)
            val backgroundRadiusAnim: ObjectAnimator = ObjectAnimator.ofFloat(
                depthController.stateDepth,
                MultiPropertyFactory.MULTI_PROPERTY_VALUE,
                LauncherState.BACKGROUND_APP.getDepth(mLauncher)
            )
                .setDuration(APP_LAUNCH_DURATION)
            if (allowBlurringLauncher) {
                // Create a temporary effect layer, that lives on top of launcher, so we can apply
                // the blur to it. The EffectLayer will be fullscreen, which will help with caching
                // optimizations on the SurfaceFlinger side:
                // - Results would be able to be cached as a texture
                // - There won't be texture allocation overhead, because EffectLayers don't have
                //   buffers
                val viewRootImpl = mLauncher.dragLayer.viewRootImpl
                val parent = viewRootImpl?.surfaceControl
                val dimLayer = SurfaceControl.Builder()
                    .setName("Blur layer")
                    .setParent(parent)
                    .setOpaque(false)
                    .setHidden(false)
                    .setEffectLayer()
                    .build()
                backgroundRadiusAnim.addListener(AnimatorListeners.forEndCallback(Runnable {
                    SurfaceControl.Transaction().remove(dimLayer).apply()
                }))
            }
            return backgroundRadiusAnim
        }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    fun registerRemoteAnimations() {
        if (FeatureFlags.SEPARATE_RECENTS_ACTIVITY.get()) {
            return
        }
        if (hasControlRemoteAppTransitionPermission()) {
            mWallpaperOpenRunner = createWallpaperOpenRunner(false /* fromUnlock */)
            val definition = RemoteAnimationDefinition()
            definition.addRemoteAnimation(
                WindowManager.TRANSIT_OLD_WALLPAPER_OPEN,
                WindowConfiguration.ACTIVITY_TYPE_STANDARD,
                RemoteAnimationAdapter(
                    LauncherAnimationRunner(
                        mHandler, mWallpaperOpenRunner,
                        false /* startAtFrontOfQueue */
                    ),
                    CLOSING_TRANSITION_DURATION_MS.toLong(), 0 /* statusBarTransitionDelay */
                )
            )
            if (FeatureFlags.KEYGUARD_ANIMATION.get()) {
                mKeyguardGoingAwayRunner = createWallpaperOpenRunner(true /* fromUnlock */)
                definition.addRemoteAnimation(
                    WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                    RemoteAnimationAdapter(
                        LauncherAnimationRunner(
                            mHandler, mKeyguardGoingAwayRunner,
                            true /* startAtFrontOfQueue */
                        ),
                        CLOSING_TRANSITION_DURATION_MS.toLong(), 0 /* statusBarTransitionDelay */
                    )
                )
            }
            mLauncher.registerRemoteAnimations(definition)
        }
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    fun registerRemoteTransitions() {
        if (FeatureFlags.SEPARATE_RECENTS_ACTIVITY.get()) {
            return
        }
        if (!Utilities.ATLEAST_T) return
        if (hasControlRemoteAppTransitionPermission()) {
            mWallpaperOpenTransitionRunner = createWallpaperOpenRunner(false /* fromUnlock */)
            mLauncherOpenTransition = RemoteTransition(
                LauncherAnimationRunner(
                    mHandler, mWallpaperOpenTransitionRunner,
                    false /* startAtFrontOfQueue */
                ).toRemoteTransition(),
                mLauncher.iApplicationThread
            )
            val homeCheck = TransitionFilter()
            // No need to handle the transition that also dismisses keyguard.
            homeCheck.mNotFlags = WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY
            homeCheck.mRequirements = arrayOf(
                Requirement(),
                Requirement()
            )
            homeCheck.mRequirements[0].mActivityType = WindowConfiguration.ACTIVITY_TYPE_HOME
            homeCheck.mRequirements[0].mTopActivity = mLauncher.componentName
            homeCheck.mRequirements[0].mModes =
                intArrayOf(WindowManager.TRANSIT_OPEN, WindowManager.TRANSIT_TO_FRONT)
            homeCheck.mRequirements[0].mOrder = TransitionFilter.CONTAINER_ORDER_TOP
            homeCheck.mRequirements[1].mActivityType = WindowConfiguration.ACTIVITY_TYPE_STANDARD
            homeCheck.mRequirements[1].mModes =
                intArrayOf(WindowManager.TRANSIT_CLOSE, WindowManager.TRANSIT_TO_BACK)
            SystemUiProxy.INSTANCE[mLauncher]
                .registerRemoteTransition(mLauncherOpenTransition, homeCheck)
        }
        if (mBackAnimationController != null) {
            mBackAnimationController!!.registerBackCallbacks(mHandler)
        }
    }

    fun onActivityDestroyed() {
        unregisterRemoteAnimations()
        unregisterRemoteTransitions()
        mStartingWindowListener.setTransitionManager(null)
        SystemUiProxy.INSTANCE[mLauncher].setStartingWindowListener(null)
    }

    private fun unregisterRemoteAnimations() {
        if (FeatureFlags.SEPARATE_RECENTS_ACTIVITY.get()) {
            return
        }
        if (hasControlRemoteAppTransitionPermission()) {
            if (Utilities.ATLEAST_R) {
                mLauncher.unregisterRemoteAnimations()
            }

            // Also clear strong references to the runners registered with the remote animation
            // definition so we don't have to wait for the system gc
            mWallpaperOpenRunner = null
            mAppLaunchRunner = null
            mKeyguardGoingAwayRunner = null
        }
    }

    private fun unregisterRemoteTransitions() {
        if (FeatureFlags.SEPARATE_RECENTS_ACTIVITY.get()) {
            return
        }
        if (hasControlRemoteAppTransitionPermission()) {
            if (mLauncherOpenTransition == null) return
            SystemUiProxy.INSTANCE[mLauncher].unregisterRemoteTransition(
                mLauncherOpenTransition
            )
            mLauncherOpenTransition = null
            mWallpaperOpenTransitionRunner = null
        }
        if (mBackAnimationController != null) {
            mBackAnimationController!!.unregisterBackCallbacks()
            mBackAnimationController = null
        }
    }

    private fun launcherIsATargetWithMode(
        targets: Array<RemoteAnimationTarget>,
        mode: Int
    ): Boolean {
        for (target in targets) {
            if (!Utilities.ATLEAST_S) {
                return if (target.mode == mode && target.taskId == mLauncher.taskId) {
                    true
                } else {
                    false
                }
            }
            if (// Compare component name instead of task-id because transitions will promote
            // the target up to the root task while getTaskId returns the leaf.
                target.mode == mode && target.taskInfo != null && target.taskInfo.topActivity != null && target.taskInfo.topActivity == mLauncher.componentName) {
                return true
            }
        }
        return false
    }

    private fun hasMultipleTargetsWithMode(
        targets: Array<RemoteAnimationTarget>,
        mode: Int
    ): Boolean {
        var numTargets = 0
        for (target in targets) {
            if (target.mode == mode) {
                numTargets++
            }
            if (numTargets > 1) {
                return true
            }
        }
        return false
    }

    /**
     * @return Runner that plays when user goes to Launcher
     * ie. pressing home, swiping up from nav bar.
     */
    fun createWallpaperOpenRunner(fromUnlock: Boolean): RemoteAnimationFactory {
        return WallpaperOpenLauncherAnimationRunner(mHandler, fromUnlock)
    }

    /**
     * Animator that controls the transformations of the windows when unlocking the device.
     */
    private fun getUnlockWindowAnimator(
        appTargets: Array<RemoteAnimationTarget>,
        wallpaperTargets: Array<RemoteAnimationTarget>
    ): Animator {
        val surfaceApplier = SurfaceTransactionApplier(mDragLayer)
        val unlockAnimator = ValueAnimator.ofFloat(0f, 1f)
        unlockAnimator.duration = CLOSING_TRANSITION_DURATION_MS.toLong()
        val cornerRadius: Float =
            if (mDeviceProfile.isMultiWindowMode) 0f else QuickStepContract.getWindowCornerRadius(
                mLauncher
            )
        unlockAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                val transaction = SurfaceTransaction()
                for (i in appTargets.indices.reversed()) {
                    val target = appTargets[i]
                    transaction.forSurface(target.leash)
                        .setAlpha(1f)
                        .setWindowCrop(if (Utilities.ATLEAST_R) target.screenSpaceBounds else target.sourceContainerBounds)
                        .setCornerRadius(cornerRadius)
                }
                surfaceApplier.scheduleApply(transaction)
            }
        })
        return unlockAnimator
    }

    /**
     * Returns view on launcher that corresponds to the closing app in the list of app targets
     */
    private fun findLauncherView(appTargets: Array<RemoteAnimationTarget>): View? {
        for (appTarget in appTargets) {
            if (appTarget.mode == RemoteAnimationTarget.MODE_CLOSING) {
                val launcherView = findLauncherView(appTarget)
                if (launcherView != null) {
                    return launcherView
                }
            }
        }
        return null
    }

    /**
     * Returns view on launcher that corresponds to the {@param runningTaskTarget}.
     */
    private fun findLauncherView(runningTaskTarget: RemoteAnimationTarget?): View? {
        if (!Utilities.ATLEAST_S) {
            return null
        }
        if (runningTaskTarget == null || runningTaskTarget.taskInfo == null) {
            return null
        }
        val taskInfoActivities = arrayOf(
            runningTaskTarget.taskInfo.baseActivity,
            runningTaskTarget.taskInfo.origActivity,
            runningTaskTarget.taskInfo.realActivity,
            runningTaskTarget.taskInfo.topActivity
        )
        var packageName: String? = null
        for (component in taskInfoActivities) {
            if (component != null && component.packageName != null) {
                packageName = component.packageName
                break
            }
        }
        if (packageName == null) {
            return null
        }

        // Find the associated item info for the launch cookie (if available), note that predicted
        // apps actually have an id of -1, so use another default id here
        val launchCookies =
            if (runningTaskTarget.taskInfo.launchCookies == null) ArrayList() else runningTaskTarget.taskInfo.launchCookies
        var launchCookieItemId = ItemInfo.NO_MATCHING_ID
        for (cookie in launchCookies) {
            val itemId = ObjectWrapper.unwrap<Int>(cookie)
            if (itemId != null) {
                launchCookieItemId = itemId
                break
            }
        }
        return mLauncher.getFirstMatchForAppClose(
            launchCookieItemId, packageName,
            UserHandle.of(runningTaskTarget.taskInfo.userId), true /* supportsAllAppsState */
        )
    }

    private val defaultWindowTargetRect: RectF
        private get() {
            val recentsView = mLauncher.getOverviewPanel<RecentsView<*, *>>()
            val orientationHandler = recentsView.pagedOrientationHandler
            val dp = mLauncher.deviceProfile
            val halfIconSize = dp.iconSizePx / 2
            val primaryDimension = orientationHandler
                .getPrimaryValue(dp.availableWidthPx, dp.availableHeightPx).toFloat()
            val secondaryDimension = orientationHandler
                .getSecondaryValue(dp.availableWidthPx, dp.availableHeightPx).toFloat()
            val targetX = primaryDimension / 2f
            val targetY = secondaryDimension - dp.hotseatBarSizePx
            return RectF(
                targetX - halfIconSize, targetY - halfIconSize,
                targetX + halfIconSize, targetY + halfIconSize
            )
        }

    /**
     * Closing animator that animates the window into its final location on the workspace.
     */
    private fun getClosingWindowAnimators(
        animation: AnimatorSet,
        targets: Array<RemoteAnimationTarget>, launcherView: View?, velocityPxPerS: PointF,
        closingWindowStartRect: RectF, startWindowCornerRadius: Float
    ): RectFSpringAnim {
        var floatingIconView: FloatingIconView? = null
        var floatingWidget: FloatingWidgetView? = null
        val targetRect = RectF()
        var runningTaskTarget: RemoteAnimationTarget? = null
        var isTransluscent = false
        for (target in targets) {
            if (target.mode == RemoteAnimationTarget.MODE_CLOSING) {
                runningTaskTarget = target
                isTransluscent = runningTaskTarget.isTranslucent
                break
            }
        }

        // Get floating view and target rect.
        if (launcherView is LauncherAppWidgetHostView) {
            val windowSize = Size(
                mDeviceProfile.availableWidthPx,
                mDeviceProfile.availableHeightPx
            )
            val fallbackBackgroundColor =
                FloatingWidgetView.getDefaultBackgroundColor(mLauncher, runningTaskTarget)
            floatingWidget = FloatingWidgetView.getFloatingWidgetView(
                mLauncher,
                launcherView as LauncherAppWidgetHostView?, targetRect, windowSize,
                if (mDeviceProfile.isMultiWindowMode) 0f else QuickStepContract.getWindowCornerRadius(
                    mLauncher
                ),
                isTransluscent, fallbackBackgroundColor
            )
        } else if (launcherView != null) {
            floatingIconView = FloatingIconView.getFloatingIconView(
                mLauncher, launcherView,
                true /* hideOriginal */, targetRect, false /* isOpening */
            )
        } else {
            targetRect.set(defaultWindowTargetRect)
        }
        val anim = RectFSpringAnim(
            closingWindowStartRect, targetRect, mLauncher,
            mDeviceProfile
        )

        // Hook up floating views to the closing window animators.
        val rotationChange = getRotationChange(targets)
        val windowTargetBounds = getWindowTargetBounds(targets, rotationChange)
        if (floatingIconView != null) {
            anim.addAnimatorListener(floatingIconView)
            floatingIconView.setOnTargetChangeListener(Runnable { anim.onTargetPositionChanged() })
            floatingIconView.setFastFinishRunnable(Runnable { anim.end() })
            val finalFloatingIconView: FloatingIconView = floatingIconView

            // We want the window alpha to be 0 once this threshold is met, so that the
            // FolderIconView can be seen morphing into the icon shape.
            val windowAlphaThreshold = 1f - FloatingIconView.SHAPE_PROGRESS_DURATION
            val runner: RectFSpringAnim.OnUpdateListener = object : SpringAnimRunner(
                targets, targetRect,
                windowTargetBounds, startWindowCornerRadius
            ) {
                override fun onUpdate(currentRectF: RectF, progress: Float) {
                    finalFloatingIconView.update(
                        1f, 255 /* fgAlpha */, currentRectF, progress,
                        windowAlphaThreshold, getCornerRadius(progress), false
                    )
                    super.onUpdate(currentRectF, progress)
                }
            }
            anim.addOnUpdateListener(runner)
        } else if (floatingWidget != null) {
            anim.addAnimatorListener(floatingWidget)
            floatingWidget.setOnTargetChangeListener(Runnable { anim.onTargetPositionChanged() })
            floatingWidget.setFastFinishRunnable(Runnable { anim.end() })
            val floatingWidgetAlpha = (if (isTransluscent) 0f else 1).toFloat()
            val finalFloatingWidget: FloatingWidgetView = floatingWidget
            val runner: RectFSpringAnim.OnUpdateListener = object : SpringAnimRunner(
                targets, targetRect,
                windowTargetBounds, startWindowCornerRadius
            ) {
                override fun onUpdate(currentRectF: RectF, progress: Float) {
                    val fallbackBackgroundAlpha = 1 - Utilities.mapBoundToRange(
                        progress,
                        0.8f,
                        1f,
                        0f,
                        1f,
                        Interpolators.EXAGGERATED_EASE
                    )
                    val foregroundAlpha = Utilities.mapBoundToRange(
                        progress,
                        0.5f,
                        1f,
                        0f,
                        1f,
                        Interpolators.EXAGGERATED_EASE
                    )
                    finalFloatingWidget.update(
                        currentRectF, floatingWidgetAlpha, foregroundAlpha,
                        fallbackBackgroundAlpha, 1 - progress
                    )
                    super.onUpdate(currentRectF, progress)
                }
            }
            anim.addOnUpdateListener(runner)
        } else {
            // If no floating icon or widget is present, animate the to the default window
            // target rect.
            anim.addOnUpdateListener(
                SpringAnimRunner(
                    targets, targetRect, windowTargetBounds, startWindowCornerRadius
                )
            )
        }

        // Use a fixed velocity to start the animation.
        animation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                anim.start(mLauncher, mDeviceProfile, velocityPxPerS)
            }
        })
        return anim
    }

    /**
     * Closing window animator that moves the window down and offscreen.
     */
    private fun getFallbackClosingWindowAnimators(appTargets: Array<RemoteAnimationTarget>): Animator {
        val rotationChange = getRotationChange(appTargets)
        val surfaceApplier = SurfaceTransactionApplier(mDragLayer)
        val matrix = Matrix()
        val tmpPos = Point()
        val tmpRect = Rect()
        val closingAnimator = ValueAnimator.ofFloat(0f, 1f)
        val duration = CLOSING_TRANSITION_DURATION_MS
        val windowCornerRadius: Float =
            if (mDeviceProfile.isMultiWindowMode) 0f else QuickStepContract.getWindowCornerRadius(
                mLauncher
            )
        val startShadowRadius: Float =
            if (areAllTargetsTranslucent(appTargets)) 0f else mMaxShadowRadius
        closingAnimator.duration = duration.toLong()
        closingAnimator.addUpdateListener(object : MultiValueUpdateListener() {
            var mDy = FloatProp(
                0f,
                mClosingWindowTransY,
                0f,
                duration.toFloat(),
                Interpolators.DEACCEL_1_7
            )
            var mScale = FloatProp(1f, 1f, 0f, duration.toFloat(), Interpolators.DEACCEL_1_7)
            var mAlpha = FloatProp(1f, 0f, 25f, 125f, Interpolators.LINEAR)
            var mShadowRadius = FloatProp(
                startShadowRadius, 0f, 0f, duration.toFloat(),
                Interpolators.DEACCEL_1_7
            )

            override fun onUpdate(percent: Float, initOnly: Boolean) {
                val transaction = SurfaceTransaction()
                for (i in appTargets.indices.reversed()) {
                    val target = appTargets[i]
                    val builder = transaction.forSurface(target.leash)
                    if (Utilities.ATLEAST_R && target.localBounds != null) {
                        tmpPos[target.localBounds.left] = target.localBounds.top
                    } else {
                        tmpPos[target.position.x] = target.position.y
                    }
                    val crop =
                        Rect(if (Utilities.ATLEAST_R) target.screenSpaceBounds else target.sourceContainerBounds)
                    crop.offsetTo(0, 0)
                    if (target.mode == RemoteAnimationTarget.MODE_CLOSING) {
                        tmpRect.set(if (Utilities.ATLEAST_R) target.screenSpaceBounds else target.sourceContainerBounds)
                        if (rotationChange % 2 != 0) {
                            val right = crop.right
                            crop.right = crop.bottom
                            crop.bottom = right
                        }
                        matrix.setScale(
                            mScale.value, mScale.value,
                            tmpRect.centerX().toFloat(),
                            tmpRect.centerY().toFloat()
                        )
                        matrix.postTranslate(0f, mDy.value)
                        matrix.postTranslate(tmpPos.x.toFloat(), tmpPos.y.toFloat())
                        builder.setMatrix(matrix)
                            .setWindowCrop(crop)
                            .setAlpha(mAlpha.value)
                            .setCornerRadius(windowCornerRadius)
                            .setShadowRadius(mShadowRadius.value)
                    } else if (target.mode == RemoteAnimationTarget.MODE_OPENING) {
                        matrix.setTranslate(tmpPos.x.toFloat(), tmpPos.y.toFloat())
                        builder.setMatrix(matrix)
                            .setWindowCrop(crop)
                            .setAlpha(1f)
                    }
                }
                surfaceApplier.scheduleApply(transaction)
            }
        })
        return closingAnimator
    }

    private fun supportsSSplashScreen(): Boolean {
        return (hasControlRemoteAppTransitionPermission()
                && Utilities.ATLEAST_S
                && ENABLE_SHELL_STARTING_SURFACE)
    }

    /**
     * Returns true if we have permission to control remote app transisions
     */
    fun hasControlRemoteAppTransitionPermission(): Boolean {
        return (mLauncher.checkSelfPermission(CONTROL_REMOTE_APP_TRANSITION_PERMISSION)
                == PackageManager.PERMISSION_GRANTED)
    }

    private fun addCujInstrumentation(anim: Animator, cuj: Int) {
        anim.addListener(object : AnimationSuccessListener() {
            override fun onAnimationStart(animation: Animator) {
                mDragLayer.viewTreeObserver.addOnDrawListener(
                    object : OnDrawListener {
                        var mHandled = false
                        override fun onDraw() {
                            if (mHandled) {
                                return
                            }
                            mHandled = true
                            InteractionJankMonitorWrapper.begin(mDragLayer, cuj)
                            mDragLayer.post {
                                mDragLayer.viewTreeObserver.removeOnDrawListener(
                                    this
                                )
                            }
                        }
                    })
                super.onAnimationStart(animation)
            }

            override fun onAnimationCancel(animation: Animator) {
                super.onAnimationCancel(animation)
                InteractionJankMonitorWrapper.cancel(cuj)
            }

            override fun onAnimationSuccess(animator: Animator) {
                InteractionJankMonitorWrapper.end(cuj)
            }
        })
    }

    /**
     * Creates the [RectFSpringAnim] and [AnimatorSet] required to animate
     * the transition.
     */
    fun createWallpaperOpenAnimations(
        appTargets: Array<RemoteAnimationTarget>,
        wallpaperTargets: Array<RemoteAnimationTarget>,
        fromUnlock: Boolean,
        startRect: RectF,
        startWindowCornerRadius: Float
    ): Pair<RectFSpringAnim?, AnimatorSet?> {
        var anim: AnimatorSet? = null
        var rectFSpringAnim: RectFSpringAnim? = null
        val provider = mRemoteAnimationProvider
        if (provider != null) {
            anim = provider.createWindowAnimation(appTargets, wallpaperTargets)
        }
        if (anim == null) {
            anim = AnimatorSet()
            val launcherIsForceInvisibleOrOpening = (mLauncher.isForceInvisible
                    || launcherIsATargetWithMode(appTargets, RemoteAnimationTarget.MODE_OPENING))
            val launcherView = findLauncherView(appTargets)
            val playFallBackAnimation = ((launcherView == null
                    && launcherIsForceInvisibleOrOpening)
                    || mLauncher.workspace.isOverlayShown
                    || hasMultipleTargetsWithMode(appTargets, RemoteAnimationTarget.MODE_CLOSING))
            var playWorkspaceReveal = true
            var skipAllAppsScale = false
            if (fromUnlock) {
                anim.play(getUnlockWindowAnimator(appTargets, wallpaperTargets))
            } else if (FeatureFlags.ENABLE_BACK_SWIPE_HOME_ANIMATION.get()
                && !playFallBackAnimation
            ) {
                // Use a fixed velocity to start the animation.
                val velocityPxPerS = DynamicResource.provider(mLauncher)
                    .getDimension(R.dimen.unlock_staggered_velocity_dp_per_s)
                val velocity = PointF(0f, -velocityPxPerS)
                rectFSpringAnim = getClosingWindowAnimators(
                    anim, appTargets, launcherView, velocity, startRect,
                    startWindowCornerRadius
                )
                if (!mLauncher.isInState(LauncherState.ALL_APPS)) {
                    anim.play(
                        StaggeredWorkspaceAnim(
                            mLauncher, velocity.y,
                            true /* animateOverviewScrim */, launcherView
                        ).animators
                    )
                    if (!areAllTargetsTranslucent(appTargets)) {
                        anim.play(
                            ObjectAnimator.ofFloat(
                                mLauncher.depthController.stateDepth,
                                MultiPropertyFactory.MULTI_PROPERTY_VALUE,
                                LauncherState.BACKGROUND_APP.getDepth(mLauncher),
                                LauncherState.NORMAL.getDepth(mLauncher)
                            )
                        )
                    }

                    // We play StaggeredWorkspaceAnim as a part of the closing window animation.
                    playWorkspaceReveal = false
                } else {
                    // Skip scaling all apps, otherwise FloatingIconView will get wrong
                    // layout bounds.
                    skipAllAppsScale = true
                }
            } else {
                anim.play(getFallbackClosingWindowAnimators(appTargets))
            }

            // Normally, we run the launcher content animation when we are transitioning
            // home, but if home is already visible, then we don't want to animate the
            // contents of launcher unless we know that we are animating home as a result
            // of the home button press with quickstep, which will result in launcher being
            // started on touch down, prior to the animation home (and won't be in the
            // targets list because it is already visible). In that case, we force
            // invisibility on touch down, and only reset it after the animation to home
            // is initialized.
            if (launcherIsForceInvisibleOrOpening) {
                addCujInstrumentation(
                    anim, InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_HOME
                )
                // Only register the content animation for cancellation when state changes
                mLauncher.stateManager.setCurrentAnimation(anim)
                if (mLauncher.isInState(LauncherState.ALL_APPS)) {
                    val contentAnimator = getLauncherContentAnimator(
                        false, LAUNCHER_RESUME_START_DELAY,
                        skipAllAppsScale
                    )
                    anim.play(contentAnimator.first)
                    anim.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            contentAnimator.second.run()
                        }
                    })
                } else {
                    if (playWorkspaceReveal) {
                        anim.play(WorkspaceRevealAnim(mLauncher, false).animators)
                    }
                }
            }
        }
        return Pair(rectFSpringAnim, anim)
    }

    /**
     * Remote animation runner for animation from the app to Launcher, including recents.
     */
    protected inner class WallpaperOpenLauncherAnimationRunner(
        private val mHandler: Handler,
        private val mFromUnlock: Boolean
    ) : RemoteAnimationFactory {
        override fun onCreateAnimation(
            transit: Int,
            appTargets: Array<RemoteAnimationTarget>,
            wallpaperTargets: Array<RemoteAnimationTarget>,
            nonAppTargets: Array<RemoteAnimationTarget>,
            result: AnimationResult
        ) {
            if (mLauncher.isDestroyed) {
                val anim = AnimatorSet()
                anim.play(getFallbackClosingWindowAnimators(appTargets))
                result.setAnimation(anim, mLauncher.applicationContext)
                return
            }
            if (mLauncher.hasSomeInvisibleFlag(BaseActivity.PENDING_INVISIBLE_BY_WALLPAPER_ANIMATION)) {
                mLauncher.addForceInvisibleFlag(BaseActivity.INVISIBLE_BY_PENDING_FLAGS)
                mLauncher.stateManager.moveToRestState()
            }
            val windowTargetBounds =
                RectF(getWindowTargetBounds(appTargets, getRotationChange(appTargets)))
            val pair = createWallpaperOpenAnimations(
                appTargets, wallpaperTargets, mFromUnlock, windowTargetBounds,
                QuickStepContract.getWindowCornerRadius(mLauncher)
            )
            mLauncher.clearForceInvisibleFlag(BaseActivity.INVISIBLE_ALL)
            result.setAnimation(pair.second, mLauncher)
        }
    }

    /**
     * Remote animation runner for animation to launch an app.
     */
    private inner class AppLaunchAnimationRunner internal constructor(
        private val mV: View,
        private val mOnEndCallback: RunnableList
    ) : RemoteAnimationFactory {
        override fun onCreateAnimation(
            transit: Int,
            appTargets: Array<RemoteAnimationTarget>,
            wallpaperTargets: Array<RemoteAnimationTarget>,
            nonAppTargets: Array<RemoteAnimationTarget>,
            result: AnimationResult
        ) {
            val anim = AnimatorSet()
            val launcherClosing =
                launcherIsATargetWithMode(appTargets, RemoteAnimationTarget.MODE_CLOSING)
            val launchingFromWidget = mV is LauncherAppWidgetHostView
            val launchingFromRecents = isLaunchingFromRecents(mV, appTargets)
            val skipFirstFrame: Boolean
            skipFirstFrame = if (launchingFromWidget) {
                composeWidgetLaunchAnimator(
                    anim, mV as LauncherAppWidgetHostView, appTargets,
                    wallpaperTargets, nonAppTargets, launcherClosing
                )
                addCujInstrumentation(
                    anim, InteractionJankMonitorWrapper.CUJ_APP_LAUNCH_FROM_WIDGET
                )
                true
            } else if (launchingFromRecents) {
                composeRecentsLaunchAnimator(
                    anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                    launcherClosing
                )
                addCujInstrumentation(
                    anim, InteractionJankMonitorWrapper.CUJ_APP_LAUNCH_FROM_RECENTS
                )
                true
            } else {
                composeIconLaunchAnimator(
                    anim, mV, appTargets, wallpaperTargets, nonAppTargets,
                    launcherClosing
                )
                addCujInstrumentation(anim, InteractionJankMonitorWrapper.CUJ_APP_LAUNCH_FROM_ICON)
                false
            }
            if (launcherClosing) {
                anim.addListener(mForceInvisibleListener)
            }
            result.setAnimation(
                anim, mLauncher, { mOnEndCallback.executeAllAndDestroy() },
                skipFirstFrame
            )
        }

        override fun onAnimationCancelled() {
            mOnEndCallback.executeAllAndDestroy()
        }
    }

    /**
     * Class that holds all the variables for the app open animation.
     */
    internal class AnimOpenProperties(
        r: Resources?, dp: DeviceProfile?, windowTargetBounds: Rect,
        launcherIconBounds: RectF, view: View, dragLayerLeft: Int, dragLayerTop: Int,
        hasSplashScreen: Boolean, hasDifferentAppIcon: Boolean
    ) {
        val cropCenterXStart: Int
        val cropCenterYStart: Int
        val cropWidthStart: Int
        val cropHeightStart: Int
        val cropCenterXEnd: Int
        val cropCenterYEnd: Int
        val cropWidthEnd: Int
        val cropHeightEnd: Int
        val dX: Float
        val dY: Float
        val initialAppIconScale: Float
        val finalAppIconScale: Float
        val initialAppIconScaleX: Float
        val finalAppIconScaleX: Float
        val initialAppIconScaleY: Float
        val finalAppIconScaleY: Float
        val initialWindowScale: Float
        val finalWindowScale: Float
        val anchorPad: Float
        val iconAlphaStart: Float

        init {
            // Scale the app icon to take up the entire screen. This simplifies the math when
            // animating the app window position / scale.
            val smallestWindowSize =
                Math.min(windowTargetBounds.height(), windowTargetBounds.width()).toFloat()
            val largestIconSize =
                Math.max(launcherIconBounds.height(), launcherIconBounds.width()).toFloat()
            val maxScaleX = smallestWindowSize / launcherIconBounds.width()
            val maxScaleY = smallestWindowSize / launcherIconBounds.height()
            var iconStartScale = 1f
            if (view is BubbleTextView && view.getParent() !is DeepShortcutView) {
                val dr: Drawable = view.icon
                if (dr is FastBitmapDrawable) {
                    iconStartScale = dr.animatedScale
                }
            }
            initialAppIconScaleX = iconStartScale
            initialAppIconScaleY = iconStartScale

            finalAppIconScaleX = windowTargetBounds.width() / launcherIconBounds.width()
            finalAppIconScaleY = windowTargetBounds.height() / launcherIconBounds.height()

            initialAppIconScale = iconStartScale
            finalAppIconScale = Math.max(maxScaleX, maxScaleY)

            initialWindowScale = largestIconSize / smallestWindowSize
            finalWindowScale = 1f

            anchorPad =
                (windowTargetBounds.height() * initialWindowScale - launcherIconBounds.height()) / 2

            // Animate the app icon to the center of the window bounds in screen coordinates.
            val centerX = (windowTargetBounds.centerX() - dragLayerLeft).toFloat()
            val centerY = (windowTargetBounds.centerY() - dragLayerTop).toFloat()
            dX = centerX - launcherIconBounds.centerX()
            dY = centerY - launcherIconBounds.centerY()
            iconAlphaStart = if (hasSplashScreen && !hasDifferentAppIcon) 0f else 1f
            val windowIconSize = ResourceUtils.getDimenByName(
                "starting_surface_icon_size",
                r, 108
            )
            cropCenterXStart = windowTargetBounds.centerX()
            cropCenterYStart = windowTargetBounds.centerX()
            cropWidthStart = windowTargetBounds.width()
            cropHeightStart = windowTargetBounds.width()
            cropWidthEnd = windowTargetBounds.width()
            cropHeightEnd = windowTargetBounds.height()
            cropCenterXEnd = windowTargetBounds.centerX()
            cropCenterYEnd = windowTargetBounds.centerY()

//            cropCenterXStart = windowTargetBounds.centerX()
//            cropCenterYStart = windowTargetBounds.centerY()
//            cropWidthStart = windowTargetBounds.width()
//            cropHeightStart = windowTargetBounds.height()
//            cropWidthEnd = windowTargetBounds.width()
//            cropHeightEnd = windowTargetBounds.height()
//            cropCenterXEnd = windowTargetBounds.centerX()
//            cropCenterYEnd = windowTargetBounds.centerY()
        }
    }

    private class StartingWindowListener : IStartingWindowListener.Stub() {
        private var mTransitionManager: QuickstepTransitionManager? = null
        fun setTransitionManager(transitionManager: QuickstepTransitionManager?) {
            mTransitionManager = transitionManager
        }

        override fun onTaskLaunching(taskId: Int, supportedType: Int, color: Int) {
            mTransitionManager!!.mTaskStartParams!![taskId] = Pair.create(supportedType, color)
        }
    }

    /**
     * RectFSpringAnim update listener to be used for app to home animation.
     */
    private open inner class SpringAnimRunner internal constructor(
        private val mAppTargets: Array<RemoteAnimationTarget>, targetRect: RectF,
        windowTargetBounds: Rect?, private val mStartRadius: Float
    ) : RectFSpringAnim.OnUpdateListener {
        private val mMatrix = Matrix()
        private val mTmpPos = Point()
        private val mCurrentRect = Rect()
        private val mEndRadius: Float
        private val mSurfaceApplier: SurfaceTransactionApplier
        private val mWindowTargetBounds = Rect()
        private val mTmpRect = Rect()

        init {
            mEndRadius = Math.max(1f, targetRect.width()) / 2f
            mSurfaceApplier = SurfaceTransactionApplier(mDragLayer)
            mWindowTargetBounds.set(windowTargetBounds)
        }

        fun getCornerRadius(progress: Float): Float {
            return Utilities.mapRange(progress, mStartRadius, mEndRadius)
        }

        override fun onUpdate(currentRectF: RectF, progress: Float) {
            val transaction = SurfaceTransaction()
            for (i in mAppTargets.indices.reversed()) {
                val target = mAppTargets[i]
                val builder = transaction.forSurface(target.leash)
                if (Utilities.ATLEAST_R && target.localBounds != null) {
                    mTmpPos[target.localBounds.left] = target.localBounds.top
                } else {
                    mTmpPos[target.position.x] = target.position.y
                }
                if (target.mode == RemoteAnimationTarget.MODE_CLOSING) {
                    currentRectF.round(mCurrentRect)

                    // Scale the target window to match the currentRectF.
                    val scale: Float

                    // We need to infer the crop (we crop the window to match the currentRectF).
                    if (mWindowTargetBounds.height() > mWindowTargetBounds.width()) {
                        scale = Math.min(1f, currentRectF.width() / mWindowTargetBounds.width())
                        val unscaledHeight = (mCurrentRect.height() * (1f / scale)).toInt()
                        val croppedHeight = mWindowTargetBounds.height() - unscaledHeight
                        mTmpRect[0, 0, mWindowTargetBounds.width()] =
                            mWindowTargetBounds.height() - croppedHeight
                    } else {
                        scale = Math.min(1f, currentRectF.height() / mWindowTargetBounds.height())
                        val unscaledWidth = (mCurrentRect.width() * (1f / scale)).toInt()
                        val croppedWidth = mWindowTargetBounds.width() - unscaledWidth
                        mTmpRect[0, 0, mWindowTargetBounds.width() - croppedWidth] =
                            mWindowTargetBounds.height()
                    }

                    // Match size and position of currentRect.
                    mMatrix.setScale(scale, scale)
                    mMatrix.postTranslate(mCurrentRect.left.toFloat(), mCurrentRect.top.toFloat())
                    builder.setMatrix(mMatrix)
                        .setWindowCrop(mTmpRect)
                        .setAlpha(getWindowAlpha(progress))
                        .setCornerRadius(getCornerRadius(progress) / scale)
                } else if (target.mode == RemoteAnimationTarget.MODE_OPENING) {
                    mMatrix.setTranslate(mTmpPos.x.toFloat(), mTmpPos.y.toFloat())
                    builder.setMatrix(mMatrix)
                        .setAlpha(1f)
                }
            }
            mSurfaceApplier.scheduleApply(transaction)
        }

        protected fun getWindowAlpha(progress: Float): Float {
            // Alpha interpolates between [1, 0] between progress values [start, end]
            val start = 0f
            val end = 0.85f
            if (progress <= start) {
                return 1f
            }
            return if (progress >= end) {
                0f
            } else Utilities.mapToRange(
                progress,
                start,
                end,
                1f,
                0f,
                Interpolators.ACCEL_1_5
            )
        }
    }

    private class MyDepthController internal constructor(l: Launcher?) : DepthController(l) {
        init {
            setCrossWindowBlursEnabled(
                Utilities.ATLEAST_S &&
                        CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled
            )
        }

        public override fun setSurface(surface: SurfaceControl) {
            super.setSurface(surface)
        }
    }

    companion object {
        private val ENABLE_SHELL_STARTING_SURFACE =
            SystemProperties.getBoolean("persist.debug.shell_starting_surface", true)

        /** Duration of status bar animations.  */
        const val STATUS_BAR_TRANSITION_DURATION = 120

        /**
         * Since our animations decelerate heavily when finishing, we want to start status bar
         * animations x ms before the ending.
         */
        const val STATUS_BAR_TRANSITION_PRE_DELAY = 96
        private const val CONTROL_REMOTE_APP_TRANSITION_PERMISSION =
            "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS"
        private const val APP_LAUNCH_DURATION: Long = 600
        private const val APP_LAUNCH_ALPHA_DURATION: Long = 50
        private const val APP_LAUNCH_ALPHA_START_DELAY: Long = 80
        const val ANIMATION_NAV_FADE_IN_DURATION = 266
        const val ANIMATION_NAV_FADE_OUT_DURATION = 133
        const val ANIMATION_DELAY_NAV_FADE_IN = APP_LAUNCH_DURATION - ANIMATION_NAV_FADE_IN_DURATION

        @JvmField
        val NAV_FADE_IN_INTERPOLATOR: Interpolator = PathInterpolator(0f, 0f, 0f, 1f)

        @JvmField
        val NAV_FADE_OUT_INTERPOLATOR: Interpolator = PathInterpolator(0.2f, 0f, 1f, 1f)
        const val RECENTS_LAUNCH_DURATION = 336
        private const val LAUNCHER_RESUME_START_DELAY = 100
        private const val CLOSING_TRANSITION_DURATION_MS = 250
        const val SPLIT_LAUNCH_DURATION = 370
        const val SPLIT_DIVIDER_ANIM_DURATION = 100
        const val CONTENT_ALPHA_DURATION = 217
        const val TRANSIENT_TASKBAR_TRANSITION_DURATION = 417
        const val TASKBAR_TO_APP_DURATION = 600

        // TODO(b/236145847): Tune TASKBAR_TO_HOME_DURATION to 383 after conflict with unlock animation
        // is solved.
        const val TASKBAR_TO_HOME_DURATION = 300
        protected const val CONTENT_SCALE_DURATION = 350
        protected const val CONTENT_SCRIM_DURATION = 350
        private const val MAX_NUM_TASKS = 5

        // Cross-fade duration between App Widget and App
        private const val WIDGET_CROSSFADE_DURATION_MILLIS = 125
        private fun getRotationChange(appTargets: Array<RemoteAnimationTarget>): Int {
            var rotationChange = 0
            try {
                for (target in appTargets) {
                    if (Math.abs(target.rotationChange) > Math.abs(rotationChange)) {
                        rotationChange = target.rotationChange
                    }
                }
            } catch (throwable: Throwable) {
                // not android13-qpr2
            }
            return rotationChange
        }
    }
}