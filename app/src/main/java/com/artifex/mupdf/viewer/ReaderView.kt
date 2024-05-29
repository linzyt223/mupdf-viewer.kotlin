package com.artifex.mupdf.viewer

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.SparseArray
import android.view.*
import android.widget.AdapterView
import android.widget.Scroller
import com.artifex.mupdf.fitz.Link
import java.util.*

open class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AdapterView<PageAdapter>(context, attrs, defStyle),
    GestureDetector.OnGestureListener,
    ScaleGestureDetector.OnScaleGestureListener,
    Runnable
{

    private var mContext: Context = context
    private var mLinksEnabled = false
    private var tapDisabled = false
    private var tapPageMargin: Int

    private var mAdapter: PageAdapter? = null
    var mCurrent = 0 // Adapter's index for the current view
    private var mResetLayout = false
    private val mChildViews = SparseArray<View>(3) // Shadows the children of the adapter view but with more sensible indexing
    private val mViewCache = LinkedList<View>()
    private var mUserInteracting = false // Whether the user is interacting
    private var mScaling = false // Whether the user is currently pinch zooming
    private var mScale = 1.0f
    private var mXScroll = 0 // Scroll amounts recorded from events.
    private var mYScroll = 0 // and then accounted for in onLayout
    private val mGestureDetector: GestureDetector
    private val mScaleGestureDetector: ScaleGestureDetector
    private val mScroller: Scroller
    private val mStepper: Stepper
    private var mScrollerLastX = 0
    private var mScrollerLastY = 0
    private var mLastScaleFocusX = 0f
    private var mLastScaleFocusY = 0f

    val mHistory: Stack<Int> = Stack()

    init {
        mGestureDetector = GestureDetector(context, this)
        mScaleGestureDetector = ScaleGestureDetector(context, this)
        mScroller = Scroller(context)
        mStepper = Stepper(this, this)

        // Get the screen size etc to customise tap margins.
        // We calculate the size of 1 inch of the screen for tapping.
        // On some devices the dpi values returned are wrong, so we
        // sanity check it: we first restrict it so that we are never
        // less than 100 pixels (the smallest Android device screen
        // dimension I've seen is 480 pixels or so). Then we check
        // to ensure we are never more than 1/5 of the screen width.
        val dm = DisplayMetrics()
        val wm = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getMetrics(dm)
        tapPageMargin = dm.xdpi.toInt()
        if (tapPageMargin < 100) tapPageMargin = 100
        if (tapPageMargin > dm.widthPixels / 5) tapPageMargin = dm.widthPixels / 5
    }

    fun popHistory(): Boolean {
        return if (mHistory.empty()) {
            false
        } else {
            setDisplayedViewIndex(mHistory.pop())
            true
        }
    }

    fun pushHistory() {
        mHistory.push(mCurrent)
    }

    fun getDisplayedViewIndex(): Int {
        return mCurrent
    }

    fun setDisplayedViewIndex(i: Int) {
        if (0 <= i && i < (mAdapter?.count ?: 0)) {
            onMoveOffChild(mCurrent)
            mCurrent = i
            onMoveToChild(i)
            mResetLayout = true
            requestLayout()
        }
    }

    fun moveToNext() {
        val v = mChildViews[mCurrent + 1]
        if (v != null) slideViewOntoScreen(v)
    }

    fun moveToPrevious() {
        val v = mChildViews[mCurrent - 1]
        if (v != null) slideViewOntoScreen(v)
    }

    private fun smartAdvanceAmount(screenHeight: Int, max: Int): Int {
        var advance = (screenHeight * 0.9 + 0.5).toInt()
        val leftOver = max % advance
        val steps = max / advance
        if (leftOver == 0) {
            // We'll make it exactly. No adjustment
        } else if (leftOver.toFloat() / steps <= screenHeight * 0.05) {
            // We can adjust up by less than 5% to make it exact.
            advance += (leftOver.toFloat() / steps + 0.5).toInt()
        } else {
            val overshoot = advance - leftOver
            if (overshoot.toFloat() / steps <= screenHeight * 0.1) {
                // We can adjust down by less than 10% to make it exact.
                advance -= (overshoot.toFloat() / steps + 0.5).toInt()
            }
        }
        if (advance > max) advance = max
        return advance
    }

    fun smartMoveForwards() {
        val v = mChildViews[mCurrent] ?: return

        val screenWidth = width
        val screenHeight = height

        val remainingX = mScroller.finalX - mScroller.currX
        val remainingY = mScroller.finalY - mScroller.currY

        val top = -(v.top + mYScroll + remainingY)
        val right = screenWidth - (v.left + mXScroll + remainingX)
        val bottom = screenHeight + top
        val docWidth = v.measuredWidth
        val docHeight = v.measuredHeight

        var xOffset: Int
        var yOffset: Int
        if (bottom >= docHeight) {
            if (right + screenWidth > docWidth) {
                val nv = mChildViews[mCurrent + 1] ?: return
                val nextTop = -(nv.top + mYScroll + remainingY)
                val nextLeft = -(nv.left + mXScroll + remainingX)
                val nextDocWidth = nv.measuredWidth
                val nextDocHeight = nv.measuredHeight

                yOffset = if (nextDocHeight < screenHeight) (nextDocHeight - screenHeight) / 2 else 0

                xOffset = if (nextDocWidth < screenWidth) {
                    (nextDocWidth - screenWidth) / 2
                } else {
                    right % screenWidth
                }

                if (xOffset + screenWidth > nextDocWidth) xOffset = nextDocWidth - screenWidth
                xOffset -= nextLeft
                yOffset -= nextTop
            } else {
                xOffset = screenWidth
                yOffset = screenHeight - bottom
            }
        } else {
            xOffset = 0
            yOffset = smartAdvanceAmount(screenHeight, docHeight - bottom)
        }
        mScrollerLastX = 0
        mScrollerLastY = 0
        mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400)
        mStepper.prod()
    }

    private fun smartMoveBackwards() {
        val v = mChildViews[mCurrent] ?: return

        val screenWidth = width
        val screenHeight = height

        val remainingX = mScroller.finalX - mScroller.currX
        val remainingY = mScroller.finalY - mScroller.currY

        val left = -(v.left + mXScroll + remainingX)
        val top = -(v.top + mYScroll + remainingY)
        val docHeight = v.measuredHeight

        var xOffset: Int
        var yOffset: Int
        if (top <= 0) {
            if (left < screenWidth) {
                val pv = mChildViews[mCurrent - 1] ?: return
                val prevDocWidth = pv.measuredWidth
                val prevDocHeight = pv.measuredHeight

                yOffset = if (prevDocHeight < screenHeight) (prevDocHeight - screenHeight) / 2 else 0

                val prevLeft = -(pv.left + mXScroll)
                val prevTop = -(pv.top + mYScroll)
                if (prevDocWidth < screenWidth) {
                    xOffset = (prevDocWidth - screenWidth) / 2
                } else {
                    xOffset = if (left > 0) left % screenWidth else 0
                    if (xOffset + screenWidth > prevDocWidth) xOffset = prevDocWidth - screenWidth
                    while (xOffset + screenWidth * 2 < prevDocWidth) xOffset += screenWidth
                }
                xOffset -= prevLeft
                yOffset -= prevTop - prevDocHeight + screenHeight
            } else {
                xOffset = -screenWidth
                yOffset = docHeight - screenHeight + top
            }
        } else {
            xOffset = 0
            yOffset = -smartAdvanceAmount(screenHeight, top)
        }
        mScrollerLastX = 0
        mScrollerLastY = 0
        mScroller.startScroll(0, 0, remainingX - xOffset, remainingY - yOffset, 400)
        mStepper.prod()
    }

    fun resetupChildren() {
        for (i in 0 until mChildViews.size()) {
            onChildSetup(mChildViews.keyAt(i), mChildViews.valueAt(i))
        }
    }

    fun applyToChildren(mapper: ViewMapper) {
        for (i in 0 until mChildViews.size()) {
            mapper.applyToView(mChildViews.valueAt(i))
        }
    }

    fun refresh() {
        mResetLayout = true
        mScale = 1.0f
        mXScroll = 0
        mYScroll = 0

        mAdapter?.refresh()
        for (i in 0 until mChildViews.size()) {
            val v = mChildViews.valueAt(i)
            onNotInUse(v)
            removeViewInLayout(v)
        }
        mChildViews.clear()
        mViewCache.clear()

        requestLayout()
    }

    fun getView(i: Int): View? {
        return mChildViews[i]
    }

    fun getDisplayedView(): View? {
        return mChildViews[mCurrent]
    }

    override fun run() {
        if (!mScroller.isFinished) {
            mScroller.computeScrollOffset()
            val x = mScroller.currX
            val y = mScroller.currY
            mXScroll += x - mScrollerLastX
            mYScroll += y - mScrollerLastY
            mScrollerLastX = x
            mScrollerLastY = y
            requestLayout()
            mStepper.prod()
        } else if (!mUserInteracting) {
            val v = mChildViews[mCurrent]
            if (v != null) postSettle(v)
        }
    }

    override fun onDown(arg0: MotionEvent): Boolean {
        mScroller.forceFinished(true)
        return true
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        if (mScaling) return true

        val v = mChildViews[mCurrent]
        if (v != null) {
            val bounds = getScrollBounds(v)
            when (directionOfTravel(velocityX, velocityY)) {
                MOVING_LEFT -> {
                    if (HORIZONTAL_SCROLLING && bounds.left >= 0) {
                        val vl = mChildViews[mCurrent + 1]
                        if (vl != null) {
                            slideViewOntoScreen(vl)
                            return true
                        }
                    }
                }
                MOVING_UP -> {
                    if (!HORIZONTAL_SCROLLING && bounds.top >= 0) {
                        val vl = mChildViews[mCurrent + 1]
                        if (vl != null) {
                            slideViewOntoScreen(vl)
                            return true
                        }
                    }
                }
                MOVING_RIGHT -> {
                    if (HORIZONTAL_SCROLLING && bounds.right <= 0) {
                        val vr = mChildViews[mCurrent - 1]
                        if (vr != null) {
                            slideViewOntoScreen(vr)
                            return true
                        }
                    }
                }
                MOVING_DOWN -> {
                    if (!HORIZONTAL_SCROLLING && bounds.bottom <= 0) {
                        val vr = mChildViews[mCurrent - 1]
                        if (vr != null) {
                            slideViewOntoScreen(vr)
                            return true
                        }
                    }
                }
            }
            mScrollerLastX = 0
            mScrollerLastY = 0

            val expandedBounds = Rect(bounds)
            expandedBounds.inset(-FLING_MARGIN, -FLING_MARGIN)

            if (withinBoundsInDirectionOfTravel(bounds, velocityX, velocityY)
                && expandedBounds.contains(0, 0)
            ) {
                mScroller.fling(
                    0, 0, velocityX.toInt(), velocityY.toInt(),
                    bounds.left, bounds.right, bounds.top, bounds.bottom
                )
                mStepper.prod()
            }
        }

        return true
    }

    override fun onLongPress(e: MotionEvent) {}

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        val pageView = getDisplayedView() as PageView?
        if (!tapDisabled) onDocMotion()
        if (!mScaling) {
            mXScroll -= distanceX.toInt()
            mYScroll -= distanceY.toInt()
            requestLayout()
        }
        return true
    }

    override fun onShowPress(e: MotionEvent) {}

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val previousScale = mScale
        mScale = Math.min(Math.max(mScale * detector.scaleFactor, MIN_SCALE), MAX_SCALE)

        val factor = mScale / previousScale

        val v = mChildViews[mCurrent]
        if (v != null) {
            val currentFocusX = detector.focusX
            val currentFocusY = detector.focusY
            val viewFocusX = currentFocusX.toInt() - (v.left + mXScroll)
            val viewFocusY = currentFocusY.toInt() - (v.top + mYScroll)
            mXScroll += viewFocusX - (viewFocusX * factor).toInt()
            mYScroll += viewFocusY - (viewFocusY * factor).toInt()

            if (mLastScaleFocusX >= 0) mXScroll += (currentFocusX - mLastScaleFocusX).toInt()
            if (mLastScaleFocusY >= 0) mYScroll += (currentFocusY - mLastScaleFocusY).toInt()

            mLastScaleFocusX = currentFocusX
            mLastScaleFocusY = currentFocusY
            requestLayout()
        }

        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        tapDisabled = true
        mScaling = true
        mXScroll = 0
        mYScroll = 0
        mLastScaleFocusX = -1f
        mLastScaleFocusY = -1f
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        mScaling = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action and event.actionMasked == MotionEvent.ACTION_DOWN) {
            tapDisabled = false
        }

        mScaleGestureDetector.onTouchEvent(event)
        mGestureDetector.onTouchEvent(event)

        if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_DOWN) {
            mUserInteracting = true
        }
        if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_UP) {
            mUserInteracting = false

            val v = mChildViews[mCurrent]
            if (v != null) {
                if (mScroller.isFinished) {
                    slideViewOntoScreen(v)
                }

                if (mScroller.isFinished) {
                    postSettle(v)
                }
            }
        }

        requestLayout()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val n = childCount
        for (i in 0 until n) measureView(getChildAt(i))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        try {
            onLayout2(changed, left, top, right, bottom)
        } catch (e: OutOfMemoryError) {
            println("Out of memory during layout")
        }
    }

    private fun onLayout2(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (isInEditMode) return

        var cv = mChildViews[mCurrent]
        var cvOffset: Point?

        if (!mResetLayout) {
            if (cv != null) {
                var move: Boolean
                cvOffset = subScreenSizeOffset(cv)
                if (HORIZONTAL_SCROLLING) move = cv.left + cv.measuredWidth + cvOffset.x + GAP / 2 + mXScroll < width / 2
                else move = cv.top + cv.measuredHeight + cvOffset.y + GAP / 2 + mYScroll < height / 2
                if (move && mCurrent + 1 < mAdapter!!.count) {
                    postUnsettle(cv)
                    mStepper.prod()
                    onMoveOffChild(mCurrent)
                    mCurrent++
                    onMoveToChild(mCurrent)
                }

                if (HORIZONTAL_SCROLLING) move = cv.left - cvOffset.x - GAP / 2 + mXScroll >= width / 2
                else move = cv.top - cvOffset.y - GAP / 2 + mYScroll >= height / 2
                if (move && mCurrent > 0) {
                    postUnsettle(cv)
                    mStepper.prod()
                    onMoveOffChild(mCurrent)
                    mCurrent--
                    onMoveToChild(mCurrent)
                }
            }

            val numChildren = mChildViews.size()
            val childIndices = IntArray(numChildren)
            for (i in 0 until numChildren) childIndices[i] = mChildViews.keyAt(i)

            for (i in 0 until numChildren) {
                val ai = childIndices[i]
                if (ai < mCurrent - 1 || ai > mCurrent + 1) {
                    val v = mChildViews[ai]
                    onNotInUse(v)
                    mViewCache.add(v)
                    removeViewInLayout(v)
                    mChildViews.remove(ai)
                }
            }
        } else {
            mResetLayout = false
            mXScroll = 0
            mYScroll = 0

            val numChildren = mChildViews.size()
            for (i in 0 until numChildren) {
                val v = mChildViews.valueAt(i)
                onNotInUse(v)
                mViewCache.add(v)
                removeViewInLayout(v)
            }
            mChildViews.clear()
            mStepper.prod()
        }

        var cvLeft: Int
        var cvRight: Int
        var cvTop: Int
        var cvBottom: Int
        val notPresent = mChildViews[mCurrent] == null
        cv = getOrCreateChild(mCurrent)
        cvOffset = subScreenSizeOffset(cv)
        if (notPresent) {
            cvLeft = cvOffset.x
            cvTop = cvOffset.y
        } else {
            cvLeft = cv.left + mXScroll
            cvTop = cv.top + mYScroll
        }
        mXScroll = 0
        mYScroll = 0
        cvRight = cvLeft + cv.measuredWidth
        cvBottom = cvTop + cv.measuredHeight

        if (!mUserInteracting && mScroller.isFinished) {
            val corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom))
            cvRight += corr.x
            cvLeft += corr.x
            cvTop += corr.y
            cvBottom += corr.y
        } else if (HORIZONTAL_SCROLLING && cv.measuredHeight <= height) {
            val corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom))
            cvTop += corr.y
            cvBottom += corr.y
        } else if (!HORIZONTAL_SCROLLING && cv.measuredWidth <= width) {
            val corr = getCorrection(getScrollBounds(cvLeft, cvTop, cvRight, cvBottom))
            cvRight += corr.x
            cvLeft += corr.x
        }

        cv.layout(cvLeft, cvTop, cvRight, cvBottom)

        if (mCurrent > 0) {
            val lv = getOrCreateChild(mCurrent - 1)
            val leftOffset = subScreenSizeOffset(lv)
            if (HORIZONTAL_SCROLLING) {
                val gap = leftOffset.x + GAP + cvOffset.x
                lv.layout(
                    cvLeft - lv.measuredWidth - gap,
                    (cvBottom + cvTop - lv.measuredHeight) / 2,
                    cvLeft - gap,
                    (cvBottom + cvTop + lv.measuredHeight) / 2
                )
            } else {
                val gap = leftOffset.y + GAP + cvOffset.y
                lv.layout(
                    (cvLeft + cvRight - lv.measuredWidth) / 2,
                    cvTop - lv.measuredHeight - gap,
                    (cvLeft + cvRight + lv.measuredWidth) / 2,
                    cvTop - gap
                )
            }
        }

        if (mCurrent + 1 < mAdapter!!.count) {
            val rv = getOrCreateChild(mCurrent + 1)
            val rightOffset = subScreenSizeOffset(rv)
            if (HORIZONTAL_SCROLLING) {
                val gap = cvOffset.x + GAP + rightOffset.x
                rv.layout(
                    cvRight + gap,
                    (cvBottom + cvTop - rv.measuredHeight) / 2,
                    cvRight + rv.measuredWidth + gap,
                    (cvBottom + cvTop + rv.measuredHeight) / 2
                )
            } else {
                val gap = cvOffset.y + GAP + rightOffset.y
                rv.layout(
                    (cvLeft + cvRight - rv.measuredWidth) / 2,
                    cvBottom + gap,
                    (cvLeft + cvRight + rv.measuredWidth) / 2,
                    cvBottom + gap + rv.measuredHeight
                )
            }
        }

        invalidate()
    }

    override fun getAdapter(): PageAdapter? {
        return mAdapter
    }

    override fun getSelectedView(): View? {
        return null
    }

    override fun setAdapter(adapter: PageAdapter?) {
        if (mAdapter != null && mAdapter != adapter) {
            mAdapter?.releaseBitmaps()
        }
        mAdapter = adapter
        requestLayout()
    }

    override fun setSelection(arg0: Int) {
        throw UnsupportedOperationException(context.getString(R.string.not_supported))
    }

    private fun getCached(): View? {
        return if (mViewCache.size == 0) null else mViewCache.removeFirst()
    }

    private fun getOrCreateChild(i: Int): View {
        var v = mChildViews[i]
        if (v == null) {
            v = mAdapter!!.getView(i, getCached(), this)
            addAndMeasureChild(i, v)
            onChildSetup(i, v)
        }
        return v
    }

    private fun addAndMeasureChild(i: Int, v: View) {
        var params = v.layoutParams
        if (params == null) {
            params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addViewInLayout(v, 0, params, true)
        mChildViews.append(i, v) // Record the view against its adapter index
        measureView(v)
    }

    private fun measureView(v: View) {
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)

        val scale = Math.min(
            width.toFloat() / v.measuredWidth.toFloat(),
            height.toFloat() / v.measuredHeight.toFloat()
        )
        v.measure(
            View.MeasureSpec.EXACTLY or (v.measuredWidth * scale * mScale).toInt(),
            View.MeasureSpec.EXACTLY or (v.measuredHeight * scale * mScale).toInt()
        )
    }

    private fun getScrollBounds(left: Int, top: Int, right: Int, bottom: Int): Rect {
        var xmin = width - right
        var xmax = -left
        var ymin = height - bottom
        var ymax = -top

        if (xmin > xmax) {
            val mid = (xmin + xmax) / 2
            xmin = mid
            xmax = mid
        }
        if (ymin > ymax) {
            val mid = (ymin + ymax) / 2
            ymin = mid
            ymax = mid
        }

        return Rect(xmin, ymin, xmax, ymax)
    }


    private fun getScrollBounds(v: View): Rect {
        return getScrollBounds(
            v.left + mXScroll,
            v.top + mYScroll,
            v.left + v.measuredWidth + mXScroll,
            v.top + v.measuredHeight + mYScroll
        )
    }

    private fun getCorrection(bounds: Rect): Point {
        return Point(
            Math.min(Math.max(0, bounds.left), bounds.right),
            Math.min(Math.max(0, bounds.top), bounds.bottom)
        )
    }

    private fun postSettle(v: View) {
        post {
            onSettle(v)
        }
    }

    private fun postUnsettle(v: View) {
        post {
            onUnsettle(v)
        }
    }

    private fun slideViewOntoScreen(v: View) {
        val corr = getCorrection(getScrollBounds(v))
        if (corr.x != 0 || corr.y != 0) {
            mScrollerLastX = 0
            mScrollerLastY = 0
            mScroller.startScroll(0, 0, corr.x, corr.y, 400)
            mStepper.prod()
        }
    }

    private fun subScreenSizeOffset(v: View): Point {
        return Point(
            Math.max((width - v.measuredWidth) / 2, 0),
            Math.max((height - v.measuredHeight) / 2, 0)
        )
    }

    protected open fun onTapMainDocArea() {}
    protected open fun onDocMotion() {}

    fun setLinksEnabled(b: Boolean) {
        mLinksEnabled = b
        resetupChildren()
        invalidate()
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (!tapDisabled) {
            val pageView = getDisplayedView() as PageView?
            if (mLinksEnabled && pageView != null) {
                val page = pageView.hitLink(e.x, e.y)
                if (page > 0) {
                    pushHistory()
                    setDisplayedViewIndex(page)
                } else {
                    onTapMainDocArea()
                }
            } else if (e.x < tapPageMargin) {
                smartMoveBackwards()
            } else if (e.x > super.getWidth() - tapPageMargin) {
                smartMoveForwards()
            } else if (e.y < tapPageMargin) {
                smartMoveBackwards()
            } else if (e.y > super.getHeight() - tapPageMargin) {
                smartMoveForwards()
            } else {
                onTapMainDocArea()
            }
        }
        return true
    }

    protected fun onChildSetup(i: Int, v: View) {
        val searchResult = SearchTaskResult.get()
        if (searchResult != null && searchResult.pageNumber == i)
            (v as PageView).setSearchBoxes(searchResult.searchBoxes)
        else
            (v as PageView).setSearchBoxes(null)

        (v as PageView).setLinkHighlighting(mLinksEnabled)
    }

    protected open fun onMoveToChild(i: Int) {
        val searchResult = SearchTaskResult.get()
        if (searchResult != null && searchResult.pageNumber != i) {
            SearchTaskResult.set(null)
            resetupChildren()
        }
    }

    protected fun onMoveOffChild(i: Int) {}
    protected fun onSettle(v: View) {
        (v as PageView).updateHq(false)
    }

    protected fun onUnsettle(v: View) {
        (v as PageView).removeHq()
    }

    protected fun onNotInUse(v: View) {
        (v as PageView).releaseResources()
    }

    abstract class ViewMapper {
        abstract fun applyToView(view: View)
    }

    companion object {
        private const val MOVING_DIAGONALLY = 0
        private const val MOVING_LEFT = 1
        private const val MOVING_RIGHT = 2
        private const val MOVING_UP = 3
        private const val MOVING_DOWN = 4

        private const val FLING_MARGIN = 100
        private const val GAP = 20

        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 64.0f

        private const val HORIZONTAL_SCROLLING = true

        private fun directionOfTravel(vx: Float, vy: Float): Int {
            return when {
                Math.abs(vx) > 2 * Math.abs(vy) -> if (vx > 0) MOVING_RIGHT else MOVING_LEFT
                Math.abs(vy) > 2 * Math.abs(vx) -> if (vy > 0) MOVING_DOWN else MOVING_UP
                else -> MOVING_DIAGONALLY
            }
        }

        private fun withinBoundsInDirectionOfTravel(bounds: Rect, vx: Float, vy: Float): Boolean {
            return when (directionOfTravel(vx, vy)) {
                MOVING_DIAGONALLY -> bounds.contains(0, 0)
                MOVING_LEFT -> bounds.left <= 0
                MOVING_RIGHT -> bounds.right >= 0
                MOVING_UP -> bounds.top <= 0
                MOVING_DOWN -> bounds.bottom >= 0
                else -> throw NoSuchElementException()
            }
        }
    }
}
