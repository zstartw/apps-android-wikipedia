package org.wikipedia.nearby

import android.location.Location
import android.support.annotation.VisibleForTesting

import org.wikipedia.dataclient.mwapi.MwQueryPage

class NearbyPage {
    var title: String
    var thumbUrl: String? = null
    var location: Location? = null

    /** calculated externally  */
    /**
     * Returns the distance from the point where the device is.
     * Calculated later and can change. Needs to be set first by #setDistance!
     */
    var distance: Int = 0

    constructor(page: MwQueryPage) {
        title = page.title()
        thumbUrl = page.thumbUrl()
        val coordinates = page.coordinates()
        if (coordinates != null && !coordinates.isEmpty()) {
            location = Location(title)
            location!!.latitude = page.coordinates()!![0].lat()
            location!!.longitude = page.coordinates()!![0].lon()
        }
    }

    @VisibleForTesting constructor(title: String, location: Location?) {
        this.title = title
        this.location = location
    }

    override fun toString(): String {
        return ("NearbyPage{"
                + "title='" + title + '\''
                + ", thumbUrl='" + thumbUrl + '\''
                + ", location=" + location + '\''
                + ", distance='" + distance
                + '}')
    }
}
