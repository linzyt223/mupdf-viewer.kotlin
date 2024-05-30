package com.artifex.mupdf.viewer

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Handler
import android.os.FileUriExposedException
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Quad
import com.example.mupdfviewer.R
import kotlinx.coroutines.*
import java.util.*

class OpaqueImageView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    override fun isOpaque(): Boolean {
        return true
    }
}

class PageView(
    private val mContext: Context,
    private val mCore: MuPDFCore,
    private var mParentSize: Point,
    private var mSharedHqBm: Bitmap?
) : ViewGroup(mContext) {

    companion object {
        private const val APP = "MuPDF"
        private const val HIGHLIGHT_COLOR = 0x80cc6600.toInt()
        private const val LINK_COLOR = 0x800066cc.toInt()
        private const val BOX_COLOR = 0xFF4444FF.toInt()
        private const val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()
        private const val PROGRESS_DIALOG_DELAY = 200
    }

    private var mPageNumber: Int = 0
    private var mSize: Point? = null // Size of page at minimum zoom
    private var mSourceScale: Float = 0.toFloat()

    private var mEntire: ImageView? = null // Image rendered at minimum zoom
    private var mEntireBm: Bitmap? = Bitmap.createBitmap(mParentSize.x, mParentSize.y, Bitmap.Config.ARGB_8888)
    private var mEntireMat: Matrix? = Matrix()
    private var mGetLinkInfo: AsyncTask<Void, Void, Array<Link>?>? = null
    private var mGetLinkInfoJob: Job? = null
    private var mDrawEntire: CancellableAsyncTask<Void, Boolean>? = null

    private var mPatchViewSize: Point? = null // View size on the basis of which the patch was created
    private var mPatchArea: Rect? = null
    private var mPatch: ImageView? = null
    private var mPatchBm: Bitmap? = mSharedHqBm
    private var mDrawPatch: CancellableAsyncTask<Void, Boolean>? = null
    private var mSearchBoxes: Array<Array<Quad>>? = null
    private var mLinks: Array<Link>? = null
    private var mSearchView: View? = null
    private var mIsBlank: Boolean = false
    private var mHighlightLinks: Boolean = false

    private var mErrorIndicator: ImageView? = null

    private var mBusyIndicator: ProgressBar? = null
    private val mHandler = Handler()

    init {
        setBackgroundColor(BACKGROUND_COLOR)
    }

    private fun reinit() {
        // Cancel pending render task
        mDrawEntire?.cancel()
        mDrawEntire = null

        mDrawPatch?.cancel()
        mDrawPatch = null

        mGetLinkInfo?.cancel(true)
        mGetLinkInfo = null

        mIsBlank = true
        mPageNumber = 0

        if (mSize == null)
            mSize = mParentSize

        mEntire?.apply {
            setImageBitmap(null)
            invalidate()
        }

        mPatch?.apply {
            setImageBitmap(null)
            invalidate()
        }

        mPatchViewSize = null
        mPatchArea = null

        mSearchBoxes = null
        mLinks = null

        clearRenderError()
    }

    fun releaseResources() {
        reinit()

        if (mBusyIndicator != null) {
            removeView(mBusyIndicator)
            mBusyIndicator = null
        }
        clearRenderError()
    }

    fun releaseBitmaps() {
        reinit()

        // recycle bitmaps before releasing them.
        mEntireBm?.recycle()
        mEntireBm = null

        mPatchBm?.recycle()
        mPatchBm = null
    }

    fun blank(page: Int) {
        reinit()
        mPageNumber = page

        if (mBusyIndicator == null) {
            mBusyIndicator = ProgressBar(mContext).apply {
                isIndeterminate = true
                addView(this)
            }
        }

        setBackgroundColor(BACKGROUND_COLOR)
    }

    protected fun clearRenderError() {
        mErrorIndicator?.let {
            removeView(it)
            mErrorIndicator = null
            invalidate()
        }
    }

    protected fun setRenderError(why: String) {
        val page = mPageNumber
        reinit()
        mPageNumber = page

        if (mBusyIndicator != null) {
            removeView(mBusyIndicator)
            mBusyIndicator = null
        }
        if (mSearchView != null) {
            removeView(mSearchView)
            mSearchView = null
        }

        if (mErrorIndicator == null) {
            mErrorIndicator = OpaqueImageView(mContext).apply {
                scaleType = ImageView.ScaleType.CENTER
                addView(this)
                setImageDrawable(resources.getDrawable(R.drawable.ic_error_red_24dp))
                setBackgroundColor(BACKGROUND_COLOR)
            }
        }

        setBackgroundColor(Color.TRANSPARENT)
        mErrorIndicator?.bringToFront()
        mErrorIndicator?.invalidate()
    }

    fun setPage(page: Int, size: PointF?) {
        // Cancel pending render task
        mDrawEntire?.cancel()
        mDrawEntire = null

        mIsBlank = false
        // Highlights may be missing because mIsBlank was true on last draw
        mSearchView?.invalidate()

        mPageNumber = page

        val pageSize = size ?: PointF(612f, 792f).also {
            setRenderError("Error loading page")
        }

        // Calculate scaled size that fits within the screen limits
        // This is the size at minimum zoom
        mSourceScale = minOf(mParentSize.x / pageSize.x, mParentSize.y / pageSize.y)
        mSize = Point((pageSize.x * mSourceScale).toInt(), (pageSize.y * mSourceScale).toInt())

        if (mErrorIndicator != null) return

        if (mEntire == null) {
            mEntire = OpaqueImageView(mContext).apply {
                scaleType = ImageView.ScaleType.MATRIX
                addView(this)
            }
        }

        mEntire?.setImageBitmap(null)
        mEntire?.invalidate()

        // Get the link info in the background
        mGetLinkInfoJob?.cancel()
        mGetLinkInfoJob = CoroutineScope(Dispatchers.Default).launch {
            val links = withContext(Dispatchers.IO) {
                getLinkInfo()
            }
            mLinks = links
            withContext(Dispatchers.Main) {
                mSearchView?.invalidate()
            }
        }

        // Render the page in the background
        mDrawEntire = object : CancellableAsyncTask<Void, Boolean>(getDrawPageTask(mEntireBm, mSize!!.x, mSize!!.y, 0, 0, mSize!!.x, mSize!!.y)) {
            override fun onPreExecute() {
                setBackgroundColor(BACKGROUND_COLOR)
                mEntire?.setImageBitmap(null)
                mEntire?.invalidate()

                if (mBusyIndicator == null) {
                    mBusyIndicator = ProgressBar(mContext).apply {
                        isIndeterminate = true
                        addView(this)
                        visibility = View.INVISIBLE
                        mHandler.postDelayed({
                            mBusyIndicator?.visibility = View.VISIBLE
                        }, PROGRESS_DIALOG_DELAY.toLong())
                    }
                }
            }

            override fun onPostExecute(result: Boolean) {
                removeView(mBusyIndicator)
                mBusyIndicator = null
                if (result) {
                    clearRenderError()
                    mEntire?.setImageBitmap(mEntireBm)
                    mEntire?.invalidate()
                } else {
                    setRenderError("Error rendering page")
                }
                setBackgroundColor(Color.TRANSPARENT)
            }
        }.also { it.execute() }

        if (mSearchView == null) {
            mSearchView = object : View(mContext) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    // Work out current total scale factor
                    // from source to view
                    val scale = mSourceScale * width.toFloat() / mSize!!.x.toFloat()
                    val paint = Paint()

                    if (!mIsBlank && mSearchBoxes != null) {
                        paint.color = HIGHLIGHT_COLOR
                        mSearchBoxes!!.forEach { searchBox ->
                            searchBox.forEach { q ->
                                val path = Path().apply {
                                    moveTo(q.ul_x * scale, q.ul_y * scale)
                                    lineTo(q.ll_x * scale, q.ll_y * scale)
                                    lineTo(q.lr_x * scale, q.lr_y * scale)
                                    lineTo(q.ur_x * scale, q.ur_y * scale)
                                    close()
                                }
                                canvas.drawPath(path, paint)
                            }
                        }
                    }

                    if (!mIsBlank && mLinks != null && mHighlightLinks) {
                        paint.color = LINK_COLOR
                        mLinks!!.forEach { link ->
                            canvas.drawRect(
                                link.bounds.x0 * scale, link.bounds.y0 * scale,
                                link.bounds.x1 * scale, link.bounds.y1 * scale, paint
                            )
                        }
                    }
                }
            }.also { addView(it) }
        }

        requestLayout()
    }

    fun setSearchBoxes(searchBoxes: Array<Array<Quad>>?) {
        mSearchBoxes = searchBoxes
        mSearchView?.invalidate()
    }

    fun setLinkHighlighting(f: Boolean) {
        mHighlightLinks = f
        mSearchView?.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val x = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> mSize!!.x
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        val y = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> mSize!!.y
            else -> MeasureSpec.getSize(heightMeasureSpec)
        }

        setMeasuredDimension(x, y)

        val limit = minOf(mParentSize.x, mParentSize.y) / 2

        mBusyIndicator?.measure(MeasureSpec.AT_MOST or limit, MeasureSpec.AT_MOST or limit)
        mErrorIndicator?.measure(MeasureSpec.AT_MOST or limit, MeasureSpec.AT_MOST or limit)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top

        mEntire?.apply {
            if (width != w || height != h) {
                mEntireMat?.setScale(w.toFloat() / mSize!!.x, h.toFloat() / mSize!!.y)
                imageMatrix = mEntireMat
                invalidate()
            }
            layout(0, 0, w, h)
        }

        mSearchView?.layout(0, 0, w, h)

        mPatchViewSize?.let {
            if (it.x != w || it.y != h) {
                // Zoomed since patch was created
                mPatchViewSize = null
                mPatchArea = null
                mPatch?.apply {
                    setImageBitmap(null)
                    invalidate()
                }
            } else {
                mPatch?.layout(mPatchArea!!.left, mPatchArea!!.top, mPatchArea!!.right, mPatchArea!!.bottom)
            }
        }

        mBusyIndicator?.apply {
            val bw = measuredWidth
            val bh = measuredHeight
            layout((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        }

        mErrorIndicator?.apply {
            val bw = (8.5 * measuredWidth).toInt()
            val bh = (11 * measuredHeight).toInt()
            layout((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        }
    }

    fun updateHq(update: Boolean) {
        mErrorIndicator?.let {
            mPatch?.apply {
                setImageBitmap(null)
                invalidate()
            }
            return
        }

        val viewArea = Rect(left, top, right, bottom)
        if (viewArea.width() == mSize!!.x || viewArea.height() == mSize!!.y) {
            // If the viewArea's size matches the unzoomed size, there is no need for an hq patch
            mPatch?.apply {
                setImageBitmap(null)
                invalidate()
            }
        } else {
            val patchViewSize = Point(viewArea.width(), viewArea.height())
            val patchArea = Rect(0, 0, mParentSize.x, mParentSize.y)

            // Intersect and test that there is an intersection
            if (!patchArea.intersect(viewArea)) return

            // Offset patch area to be relative to the view top left
            patchArea.offset(-viewArea.left, -viewArea.top)

            val areaUnchanged = patchArea == mPatchArea && patchViewSize == mPatchViewSize

            // If being asked for the same area as last time and not because of an update then nothing to do
            if (areaUnchanged && !update) return

            val completeRedraw = !(areaUnchanged && update)

            // Stop the drawing of previous patch if still going
            mDrawPatch?.cancel()
            mDrawPatch = null

            // Create and add the image view if not already done
            if (mPatch == null) {
                mPatch = OpaqueImageView(mContext).apply {
                    scaleType = ImageView.ScaleType.MATRIX
                    addView(this)
                    mSearchView?.bringToFront()
                }
            }

            val task: CancellableTaskDefinition<Void, Boolean> = if (completeRedraw) {
                getDrawPageTask(mPatchBm, patchViewSize.x, patchViewSize.y, patchArea.left, patchArea.top, patchArea.width(), patchArea.height())
            } else {
                getUpdatePageTask(mPatchBm, patchViewSize.x, patchViewSize.y, patchArea.left, patchArea.top, patchArea.width(), patchArea.height())
            }

            mDrawPatch = object : CancellableAsyncTask<Void, Boolean>(task) {
                override fun onPostExecute(result: Boolean) {
                    if (result) {
                        mPatchViewSize = patchViewSize
                        mPatchArea = patchArea
                        clearRenderError()
                        mPatch?.setImageBitmap(mPatchBm)
                        mPatch?.invalidate()
                        //requestLayout();
                        // Calling requestLayout here doesn't lead to a later call to layout. No idea
                        // why, but apparently others have run into the problem.
                        mPatch?.layout(mPatchArea!!.left, mPatchArea!!.top, mPatchArea!!.right, mPatchArea!!.bottom)
                    } else {
                        setRenderError("Error rendering patch")
                    }
                }
            }.also { it.execute() }
        }
    }

    fun update() {
        // Cancel pending render task
        mDrawEntire?.cancel()
        mDrawEntire = null

        mDrawPatch?.cancel()
        mDrawPatch = null

        // Render the page in the background
        mDrawEntire = object : CancellableAsyncTask<Void, Boolean>(getUpdatePageTask(mEntireBm, mSize!!.x, mSize!!.y, 0, 0, mSize!!.x, mSize!!.y)) {
//            override fun onPreExecute() {
//                setBackgroundColor(BACKGROUND_COLOR)
//                mEntire?.setImageBitmap(null)
//                mEntire?.invalidate()
//
//                if (mBusyIndicator == null) {
//                    mBusyIndicator = ProgressBar(mContext).apply {
//                        isIndeterminate = true
//                        addView(this)
//                        visibility = View.INVISIBLE
//                        mHandler.postDelayed({
//                            mBusyIndicator?.visibility = View.VISIBLE
//                        }, PROGRESS_DIALOG_DELAY.toLong())
//                    }
//                }
//            }

            override fun onPostExecute(result: Boolean) {
                removeView(mBusyIndicator)
                mBusyIndicator = null
                if (result) {
                    clearRenderError()
                    mEntire?.setImageBitmap(mEntireBm)
                    mEntire?.invalidate()
                } else {
                    setRenderError("Error updating page")
                }
                setBackgroundColor(Color.TRANSPARENT)
            }
        }.also { it.execute() }

        updateHq(true)
    }

    fun removeHq() {
        // Stop the drawing of the patch if still going
        mDrawPatch?.cancel()
        mDrawPatch = null

        // And get rid of it
        mPatchViewSize = null
        mPatchArea = null
        mPatch?.apply {
            setImageBitmap(null)
            invalidate()
        }
    }

    fun getPage(): Int {
        return mPageNumber
    }

    override fun isOpaque(): Boolean {
        return true
    }

    fun hitLink(link: Link): Int {
        if (link.isExternal) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) // API>=21: FLAG_ACTIVITY_NEW_DOCUMENT
            }
            return try {
                mContext.startActivity(intent)
                0
            } catch (e: FileUriExposedException) {
                Log.e(APP, e.toString())
                Toast.makeText(mContext, "Android does not allow following file:// link: ${link.uri}", Toast.LENGTH_LONG).show()
                0
            } catch (e: Throwable) {
                Log.e(APP, e.toString())
                Toast.makeText(mContext, e.message, Toast.LENGTH_LONG).show()
                0
            }
        } else {
            return mCore.resolveLink(link)
        }
    }

    fun hitLink(x: Float, y: Float): Int {
        val scale = mSourceScale * width.toFloat() / mSize!!.x.toFloat()
        val docRelX = (x - left) / scale
        val docRelY = (y - top) / scale

        return mLinks?.firstOrNull { it.bounds.contains(docRelX, docRelY) }?.let { hitLink(it) } ?: 0
    }

    protected fun getDrawPageTask(
        bm: Bitmap?, sizeX: Int, sizeY: Int,
        patchX: Int, patchY: Int, patchWidth: Int, patchHeight: Int
    ): CancellableTaskDefinition<Void, Boolean> {
        return object : MuPDFCancellableTaskDefinition<Void, Boolean>() {
            override fun doInBackground(cookie: Cookie, vararg params: Void): Boolean {
                if (bm == null) return false
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.HONEYCOMB until Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    bm.eraseColor(0)
                }
                return try {
                    mCore.drawPage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight, cookie)
                    true
                } catch (e: RuntimeException) {
                    false
                }
            }
        }
    }

    protected fun getUpdatePageTask(
        bm: Bitmap?, sizeX: Int, sizeY: Int,
        patchX: Int, patchY: Int, patchWidth: Int, patchHeight: Int
    ): CancellableTaskDefinition<Void, Boolean> {
        return object : MuPDFCancellableTaskDefinition<Void, Boolean>() {
            override fun doInBackground(cookie: Cookie, vararg params: Void): Boolean {
                if (bm == null) return false
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.HONEYCOMB until Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    bm.eraseColor(0)
                }
                return try {
                    mCore.updatePage(bm, mPageNumber, sizeX, sizeY, patchX, patchY, patchWidth, patchHeight, cookie)
                    true
                } catch (e: RuntimeException) {
                    false
                }
            }
        }
    }

    protected fun getLinkInfo(): Array<Link>? {
        return try {
            mCore.getPageLinks(mPageNumber)
        } catch (e: RuntimeException) {
            null
        }
    }
}
