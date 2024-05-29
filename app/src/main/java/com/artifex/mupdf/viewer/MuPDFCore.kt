package com.artifex.mupdf.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.artifex.mupdf.fitz.*
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.IOException
class MuPDFCore private constructor(private var doc: Document?) {

    private var resolution: Int = 160
    var outline: Array<Outline>? = null
    private var pageCount: Int = -1
    private var reflowable: Boolean = false
    private var currentPage: Int = -1
    private var page: Page? = null
    private var pageWidth: Float = 0f
    private var pageHeight: Float = 0f
    private var displayList: DisplayList? = null

    /* Default to "A Format" pocket book size. */
    private var layoutW = 312
    private var layoutH = 504
    private var layoutEM = 10

    init {
        doc?.let {
            it.layout(layoutW.toFloat(), layoutH.toFloat(), layoutEM.toFloat())
            pageCount = it.countPages()
            reflowable = it.isReflowable()
        }
    }

    constructor(buffer: ByteArray, magic: String) : this(Document.openDocument(buffer, magic))

    constructor(stm: SeekableInputStream, magic: String) : this(Document.openDocument(stm, magic))

    fun getTitle(): String? {
        return doc?.getMetaData(Document.META_INFO_TITLE)
    }

    fun countPages(): Int {
        return pageCount
    }

    fun isReflowable(): Boolean {
        return reflowable
    }

    @Synchronized
    fun layout(oldPage: Int, w: Int, h: Int, em: Int): Int {
        if (w != layoutW || h != layoutH || em != layoutEM) {
            println("LAYOUT: $w,$h")
            layoutW = w
            layoutH = h
            layoutEM = em
            val mark = doc!!.makeBookmark(doc!!.locationFromPageNumber(oldPage))
            doc!!.layout(layoutW.toFloat(), layoutH.toFloat(), layoutEM.toFloat())
            currentPage = -1
            pageCount = doc!!.countPages()
            outline = null
            try {
                outline = doc!!.loadOutline()
            } catch (ex: Exception) {
                /* ignore error */
            }
            return doc!!.pageNumberFromLocation(doc!!.findBookmark(mark))
        }
        return oldPage
    }

    @Synchronized
    private fun gotoPage(pageNum: Int) {
        /* TODO: page cache */
        var pageNum = pageNum
        if (pageNum > pageCount - 1)
            pageNum = pageCount - 1
        else if (pageNum < 0)
            pageNum = 0
        if (pageNum != currentPage) {
            page?.destroy()
            page = null
            displayList?.destroy()
            displayList = null
            pageWidth = 0f
            pageHeight = 0f
            currentPage = -1

            doc?.let {
                page = it.loadPage(pageNum)
                val b = page!!.bounds
                pageWidth = b.x1 - b.x0
                pageHeight = b.y1 - b.y0
            }

            currentPage = pageNum
        }
    }

    @Synchronized
    fun getPageSize(pageNum: Int): PointF {
        gotoPage(pageNum)
        return PointF(pageWidth, pageHeight)
    }

    @Synchronized
    fun onDestroy() {
        displayList?.destroy()
        displayList = null
        page?.destroy()
        page = null
        doc?.destroy()
        doc = null
    }

    @Synchronized
    fun drawPage(
        bm: Bitmap, pageNum: Int,
        pageW: Int, pageH: Int,
        patchX: Int, patchY: Int,
        patchW: Int, patchH: Int,
        cookie: Cookie
    ) {
        gotoPage(pageNum)

        if (displayList == null && page != null) {
            try {
                displayList = page!!.toDisplayList()
            } catch (ex: Exception) {
                displayList = null
            }
        }

        if (displayList == null || page == null)
            return

        val zoom = resolution / 72f
        val ctm = Matrix(zoom, zoom)
        val bbox = RectI(page!!.bounds.transform(ctm))
        val xscale = pageW.toFloat() / (bbox.x1 - bbox.x0)
        val yscale = pageH.toFloat() / (bbox.y1 - bbox.y0)
        ctm.scale(xscale, yscale)

        val dev = AndroidDrawDevice(bm, patchX, patchY)
        try {
            displayList!!.run(dev, ctm, cookie)
            dev.close()
        } finally {
            dev.destroy()
        }
    }

    @Synchronized
    fun updatePage(
        bm: Bitmap, pageNum: Int,
        pageW: Int, pageH: Int,
        patchX: Int, patchY: Int,
        patchW: Int, patchH: Int,
        cookie: Cookie
    ) {
        drawPage(bm, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, cookie)
    }

    @Synchronized
    fun getPageLinks(pageNum: Int): Array<Link>? {
        gotoPage(pageNum)
        return page?.links
    }

    @Synchronized
    fun resolveLink(link: Link): Int {
        return doc!!.pageNumberFromLocation(doc!!.resolveLink(link))
    }

    @Synchronized
    fun searchPage(pageNum: Int, text: String): Array<Array<Quad>>? {
        gotoPage(pageNum)
        return page?.search(text)
    }

    @Synchronized
    fun hasOutline(): Boolean {
        if (outline == null) {
            try {
                outline = doc!!.loadOutline()
            } catch (ex: Exception) {
                /* ignore error */
            }
        }
        return outline != null
    }

    private fun flattenOutlineNodes(result: ArrayList<Item>, list: Array<Outline>?, indent: String) {
        list?.forEach { node ->
            node.title?.let {
                val page = doc!!.pageNumberFromLocation(doc!!.resolveLink(node))
                result.add(Item(indent + it, page))
            }
            flattenOutlineNodes(result, node.down, indent + "    ")
        }
    }

    @Synchronized
    fun getOutline(): ArrayList<Item> {
        val result = ArrayList<Item>()
        flattenOutlineNodes(result, outline, "")
        return result
    }

    @Synchronized
    fun needsPassword(): Boolean {
        return doc!!.needsPassword()
    }

    @Synchronized
    fun authenticatePassword(password: String): Boolean {
        return doc!!.authenticatePassword(password)
    }
}
