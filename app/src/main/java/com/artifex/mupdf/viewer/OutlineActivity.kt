package com.artifex.mupdf.viewer

import android.app.ListActivity
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import java.io.Serializable

class OutlineActivity : ListActivity() {

//    companion object {
//        class Item(val title: String, val page: Int) : Serializable {
//            override fun toString(): String {
//                return title
//            }
//        }
//    }

    private lateinit var adapter: ArrayAdapter<Item>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listAdapter = adapter

        val idx = intent.getIntExtra("PALLETBUNDLE", -1)
        val bundle = Pallet.receiveBundle(idx)
        if (bundle != null) {
            val currentPage = bundle.getInt("POSITION")
            @Suppress("UNCHECKED_CAST")
            val outline = bundle.getSerializable("OUTLINE") as ArrayList<Item>
            var found = -1
            for (i in outline.indices) {
                val item = outline[i]
                if (found < 0 && item.page >= currentPage)
                    found = i
                adapter.add(item)
            }
            if (found >= 0)
                setSelection(found)
        }
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val item = adapter.getItem(position)
        setResult(RESULT_FIRST_USER + (item?.page ?: 0))
        finish()
    }
}
