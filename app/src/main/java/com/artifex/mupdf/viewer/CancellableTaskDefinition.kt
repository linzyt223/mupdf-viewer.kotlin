package com.artifex.mupdf.viewer

interface CancellableTaskDefinition<Params, Result> {
    fun doInBackground(vararg params: Params): Result?
    fun doCancel()
    fun doCleanup()
}
