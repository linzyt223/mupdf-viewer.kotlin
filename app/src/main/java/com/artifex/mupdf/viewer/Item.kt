package com.artifex.mupdf.viewer

import java.io.Serializable

class Item(val title: String, val page: Int) : Serializable {
    override fun toString(): String {
        return title
    }
}
