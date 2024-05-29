package com.artifex.mupdf.viewer

import com.artifex.mupdf.fitz.Quad

class SearchTaskResult(val txt: String, val pageNumber: Int, val searchBoxes: Array<Array<Quad>>) {

    companion object {
        private var singleton: SearchTaskResult? = null

        @JvmStatic
        fun get(): SearchTaskResult? {
            return singleton
        }

        @JvmStatic
        fun set(r: SearchTaskResult?) {
            singleton = r
        }
    }
}
