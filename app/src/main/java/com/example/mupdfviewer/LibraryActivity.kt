package com.example.mupdfviewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.artifex.mupdf.viewer.DocumentActivity

class LibraryActivity : Activity() {
    private val FILE_REQUEST = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                // open the mime-types we know about
                "application/pdf",
                "application/vnd.ms-xpsdocument",
                "application/oxps",
                "application/x-cbz",
                "application/vnd.comicbook+zip",
                "application/epub+zip",
                "application/x-fictionbook",
                "application/x-mobipocket-ebook",
                // ... and the ones android doesn't know about
                "application/octet-stream"
            ))
        }

        startActivityForResult(intent, FILE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    data?.let {
                        val intent = Intent(this, DocumentActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                            action = Intent.ACTION_VIEW
                            setDataAndType(data.data, data.type)
                            putExtra("$packageName.ReturnToLibraryActivity", 1)
                        }
                        startActivity(intent)
                    }
                    finish()
                }
                Activity.RESULT_CANCELED -> {
                    finish()
                }
            }
        }
    }
}
