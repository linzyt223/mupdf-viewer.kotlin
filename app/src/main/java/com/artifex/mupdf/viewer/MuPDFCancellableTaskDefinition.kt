package com.artifex.mupdf.viewer

import com.artifex.mupdf.fitz.Cookie

abstract class MuPDFCancellableTaskDefinition<Params, Result> : CancellableTaskDefinition<Params, Result> {
    private var cookie: Cookie? = Cookie()

    override fun doCancel() {
        cookie?.abort()
    }

    override fun doCleanup() {
        cookie?.destroy()
        cookie = null
    }

    open override fun doInBackground(vararg params: Params): Result {
        return doInBackground(cookie!!, *params)
    }

    abstract fun doInBackground(cookie: Cookie, vararg params: Params): Result
}
