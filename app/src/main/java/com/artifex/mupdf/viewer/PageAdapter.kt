package com.artifex.mupdf.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import kotlinx.coroutines.*

class PageAdapter(private val mContext: Context, private val mCore: MuPDFCore) : BaseAdapter() {

    private val mPageSizes = SparseArray<PointF>()
    private var mSharedHqBm: Bitmap? = null

    override fun getCount(): Int {
        return try {
            mCore.countPages()
        } catch (e: RuntimeException) {
            0
        }
    }

    override fun getItem(position: Int): Any? {
        return null
    }

    override fun getItemId(position: Int): Long {
        return 0
    }

    @Synchronized
    fun releaseBitmaps() {
        mSharedHqBm?.recycle()
        mSharedHqBm = null
    }

    fun refresh() {
        mPageSizes.clear()
    }

    @Synchronized
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val pageView: PageView
        if (convertView == null) {
            if (mSharedHqBm == null || mSharedHqBm!!.width != parent.width || mSharedHqBm!!.height != parent.height) {
                if (parent.width > 0 && parent.height > 0) {
                    mSharedHqBm = Bitmap.createBitmap(parent.width, parent.height, Bitmap.Config.ARGB_8888)
                } else {
                    mSharedHqBm = null
                }
            }
            pageView = PageView(mContext, mCore, Point(parent.width, parent.height), mSharedHqBm)
        } else {
            pageView = convertView as PageView
        }

        val pageSize = mPageSizes.get(position)
        if (pageSize != null) {
            pageView.setPage(position, pageSize)
        } else {
            pageView.blank(position)
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        mCore.getPageSize(position)
                    } catch (e: RuntimeException) {
                        null
                    }
                }
                result?.let {
                    mPageSizes.put(position, it)
                    if (pageView.getPage() == position) {
                        pageView.setPage(position, it)
                    }
                }
            }
        }
        return pageView
    }
}
