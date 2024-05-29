import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.os.AsyncTask
import android.os.Handler
import com.artifex.mupdf.viewer.MuPDFCore
import com.artifex.mupdf.viewer.SearchTaskResult
import com.example.mupdfviewer.R
import java.lang.ref.WeakReference

class ProgressDialogX(context: Context) : ProgressDialog(context) {
    private var mCancelled = false

    val isCancelled: Boolean
        get() = mCancelled

    override fun cancel() {
        mCancelled = true
        super.cancel()
    }
}

abstract class SearchTask(private val mContext: Context, private val mCore: MuPDFCore?) {
    companion object {
        private const val SEARCH_PROGRESS_DELAY = 200
    }

    private val mHandler = Handler()
    private val mAlertBuilder = AlertDialog.Builder(mContext)
    private var mSearchTask: SearchAsyncTask? = null

    protected abstract fun onTextFound(result: SearchTaskResult?)

    fun stop() {
        mSearchTask?.cancel(true)
        mSearchTask = null
    }

    fun go(text: String, direction: Int, displayPage: Int, searchPage: Int) {
        if (mCore == null) return

        stop()

        val increment = direction
        val startIndex = if (searchPage == -1) displayPage else searchPage + increment

        val progressDialog = ProgressDialogX(mContext).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setTitle(mContext.getString(R.string.searching_))
            setOnCancelListener { stop() }
            max = mCore.countPages()
        }

        mSearchTask = SearchAsyncTask(
            mContext,
            mCore,
            text,
            increment,
            startIndex,
            progressDialog,
            mHandler,
            mAlertBuilder,
            ::onTextFound
        )

        mSearchTask?.execute()
    }

    private class SearchAsyncTask(
        context: Context,
        private val mCore: MuPDFCore?,
        private val text: String,
        private val increment: Int,
        private val startIndex: Int,
        private val progressDialog: ProgressDialogX,
        private val mHandler: Handler,
        private val mAlertBuilder: AlertDialog.Builder,
        private val onTextFound: (SearchTaskResult?) -> Unit
    ) : AsyncTask<Void, Int, SearchTaskResult?>() {
        private val contextRef = WeakReference(context)

        @Deprecated("Deprecated in Java")
        override fun doInBackground(vararg params: Void?): SearchTaskResult? {
            var index = startIndex

            while (index in 0 until (mCore?.countPages() ?: 0) && !isCancelled) {
                publishProgress(index)
                val searchHits = mCore?.searchPage(index, text)

                if (!searchHits.isNullOrEmpty()) {
                    return SearchTaskResult(text, index, searchHits)
                }
                index += increment
            }
            return null
        }

        @Deprecated("Deprecated in Java")
        override fun onPostExecute(result: SearchTaskResult?) {
            progressDialog.cancel()
            val context = contextRef.get()
            if (context != null) {
                if (result != null) {
                    onTextFound(result)
                } else {
                    mAlertBuilder.setTitle(
                        R.string.text_not_found
                    )
                    val alert = mAlertBuilder.create()
                    alert.setButton(
                        AlertDialog.BUTTON_POSITIVE, context.getString(R.string.dismiss)
                    ) { _, _ -> }
                    alert.show()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCancelled() {
            progressDialog.cancel()
        }

        @Deprecated("Deprecated in Java")
        override fun onProgressUpdate(vararg values: Int?) {
            progressDialog.progress = values[0] ?: 0
        }

        @Deprecated("Deprecated in Java")
        override fun onPreExecute() {
            super.onPreExecute()
            mHandler.postDelayed({
                if (!progressDialog.isCancelled) {
                    progressDialog.show()
                    progressDialog.progress = startIndex
                }
            }, SEARCH_PROGRESS_DELAY.toLong())
        }
    }
}
