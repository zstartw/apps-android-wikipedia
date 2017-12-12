package org.wikipedia.nearby

import android.support.annotation.VisibleForTesting

import org.wikipedia.Constants
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.mwapi.MwException
import org.wikipedia.dataclient.mwapi.MwQueryResponse
import org.wikipedia.dataclient.retrofit.MwCachedService
import org.wikipedia.dataclient.retrofit.WikiCachedService

import java.util.ArrayList
import java.util.Locale

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

internal class NearbyClient {

    private val cachedService = MwCachedService(Service::class.java)

    interface Callback {
        fun success(call: Call<MwQueryResponse>, result: NearbyResult)
        fun failure(call: Call<MwQueryResponse>, caught: Throwable)
    }

    fun request(wiki: WikiSite, latitude: Double, longitude: Double,
                radius: Double, cb: Callback): Call<MwQueryResponse> {
        return request(wiki, cachedService.service(wiki), latitude, longitude, radius, cb)
    }

    @VisibleForTesting
    fun request(wiki: WikiSite, service: Service,
                latitude: Double, longitude: Double, radius: Double,
                cb: Callback): Call<MwQueryResponse> {
        var radius = radius
        val latLng = String.format(Locale.ROOT, "%f|%f", latitude, longitude)
        radius = Math.min(MAX_RADIUS.toDouble(), radius)
        val call = service.request(latLng, radius, Constants.PREFERRED_THUMB_SIZE)
        call.enqueue(object : retrofit2.Callback<MwQueryResponse> {
            override fun onResponse(call: Call<MwQueryResponse>, response: Response<MwQueryResponse>) {
                // The API results here are unusual in that, if there are no valid results, the
                // response won't even have a "query" key.  Nor will we receive an error.
                // Accordingly, let's assume that we just got an empty result set unless the
                // API explicitly tells us we have an error.
                if (response.body()!!.success()) {

                    cb.success(call, NearbyResult(wiki, response.body()!!.query()!!.nearbyPages()))
                } else if (response.body()!!.hasError()) {

                    cb.failure(call, MwException(response.body()!!.error!!))
                } else {
                    cb.success(call, NearbyResult(wiki, ArrayList()))
                }
            }

            override fun onFailure(call: Call<MwQueryResponse>, t: Throwable) {
                cb.failure(call, t)
            }
        })
        return call
    }

    @VisibleForTesting internal interface Service {
        @GET("w/api.php?action=query&format=json&formatversion=2&prop=coordinates|pageimages|pageterms"
                + "&colimit=50&piprop=thumbnail&pilicense=any&wbptterms=description"
                + "&generator=geosearch&ggslimit=50&continue=")
        fun request(@Query("ggscoord") coord: String,
                    @Query("ggsradius") radius: Double,
                    @Query("pithumbsize") thumbsize: Int): Call<MwQueryResponse>
    }

    companion object {

        private val MAX_RADIUS = 10000
    }
}
