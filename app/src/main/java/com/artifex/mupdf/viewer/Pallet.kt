package com.artifex.mupdf.viewer

import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

class Pallet private constructor() {

    private val pallet = ConcurrentHashMap<Int, Any>()
    private var sequenceNumber = 0

    companion object {
        private val instance = Pallet()

        private fun getInstance(): Pallet {
            return instance
        }

        @Synchronized
        fun sendBundle(bundle: Bundle): Int {
            val instance = getInstance()
            val i = instance.sequenceNumber++
            if (instance.sequenceNumber < 0) instance.sequenceNumber = 0
            instance.pallet[i] = bundle
            return i
        }

        fun receiveBundle(number: Int): Bundle? {
            val instance = getInstance()
            val bundle = instance.pallet[number] as? Bundle
            if (bundle != null) {
                instance.pallet.remove(number)
            }
            return bundle
        }

        fun hasBundle(number: Int): Boolean {
            return getInstance().pallet.containsKey(number)
        }
    }
}
