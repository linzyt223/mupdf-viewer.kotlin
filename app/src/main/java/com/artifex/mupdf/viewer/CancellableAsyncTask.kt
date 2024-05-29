package com.artifex.mupdf.viewer

import kotlinx.coroutines.*
import java.util.concurrent.CancellationException

abstract class CancellableAsyncTask<Params, Result>(
    private val task: CancellableTaskDefinition<Params, Result>
) {

    private var job: Job? = null

    init {
    }

    open fun onPreExecute() {
        // Override this method if needed
    }

    open fun onPostExecute(result: Result) {
        // Override this method if needed
    }

    fun cancel() {
        job?.cancel(CancellationException("Task was cancelled"))
        task.doCancel()
    }

    fun execute(vararg params: Params) {
        onPreExecute()
        job = CoroutineScope(Dispatchers.Default).launch {
            val result = withContext(Dispatchers.IO) {
                task.doInBackground(*params)
            }
            withContext(Dispatchers.Main) {
                if (result != null) {
                    onPostExecute(result)
                }
                task.doCleanup()
            }
        }
    }
}
