package org.wikipedia.nearby

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.Icon
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.constants.MyLocationTracking
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Projection
import com.mapbox.services.android.telemetry.MapboxTelemetry
import com.mapbox.services.android.telemetry.location.LocationEngine
import com.mapbox.services.android.telemetry.location.LocationEngineListener
import com.mapbox.services.android.telemetry.location.LocationEngineProvider

import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.FragmentUtil
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.mwapi.MwQueryResponse
import org.wikipedia.history.HistoryEntry
import org.wikipedia.json.GsonMarshaller
import org.wikipedia.json.GsonUnmarshaller
import org.wikipedia.page.PageTitle
import org.wikipedia.richtext.RichTextUtil
import org.wikipedia.util.DeviceUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.PermissionUtil
import org.wikipedia.util.ResourceUtil
import org.wikipedia.util.StringUtil
import org.wikipedia.util.ThrowableUtil
import org.wikipedia.util.log.L

import java.util.ArrayList

import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import butterknife.Unbinder
import retrofit2.Call

/**
 * Displays a list of nearby pages.
 */
class NearbyFragment : Fragment() {

    @BindView(R.id.mapview) @JvmField var mapView: MapView? = null
    @BindView(R.id.osm_license) @JvmField var osmLicenseTextView: TextView? = null
    private var unbinder: Unbinder? = null

    private var mapboxMap: MapboxMap? = null
    private var markerIconPassive: Icon? = null
    private var locationEngine: LocationEngine? = null

    private var client: NearbyClient? = null
    private var lastResult: NearbyResult? = null

    private val locationChangeListener = LocationChangeListener()
    private var lastCameraPos: CameraPosition? = null
    private var firstLocationLock: Boolean = false

    private val fetchTaskRunnable = Runnable {
        if (!isResumed || mapboxMap == null) {
            return@Runnable
        }

        onLoading()

        val wiki = WikipediaApp.instance.getWikiSite()
        client!!.request(wiki, mapboxMap!!.cameraPosition.target.latitude,
                mapboxMap!!.cameraPosition.target.longitude, mapRadius,
                object : NearbyClient.Callback {
                    override fun success(call: Call<MwQueryResponse>,
                                         result: NearbyResult) {
                        if (!isResumed) {
                            return
                        }
                        lastResult = result
                        showNearbyPages(result)
                        onLoaded()
                    }

                    override fun failure(call: Call<MwQueryResponse>,
                                         caught: Throwable) {
                        if (!isResumed) {
                            return
                        }
                        val error = ThrowableUtil.getAppError(activity, caught)
                        Toast.makeText(activity, error.error, Toast.LENGTH_SHORT).show()
                        L.e(caught)
                        onLoaded()
                    }
                })
    }

    private val mapRadius: Double
        get() {
            if (mapboxMap == null) {
                return 0.0
            }

            val proj = mapboxMap!!.projection
            val leftTop = proj.fromScreenLocation(PointF(0.0f, 0.0f))
            val rightTop = proj.fromScreenLocation(PointF(mapView!!.width.toFloat(), 0.0f))
            val leftBottom = proj.fromScreenLocation(PointF(0.0f, mapView!!.height.toFloat()))
            val width = leftTop.distanceTo(rightTop)
            val height = leftTop.distanceTo(leftBottom)
            return Math.max(width, height) / 2
        }

    interface Callback {
        fun onLoading()
        fun onLoaded()
        fun onLoadPage(title: PageTitle, entrySource: Int, location: Location?)
    }

    companion object {

        private val NEARBY_LAST_RESULT = "lastRes"
        private val NEARBY_LAST_CAMERA_POS = "lastCameraPos"
        private val NEARBY_FIRST_LOCATION_LOCK = "firstLocationLock"
        private val GO_TO_LOCATION_PERMISSION_REQUEST = 50

        fun newInstance(): NearbyFragment {
            return NearbyFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = NearbyClient()

        Mapbox.getInstance(context.applicationContext,
                getString(R.string.mapbox_public_token))
        MapboxTelemetry.getInstance().isTelemetryEnabled = false
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater!!.inflate(R.layout.fragment_nearby, container, false)
        unbinder = ButterKnife.bind(this, view)

        markerIconPassive = IconFactory.getInstance(context)
                .fromBitmap(ResourceUtil.bitmapFromVectorDrawable(context,
                        R.drawable.ic_map_marker))

        osmLicenseTextView!!.text = StringUtil.fromHtml(getString(R.string.nearby_osm_license))
        osmLicenseTextView!!.movementMethod = LinkMovementMethod.getInstance()
        RichTextUtil.removeUnderlinesFromLinks(osmLicenseTextView!!)

        mapView!!.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        if (savedInstanceState != null) {
            lastCameraPos = savedInstanceState.getParcelable(NEARBY_LAST_CAMERA_POS)
            firstLocationLock = savedInstanceState.getBoolean(NEARBY_FIRST_LOCATION_LOCK)
            if (savedInstanceState.containsKey(NEARBY_LAST_RESULT)) {
                lastResult = GsonUnmarshaller.unmarshal(NearbyResult::class.java, savedInstanceState.getString(NEARBY_LAST_RESULT))
            }
        }

        locationEngine = LocationEngineProvider(context).obtainBestLocationEngineAvailable()
        locationEngine!!.addLocationEngineListener(locationChangeListener)

        onLoading()
        initializeMap()
        return view
    }

    override fun onStart() {
        mapView!!.onStart()
        super.onStart()
    }

    override fun onPause() {
        if (mapboxMap != null) {
            lastCameraPos = mapboxMap!!.cameraPosition
        }
        mapView!!.onPause()
        super.onPause()
    }

    override fun onResume() {
        mapView!!.onResume()
        super.onResume()
    }

    override fun onStop() {
        mapView!!.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        locationEngine!!.removeLocationEngineListener(locationChangeListener)
        mapView!!.onDestroy()
        mapboxMap = null
        unbinder!!.unbind()
        unbinder = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        WikipediaApp.instance.refWatcher!!.watch(this)
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
        if (mapView != null) {
            mapView!!.onSaveInstanceState(outState!!)
        }
        outState!!.putBoolean(NEARBY_FIRST_LOCATION_LOCK, firstLocationLock)
        if (mapboxMap != null) {
            outState.putParcelable(NEARBY_LAST_CAMERA_POS, mapboxMap!!.cameraPosition)
        }
        if (lastResult != null) {
            outState.putString(NEARBY_LAST_RESULT, GsonMarshaller.marshal(lastResult))
        }
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)

        if (mapView == null || mapboxMap == null) {
            return
        }

        if (isVisibleToUser && !firstLocationLock) {
            goToUserLocationOrPromptPermissions()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (mapView != null) {
            mapView!!.onLowMemory()
        }
    }

    @OnClick(R.id.user_location_button) internal fun onClick() {
        if (!locationPermitted()) {
            requestLocationRuntimePermissions(GO_TO_LOCATION_PERMISSION_REQUEST)
        } else if (mapboxMap != null) {
            goToUserLocation()
        }
    }

    private fun initializeMap() {
        mapView!!.getMapAsync(OnMapReadyCallback { mapboxMap ->
            if (!isAdded) {
                return@OnMapReadyCallback
            }
            this@NearbyFragment.mapboxMap = mapboxMap

            enableUserLocationMarker()
            mapboxMap.trackingSettings.myLocationTrackingMode = MyLocationTracking.TRACKING_NONE

            mapboxMap.setOnScrollListener { fetchNearbyPages() }
            mapboxMap.setOnMarkerClickListener { marker ->
                val page = findNearbyPageFromMarker(marker)
                if (page != null) {
                    val title = PageTitle(page.title, lastResult!!.wiki, page.thumbUrl)
                    onLoadPage(title, HistoryEntry.SOURCE_NEARBY, page.location)
                    true
                } else {
                    false
                }
            }

            if (lastCameraPos != null) {
                mapboxMap.cameraPosition = lastCameraPos!!
            } else {
                goToUserLocationOrPromptPermissions()
            }
            if (lastResult != null) {
                showNearbyPages(lastResult)
            }
        })
    }

    private fun findNearbyPageFromMarker(marker: Marker): NearbyPage? {
        for (page in lastResult!!.list) {
            if (page.title == marker.title) {
                return page
            }
        }
        return null
    }

    private fun locationPermitted(): Boolean {
        return ContextCompat.checkSelfPermission(WikipediaApp.instance,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationRuntimePermissions(requestCode: Int) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), requestCode)
        // once permission is granted/denied it will continue with onRequestPermissionsResult
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            GO_TO_LOCATION_PERMISSION_REQUEST -> if (PermissionUtil.isPermitted(grantResults) && mapboxMap != null) {
                goToUserLocation()
            } else {
                onLoaded()
                FeedbackUtil.showMessage(activity, R.string.nearby_zoom_to_location)
            }
            else -> throw RuntimeException("unexpected permission request code " + requestCode)
        }
    }

    private fun enableUserLocationMarker() {
        if (mapboxMap != null && locationPermitted()) {
            mapboxMap!!.isMyLocationEnabled = true
        }
    }

    private fun goToUserLocation() {
        if (mapboxMap == null || !userVisibleHint) {
            return
        }
        if (!DeviceUtil.isLocationServiceEnabled(context.applicationContext)) {
            showLocationDisabledSnackbar()
            return
        }

        enableUserLocationMarker()
        val location = mapboxMap!!.myLocation
        if (location != null) {
            goToLocation(location)
        }
        fetchNearbyPages()
    }

    private fun goToLocation(location: Location) {
        if (mapboxMap == null) {
            return
        }
        val pos = CameraPosition.Builder()
                .target(LatLng(location))
                .zoom(resources.getInteger(R.integer.map_default_zoom).toDouble())
                .build()
        mapboxMap!!.animateCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    private fun goToUserLocationOrPromptPermissions() {
        if (locationPermitted()) {
            goToUserLocation()
        } else if (userVisibleHint) {
            showLocationPermissionSnackbar()
        }
    }

    private fun showLocationDisabledSnackbar() {
        val snackbar = FeedbackUtil.makeSnackbar(activity,
                getString(R.string.location_service_disabled),
                FeedbackUtil.LENGTH_DEFAULT)
        snackbar.setAction(R.string.enable_location_service) {
            val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            context.startActivity(settingsIntent)
        }
        snackbar.show()
    }

    private fun showLocationPermissionSnackbar() {
        val snackbar = FeedbackUtil.makeSnackbar(activity,
                getString(R.string.location_permissions_enable_prompt),
                FeedbackUtil.LENGTH_DEFAULT)
        snackbar.setAction(R.string.location_permissions_enable_action) { requestLocationRuntimePermissions(GO_TO_LOCATION_PERMISSION_REQUEST) }
        snackbar.show()
    }

    private fun fetchNearbyPages() {
        val fetchTaskDelayMillis = 500
        mapView!!.removeCallbacks(fetchTaskRunnable)
        mapView!!.postDelayed(fetchTaskRunnable, fetchTaskDelayMillis.toLong())
    }

    private fun showNearbyPages(result: NearbyResult?) {
        if (mapboxMap == null || activity == null) {
            return
        }

        activity.invalidateOptionsMenu()
        // Since Marker is a descendant of Annotation, this will remove all Markers.
        mapboxMap!!.removeAnnotations()

        val optionsList = ArrayList<MarkerOptions>()
        for (item in result!!.list) {
            if (item.location != null) {
                optionsList.add(createMarkerOptions(item))
            }
        }
        mapboxMap!!.addMarkers(optionsList)
    }

    private fun createMarkerOptions(page: NearbyPage): MarkerOptions {
        val location = page.location
        return MarkerOptions()
                .position(LatLng(location!!.latitude, location.longitude))
                .title(page.title)
                .icon(markerIconPassive)
    }

    private fun onLoading() {
        val callback = callback()
        callback?.onLoading()
    }

    private fun onLoaded() {
        val callback = callback()
        callback?.onLoaded()
    }

    private fun onLoadPage(title: PageTitle, entrySource: Int, location: Location?) {
        val callback = callback()
        callback?.onLoadPage(title, entrySource, location)
    }
    private inner class LocationChangeListener : LocationEngineListener {

        override fun onConnected() {}
        override fun onLocationChanged(location: Location) {
            if (!firstLocationLock) {
                goToUserLocation()
                firstLocationLock = true
            }
        }

    }

    private fun callback(): Callback? {
        return FragmentUtil.getCallback(this, Callback::class.java)
    }
}
