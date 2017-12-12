package org.wikipedia.feed.image

import org.wikipedia.feed.model.Thumbnail
import org.wikipedia.json.annotations.Required

class FeaturedImage {
    @Required private lateinit var title: String
    @Required private lateinit var thumbnail: Thumbnail
    @Required private lateinit var image: Thumbnail
    private val description: Description? = null

    fun title(): String {
        return title
    }

    fun thumbnail(): Thumbnail {
        return thumbnail
    }

    fun image(): Thumbnail {
        return image
    }

    fun description(): String? {
        return description?.text
    }

    fun descriptionLang(): String? {
        return description?.lang
    }

    /**
     * An object containing a description of the featured image and a language code for that description.
     *
     * The content service gets all available translations of the featured image description and
     * returns the translation for the request wiki language, if available.  Otherwise it defaults
     * to providing the English translation.
     */
    private class Description {
        lateinit var text: String
        lateinit var lang: String

        fun text(): String {
            return text
        }

        fun lang(): String {
            return lang
        }
    }
}
