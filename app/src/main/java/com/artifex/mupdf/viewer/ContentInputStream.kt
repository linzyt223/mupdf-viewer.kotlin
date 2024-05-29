package com.artifex.mupdf.viewer

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream.SEEK_CUR
import com.artifex.mupdf.fitz.SeekableStream.SEEK_END
import com.artifex.mupdf.fitz.SeekableStream.SEEK_SET
import java.io.IOException
import java.io.InputStream

class ContentInputStream(private val cr: ContentResolver, private val uri: Uri, size: Long) : SeekableInputStream {
    private val APP = "MuPDF"
    private var istream: InputStream? = null
    private var length: Long = size
    private var p: Long = 0
    private var mustReopenStream = false

    init {
        reopenStream()
    }

    @Throws(IOException::class)
    override fun seek(offset: Long, whence: Int): Long {
        var newp = p
        when (whence) {
            SEEK_SET -> newp = offset
            SEEK_CUR -> newp = p + offset
            SEEK_END -> {
                if (length < 0) {
                    val buf = ByteArray(16384)
                    var k: Int
                    while (istream?.read(buf).also { k = it ?: -1 } != -1) {
                        p += k
                    }
                    length = p
                }
                newp = length + offset
            }
        }

        if (newp < p) {
            if (!mustReopenStream) {
                try {
                    istream?.skip(newp - p)
                } catch (x: IOException) {
                    Log.i(APP, "Unable to skip backwards, reopening input stream")
                    mustReopenStream = true
                }
            }
            if (mustReopenStream) {
                reopenStream()
                istream?.skip(newp)
            }
        } else if (newp > p) {
            istream?.skip(newp - p)
        }
        p = newp
        return newp
    }

    @Throws(IOException::class)
    override fun position(): Long {
        return p
    }

    @Throws(IOException::class)
    override fun read(buf: ByteArray): Int {
        val n = istream?.read(buf) ?: -1
        if (n > 0) {
            p += n
        } else if (n < 0 && length < 0) {
            length = p
        }
        return n
    }

    @Throws(IOException::class)
    private fun reopenStream() {
        istream?.close()
        istream = cr.openInputStream(uri)
        p = 0
    }
}
