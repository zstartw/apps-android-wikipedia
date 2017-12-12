package org.wikipedia.feed.model

import android.net.Uri

import org.wikipedia.json.annotations.Required

class Thumbnail {
    @Required private lateinit var source: Uri
    private val height: Int = 0
    private val width: Int = 0

    fun source(): Uri {
        return source
    }

    fun width(): Int {
        return width
    }

    fun height(): Int {
        return height
    }
}
