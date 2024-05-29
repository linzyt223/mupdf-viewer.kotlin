package com.artifex.mupdf.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ViewAnimator
import com.artifex.mupdf.fitz.SeekableInputStream
import java.io.IOException
import java.util.Locale

class DocumentActivity : Activity() {

    private val APP = "MuPDF"

    private enum class TopBarMode { Main, Search, More }

    private val OUTLINE_REQUEST = 0
    private var core: MuPDFCore? = null
    private var mDocTitle: String? = null
    private var mDocKey: String? = null
    private lateinit var mDocView: ReaderView
    private lateinit var mButtonsView: View
    private var mButtonsVisible = false
    private lateinit var mPasswordView: EditText
    private lateinit var mDocNameView: TextView
    private lateinit var mPageSlider: SeekBar
    private var mPageSliderRes: Int = 0
    private lateinit var mPageNumberView: TextView
    private lateinit var mSearchButton: ImageButton
    private lateinit var mOutlineButton: ImageButton
    private lateinit var mTopBarSwitcher: ViewAnimator
    private lateinit var mLinkButton: ImageButton
    private var mTopBarMode = TopBarMode.Main
    private lateinit var mSearchBack: ImageButton
    private lateinit var mSearchFwd: ImageButton
    private lateinit var mSearchClose: ImageButton
    private lateinit var mSearchText: EditText
    private lateinit var mSearchTask: SearchTask
    private lateinit var mAlertBuilder: AlertDialog.Builder
    private var mLinkHighlight = false
    private var mFlatOutline: ArrayList<Item>? = null
    private var mReturnToLibraryActivity = false

    protected var mDisplayDPI: Int = 0
    private var mLayoutEM = 10
    private var mLayoutW = 312
    private var mLayoutH = 504

    protected lateinit var mLayoutButton: View
    protected lateinit var mLayoutPopupMenu: PopupMenu

    private fun toHex(digest: ByteArray): String {
        val builder = StringBuilder(2 * digest.size)
        for (b in digest)
            builder.append(String.format("%02x", b))
        return builder.toString()
    }

    private fun openBuffer(buffer: ByteArray, magic: String): MuPDFCore? {
        return try {
            core = MuPDFCore(buffer, magic)
            core
        } catch (e: Exception) {
            Log.e(APP, "Error opening document buffer: $e")
            null
        }
    }

    private fun openStream(stm: SeekableInputStream, magic: String): MuPDFCore? {
        return try {
            core = MuPDFCore(stm, magic)
            core
        } catch (e: Exception) {
            Log.e(APP, "Error opening document stream: $e")
            null
        }
    }

    @Throws(IOException::class)
    private fun openCore(uri: Uri, size: Long, mimetype: String?): MuPDFCore? {
        val cr = contentResolver

        Log.i(APP, "Opening document $uri")

        val isStream = cr.openInputStream(uri)
        var buf: ByteArray? = null
        var used = -1
        try {
            val limit = 8 * 1024 * 1024
            if (size < 0) {
                buf = ByteArray(limit)
                used = isStream?.read(buf) ?: -1
                val atEOF = isStream?.read() == -1
                if (used < 0 || (used == limit && !atEOF)) buf = null
            } else if (size <= limit) {
                buf = ByteArray(size.toInt())
                used = isStream?.read(buf) ?: -1
                if (used < 0 || used < size) buf = null
            }
            if (buf != null && buf.size != used) {
                val newbuf = ByteArray(used)
                System.arraycopy(buf, 0, newbuf, 0, used)
                buf = newbuf
            }
        } catch (e: OutOfMemoryError) {
            buf = null
        } finally {
            isStream?.close()
        }

        return if (buf != null) {
            Log.i(APP, "  Opening document from memory buffer of size ${buf.size}")
            openBuffer(buf, mimetype!!)
        } else {
            Log.i(APP, "  Opening document from stream")
            openStream(ContentInputStream(cr, uri, size), mimetype!!)
        }
    }

    private fun showCannotOpenDialog(reason: String) {
        val res = resources
        val alert = mAlertBuilder.create()
        setTitle(String.format(Locale.ROOT, res.getString(R.string.cannot_open_document_Reason), reason))
        alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss)) { _, _ -> finish() }
        alert.show()
    }

    /** Called when the activity is first created. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        mDisplayDPI = metrics.densityDpi

        mAlertBuilder = AlertDialog.Builder(this)

        if (core == null) {
            if (savedInstanceState != null && savedInstanceState.containsKey("DocTitle")) {
                mDocTitle = savedInstanceState.getString("DocTitle")
            }
        }
        if (core == null) {
            val intent = intent
            val file: SeekableInputStream?

            mReturnToLibraryActivity = intent.getIntExtra("$packageName.ReturnToLibraryActivity", 0) != 0

            if (Intent.ACTION_VIEW == intent.action) {
                val uri = intent.data
                var mimetype = intent.type

                if (uri == null) {
                    showCannotOpenDialog("No document uri to open")
                    return
                }

                mDocKey = uri.toString()

                Log.i(APP, "OPEN URI $uri")
                Log.i(APP, "  MAGIC (Intent) $mimetype")

                mDocTitle = null
                var size: Long = -1
                var cursor: Cursor? = null

                try {
                    cursor = contentResolver.query(uri, null, null, null, null)
                    if (cursor != null && cursor.moveToFirst()) {
                        var idx: Int

                        idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_STRING)
                            mDocTitle = cursor.getString(idx)

                        idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER)
                            size = cursor.getLong(idx)

                        if (size == 0L) size = -1
                    }
                } catch (x: Exception) {
                    // Ignore any exception and depend on default values for title
                    // and size (unless one was decoded
                } finally {
                    cursor?.close()
                }

                Log.i(APP, "  NAME $mDocTitle")
                Log.i(APP, "  SIZE $size")

                if (mimetype == null || mimetype == "application/octet-stream") {
                    mimetype = contentResolver.getType(uri)
                    Log.i(APP, "  MAGIC (Resolved) $mimetype")
                }
                if (mimetype == null || mimetype == "application/octet-stream") {
                    mimetype = mDocTitle
                    Log.i(APP, "  MAGIC (Filename) $mimetype")
                }

                try {
                    core = openCore(uri, size, mimetype)
                    SearchTaskResult.set(null)
                } catch (x: Exception) {
                    showCannotOpenDialog(x.toString())
                    return
                }
            }
            if (core != null && core!!.needsPassword()) {
                requestPassword(savedInstanceState)
                return
            }
            if (core != null && core!!.countPages() == 0) {
                core = null
            }
        }
        if (core == null) {
            val alert = mAlertBuilder.create()
            alert.setTitle(R.string.cannot_open_document)
            alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss)) { _, _ -> finish() }
            alert.setOnCancelListener { finish() }
            alert.show()
            return
        }

        createUI(savedInstanceState)
    }

    private fun requestPassword(savedInstanceState: Bundle?) {
        mPasswordView = EditText(this)
        mPasswordView.inputType = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        mPasswordView.transformationMethod = PasswordTransformationMethod()

        val alert = mAlertBuilder.create()
        alert.setTitle(R.string.enter_password)
        alert.setView(mPasswordView)
        alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay)) { _, _ ->
            if (core!!.authenticatePassword(mPasswordView.text.toString())) {
                createUI(savedInstanceState)
            } else {
                requestPassword(savedInstanceState)
            }
        }
        alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel)) { _, _ -> finish() }
        alert.show()
    }

    fun relayoutDocument() {
        val loc = core!!.layout(mDocView.mCurrent, mLayoutW, mLayoutH, mLayoutEM)
        mFlatOutline = null
        mDocView.mHistory.clear()
        mDocView.refresh()
        mDocView.setDisplayedViewIndex(loc)
    }

    fun createUI(savedInstanceState: Bundle?) {
        if (core == null) return

        // Now create the UI.
        // First create the document view
        mDocView = object : ReaderView(this@DocumentActivity) {
            override fun onMoveToChild(i: Int) {
                if (core == null) return

                mPageNumberView.text = String.format(Locale.ROOT, "%d / %d", i + 1, core!!.countPages())
                mPageSlider.max = (core!!.countPages() - 1) * mPageSliderRes
                mPageSlider.progress = i * mPageSliderRes
                super.onMoveToChild(i)
            }

            override fun onTapMainDocArea() {
                if (!mButtonsVisible) {
                    showButtons()
                } else {
                    if (mTopBarMode == TopBarMode.Main) hideButtons()
                }
            }

            override fun onDocMotion() {
                hideButtons()
            }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                if (core!!.isReflowable()) {
                    mLayoutW = w * 72 / mDisplayDPI
                    mLayoutH = h * 72 / mDisplayDPI
                    relayoutDocument()
                } else {
                    refresh()
                }
            }
        }
        mDocView.adapter = PageAdapter(this, core!!)

        mSearchTask = object : SearchTask(this, core!!) {
            override fun onTextFound(result: SearchTaskResult) {
                SearchTaskResult.set(result)
                // Ask the ReaderView to move to the resulting page
                mDocView.setDisplayedViewIndex(result.pageNumber)
                // Make the ReaderView act on the change to SearchTaskResult
                // via overridden onChildSetup method.
                mDocView.resetupChildren()
            }
        }

        // Make the buttons overlay, and store all its
        // controls in variables
        makeButtonsView()

        // Set up the page slider
        val smax = Math.max(core!!.countPages() - 1, 1)
        mPageSliderRes = ((10 + smax - 1) / smax) * 2

        // Set the file-name text
        val docTitle = core!!.getTitle()
        if (docTitle != null) mDocNameView.text = docTitle else mDocNameView.text = mDocTitle

        // Activate the seekbar
        mPageSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                mDocView.pushHistory()
                mDocView.setDisplayedViewIndex((seekBar.progress + mPageSliderRes / 2) / mPageSliderRes)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updatePageNumView((progress + mPageSliderRes / 2) / mPageSliderRes)
            }
        })

        // Activate the search-preparing button
        mSearchButton.setOnClickListener { searchModeOn() }

        mSearchClose.setOnClickListener { searchModeOff() }

        // Search invoking buttons are disabled while there is no text specified
        mSearchBack.isEnabled = false
        mSearchFwd.isEnabled = false
        mSearchBack.setColorFilter(Color.argb(255, 128, 128, 128))
        mSearchFwd.setColorFilter(Color.argb(255, 128, 128, 128))

        // React to interaction with the text widget
        mSearchText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                val haveText = s.toString().isNotEmpty()
                setButtonEnabled(mSearchBack, haveText)
                setButtonEnabled(mSearchFwd, haveText)

                // Remove any previous search results
                if (SearchTaskResult.get() != null && mSearchText.text.toString() != SearchTaskResult.get()!!.txt) {
                    SearchTaskResult.set(null)
                    mDocView.resetupChildren()
                }
            }

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })

        //React to Done button on keyboard
        mSearchText.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) search(1)
            false
        }

        mSearchText.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) search(1)
            false
        }

        // Activate search invoking buttons
        mSearchBack.setOnClickListener { search(-1) }
        mSearchFwd.setOnClickListener { search(1) }

        mLinkButton.setOnClickListener { setLinkHighlight(!mLinkHighlight) }

        if (core!!.isReflowable()) {
            mLayoutButton.visibility = View.VISIBLE
            mLayoutPopupMenu = PopupMenu(this, mLayoutButton)
            mLayoutPopupMenu.menuInflater.inflate(R.menu.layout_menu, mLayoutPopupMenu.menu)
            mLayoutPopupMenu.setOnMenuItemClickListener { item ->
                val oldLayoutEM = mLayoutEM
                when (item.itemId) {
                    R.id.action_layout_6pt -> mLayoutEM = 6
                    R.id.action_layout_7pt -> mLayoutEM = 7
                    R.id.action_layout_8pt -> mLayoutEM = 8
                    R.id.action_layout_9pt -> mLayoutEM = 9
                    R.id.action_layout_10pt -> mLayoutEM = 10
                    R.id.action_layout_11pt -> mLayoutEM = 11
                    R.id.action_layout_12pt -> mLayoutEM = 12
                    R.id.action_layout_13pt -> mLayoutEM = 13
                    R.id.action_layout_14pt -> mLayoutEM = 14
                    R.id.action_layout_15pt -> mLayoutEM = 15
                    R.id.action_layout_16pt -> mLayoutEM = 16
                }
                if (oldLayoutEM != mLayoutEM) relayoutDocument()
                true
            }
            mLayoutButton.setOnClickListener { mLayoutPopupMenu.show() }
        }

        if (core!!.hasOutline()) {
            mOutlineButton.setOnClickListener {
                if (mFlatOutline == null) {
                    mFlatOutline = core!!.getOutline()
                }
                else {
                    val intent = Intent(this@DocumentActivity, OutlineActivity::class.java)
                    val bundle = Bundle()
                    bundle.putInt("POSITION", mDocView.getDisplayedViewIndex())
                    bundle.putSerializable("OUTLINE", mFlatOutline)
                    intent.putExtra("PALLET-BUNDLE", Pallet.sendBundle(bundle))
                    startActivityForResult(intent, OUTLINE_REQUEST)
                }
            }
        } else {
            mOutlineButton.visibility = View.GONE
        }

        // Reenstate last state if it was recorded
        val prefs = getPreferences(Context.MODE_PRIVATE)
        mDocView.setDisplayedViewIndex(prefs.getInt("page$mDocKey", 0))

        if (savedInstanceState == null || !savedInstanceState.getBoolean("ButtonsHidden", false))
            showButtons()

        if (savedInstanceState != null && savedInstanceState.getBoolean("SearchMode", false))
            searchModeOn()

        // Stick the document view and the buttons overlay into a parent view
        val layout = RelativeLayout(this)
        layout.setBackgroundColor(Color.DKGRAY)
        layout.addView(mDocView)
        layout.addView(mButtonsView)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            OUTLINE_REQUEST -> if (resultCode >= RESULT_FIRST_USER && mDocView != null) {
                mDocView.pushHistory()
                mDocView.setDisplayedViewIndex(resultCode - RESULT_FIRST_USER)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (mDocKey != null && mDocView != null) {
            if (mDocTitle != null) outState.putString("DocTitle", mDocTitle)

            // Store current page in the prefs against the file name,
            // so that we can pick it up each time the file is loaded
            // Other info is needed only for screen-orientation change,
            // so it can go in the bundle
            val prefs = getPreferences(Context.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putInt("page$mDocKey", mDocView.getDisplayedViewIndex())
            edit.apply()
        }

        if (!mButtonsVisible) outState.putBoolean("ButtonsHidden", true)

        if (mTopBarMode == TopBarMode.Search) outState.putBoolean("SearchMode", true)
    }

    override fun onPause() {
        super.onPause()

        mSearchTask.stop()

        if (mDocKey != null && mDocView != null) {
            val prefs = getPreferences(Context.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putInt("page$mDocKey", mDocView.getDisplayedViewIndex())
            edit.apply()
        }
    }

    override fun onDestroy() {
        mDocView.applyToChildren(object : ReaderView.ViewMapper() {
            override fun applyToView(view: View) {
                (view as PageView).releaseBitmaps()
            }
        })
        core?.onDestroy()
        core = null
        super.onDestroy()
    }

    private fun setButtonEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.setColorFilter(if (enabled) Color.argb(255, 255, 255, 255) else Color.argb(255, 128, 128, 128))
    }

    private fun setLinkHighlight(highlight: Boolean) {
        mLinkHighlight = highlight
        // LINK_COLOR tint
        mLinkButton.setColorFilter(if (highlight) Color.argb(0xFF, 0x00, 0x66, 0xCC) else Color.argb(0xFF, 255, 255, 255))
        // Inform pages of the change.
        mDocView.setLinksEnabled(highlight)
    }

    private fun showButtons() {
        if (core == null) return
        if (!mButtonsVisible) {
            mButtonsVisible = true
            // Update page number text and slider
            val index = mDocView.getDisplayedViewIndex()
            updatePageNumView(index)
            mPageSlider.max = (core!!.countPages() - 1) * mPageSliderRes
            mPageSlider.progress = index * mPageSliderRes
            if (mTopBarMode == TopBarMode.Search) {
                mSearchText.requestFocus()
                showKeyboard()
            }

            var anim = TranslateAnimation(0f, 0f, -mTopBarSwitcher.height.toFloat(), 0f)
            anim.duration = 200
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {
                    mTopBarSwitcher.visibility = View.VISIBLE
                }

                override fun onAnimationRepeat(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {}
            })
            mTopBarSwitcher.startAnimation(anim)

            anim = TranslateAnimation(0f, 0f, mPageSlider.height.toFloat(), 0f)
            anim.duration = 200
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {
                    mPageSlider.visibility = View.VISIBLE
                }

                override fun onAnimationRepeat(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    mPageNumberView.visibility = View.VISIBLE
                }
            })
            mPageSlider.startAnimation(anim)
        }
    }

    private fun hideButtons() {
        if (mButtonsVisible) {
            mButtonsVisible = false
            hideKeyboard()

            var anim = TranslateAnimation(0f, 0f, 0f, -mTopBarSwitcher.height.toFloat())
            anim.duration = 200
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}
                override fun onAnimationRepeat(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    mTopBarSwitcher.visibility = View.INVISIBLE
                }
            })
            mTopBarSwitcher.startAnimation(anim)

            anim = TranslateAnimation(0f, 0f, 0f, mPageSlider.height.toFloat())
            anim.duration = 200
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {
                    mPageNumberView.visibility = View.INVISIBLE
                }

                override fun onAnimationRepeat(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    mPageSlider.visibility = View.INVISIBLE
                }
            })
            mPageSlider.startAnimation(anim)
        }
    }

    private fun searchModeOn() {
        if (mTopBarMode != TopBarMode.Search) {
            mTopBarMode = TopBarMode.Search
            //Focus on EditTextWidget
            mSearchText.requestFocus()
            showKeyboard()
            mTopBarSwitcher.displayedChild = mTopBarMode.ordinal
        }
    }

    private fun searchModeOff() {
        if (mTopBarMode == TopBarMode.Search) {
            mTopBarMode = TopBarMode.Main
            hideKeyboard()
            mTopBarSwitcher.displayedChild = mTopBarMode.ordinal
            SearchTaskResult.set(null)
            // Make the ReaderView act on the change to mSearchTaskResult
            // via overridden onChildSetup method.
            mDocView.resetupChildren()
        }
    }

    private fun updatePageNumView(index: Int) {
        if (core == null) return
        mPageNumberView.text = String.format(Locale.ROOT, "%d / %d", index + 1, core!!.countPages())
    }

    private fun makeButtonsView() {
        mButtonsView = layoutInflater.inflate(R.layout.document_activity, null)
        mDocNameView = mButtonsView.findViewById(R.id.docNameText)
        mPageSlider = mButtonsView.findViewById(R.id.pageSlider)
        mPageNumberView = mButtonsView.findViewById(R.id.pageNumber)
        mSearchButton = mButtonsView.findViewById(R.id.searchButton)
        mOutlineButton = mButtonsView.findViewById(R.id.outlineButton)
        mTopBarSwitcher = mButtonsView.findViewById(R.id.switcher)
        mSearchBack = mButtonsView.findViewById(R.id.searchBack)
        mSearchFwd = mButtonsView.findViewById(R.id.searchForward)
        mSearchClose = mButtonsView.findViewById(R.id.searchClose)
        mSearchText = mButtonsView.findViewById(R.id.searchText)
        mLinkButton = mButtonsView.findViewById(R.id.linkButton)
        mLayoutButton = mButtonsView.findViewById(R.id.layoutButton)
        mTopBarSwitcher.visibility = View.INVISIBLE
        mPageNumberView.visibility = View.INVISIBLE

        mPageSlider.visibility = View.INVISIBLE
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm?.showSoftInput(mSearchText, 0)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm?.hideSoftInputFromWindow(mSearchText.windowToken, 0)
    }

    private fun search(direction: Int) {
        hideKeyboard()
        val displayPage = mDocView.getDisplayedViewIndex()
        val r = SearchTaskResult.get()
        val searchPage = r?.pageNumber ?: -1
        mSearchTask.go(mSearchText.text.toString(), direction, displayPage, searchPage)
    }

    override fun onSearchRequested(): Boolean {
        if (mButtonsVisible && mTopBarMode == TopBarMode.Search) {
            hideButtons()
        } else {
            showButtons()
            searchModeOn()
        }
        return super.onSearchRequested()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (mButtonsVisible && mTopBarMode != TopBarMode.Search) {
            hideButtons()
        } else {
            showButtons()
            searchModeOff()
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mDocView != null && !mDocView.popHistory()) {
            super.onBackPressed()
            if (mReturnToLibraryActivity) {
                val intent = packageManager.getLaunchIntentForPackage(componentName.packageName)
                startActivity(intent)
            }
        }
    }
}
