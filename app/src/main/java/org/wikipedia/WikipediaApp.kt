package org.wikipedia

import android.app.Activity
import android.app.Application
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.support.annotation.IntRange
import android.support.v7.app.AppCompatDelegate
import android.view.Window
import android.webkit.WebView

import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.squareup.leakcanary.LeakCanary
import com.squareup.leakcanary.RefWatcher
import com.squareup.otto.Bus

import org.wikipedia.analytics.FunnelManager
import org.wikipedia.analytics.SessionFunnel
import org.wikipedia.auth.AccountUtil
import org.wikipedia.concurrency.ThreadSafeBus
import org.wikipedia.connectivity.NetworkConnectivityReceiver
import org.wikipedia.crash.CrashReporter
import org.wikipedia.crash.hockeyapp.HockeyAppCrashReporter
import org.wikipedia.database.Database
import org.wikipedia.database.DatabaseClient
import org.wikipedia.dataclient.SharedPreferenceCookieManager
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.fresco.DisabledCache
import org.wikipedia.dataclient.mwapi.MwQueryResponse
import org.wikipedia.dataclient.okhttp.CacheableOkHttpNetworkFetcher
import org.wikipedia.dataclient.okhttp.OkHttpConnectionFactory
import org.wikipedia.edit.summaries.EditSummary
import org.wikipedia.events.ChangeTextSizeEvent
import org.wikipedia.events.ThemeChangeEvent
import org.wikipedia.history.HistoryEntry
import org.wikipedia.language.AcceptLanguageUtil
import org.wikipedia.language.AppLanguageState
import org.wikipedia.login.UserIdClient
import org.wikipedia.notifications.NotificationPollBroadcastReceiver
import org.wikipedia.pageimages.PageImage
import org.wikipedia.readinglist.database.ReadingListRow
import org.wikipedia.readinglist.page.ReadingListPageRow
import org.wikipedia.readinglist.page.database.ReadingListPageHttpRow
import org.wikipedia.readinglist.page.database.disk.ReadingListPageDiskRow
import org.wikipedia.search.RecentSearch
import org.wikipedia.settings.Prefs
import org.wikipedia.settings.RemoteConfig
import org.wikipedia.theme.Theme
import org.wikipedia.useroption.UserOption
import org.wikipedia.useroption.database.UserOptionDao
import org.wikipedia.useroption.database.UserOptionRow
import org.wikipedia.useroption.sync.UserOptionContentResolver
import org.wikipedia.util.DimenUtil
import org.wikipedia.util.ReleaseUtil
import org.wikipedia.util.log.L
import org.wikipedia.views.ViewAnimations
import org.wikipedia.zero.WikipediaZeroHandler

import java.util.Collections
import java.util.HashMap
import java.util.Random
import java.util.UUID

import retrofit2.Call

import org.apache.commons.lang3.StringUtils.defaultString
import org.wikipedia.database.http.HttpRow
import org.wikipedia.readinglist.page.database.disk.DiskRow
import org.wikipedia.settings.Prefs.getTextSizeMultiplier
import org.wikipedia.util.DimenUtil.getFontSizeFromSp
import org.wikipedia.util.ReleaseUtil.getChannel

class WikipediaApp : Application() {

    private val databaseClients = Collections.synchronizedMap(HashMap<Class<*>, DatabaseClient<*>>())
    private var appLanguageState: AppLanguageState? = null
    private val notificationReceiver = NotificationPollBroadcastReceiver()
    private val connectivityReceiver = NetworkConnectivityReceiver()
    private val userIdClient = UserIdClient()
    private var crashReporter: CrashReporter? = null
    private var currentTheme = Theme.getFallback()

    val remoteConfig = RemoteConfig()
    var database: Database? = null
    var refWatcher: RefWatcher? = null
    var wikipediaZeroHandler: WikipediaZeroHandler? = null
    var funnelManager: FunnelManager? = null
    var sessionFunnel: SessionFunnel? = null
    var bus: Bus? = null
    var wiki: WikiSite? = null

    var userAgent: String? = null
        get() {
            L.d("user agent:")
            if (field == null){
                var channel = getChannel(this)
                channel = if (channel == "") channel else " " + channel
                field = String.format("WikipediaApp/%s (Android %s; %s)%s",
                        BuildConfig.VERSION_NAME,
                        Build.VERSION.RELEASE,
                        getString(R.string.device_type),
                        channel
                )
            }
            return field
        }

    var input: String? = null
        get() {
            L.d("input======")
            return "name"
        }

    var appLanguageCode: String
        get() = defaultString(appLanguageState!!.appLanguageCode)
        set(code) {
            appLanguageState!!.appLanguageCode = code
            resetWikiSite()
        }

    val appOrSystemLanguageCode: String
        get() {
            val code = appLanguageState!!.appOrSystemLanguageCode

            if (AccountUtil.getUserIdForLanguage(code) == 0) {
                getUserIdForLanguage(code)
            }
            return code
        }

    val systemLanguageCode: String
        get() = appLanguageState!!.systemLanguageCode

    val appOrSystemLanguageLocalizedName: String?
        get() = appLanguageState!!.appOrSystemLanguageLocalizedName

    val mruLanguageCodes: List<String>
        get() = appLanguageState!!.mruLanguageCodes

    val appMruLanguageCodes: List<String>
        get() = appLanguageState!!.appMruLanguageCodes

    /**
     * Get this app's unique install ID, which is a UUID that should be unique for each install
     * of the app. Useful for anonymous analytics.
     * @return Unique install ID for this app.
     */
    val appInstallID: String
        get() {
            var id = Prefs.getAppInstallId()
            if (id == null) {
                id = UUID.randomUUID().toString()
                Prefs.setAppInstallId(id)
            }
            return id
        }

    /**
     * Get an integer-valued random ID. This is typically used to determine global EventLogging
     * sampling, that is, whether the user's instance of the app sends any events or not. This is a
     * pure technical measure which is necessary to prevent overloading EventLogging with too many
     * events. This value will persist for the lifetime of the app.
     *
     * Don't use this method when running to determine whether or not the user falls into a control
     * or test group in any kind of tests (such as A/B tests), as that would introduce sampling
     * biases which would invalidate the test.
     * @return Integer ID for event log sampling.
     */
    val eventLogSamplingID: Int
        @IntRange(from = 0)
        get() = EVENT_LOG_TESTING_ID

    companion object {
        private val EVENT_LOG_TESTING_ID = Random().nextInt(Integer.MAX_VALUE)

        lateinit var instance: WikipediaApp
    }

    init {
        instance = this
    }

    /**
     * Gets the currently-selected theme for the app.
     * @return Theme that is currently selected, which is the actual theme ID that can
     * be passed to setTheme() when creating an activity.
     */
    fun getCurrentTheme(): Theme {
        return currentTheme
    }

    fun setMruLanguageCode(code: String?) {
        appLanguageState!!.setMruLanguageCode(code)
    }

    fun getAppLanguageLocalizedName(code: String): String? {
        return appLanguageState!!.getAppLanguageLocalizedName(code)
    }

    fun getAppLanguageCanonicalName(code: String): String? {
        return appLanguageState!!.getAppLanguageCanonicalName(code)
    }

    override fun onCreate() {
        super.onCreate()

        wikipediaZeroHandler = WikipediaZeroHandler(this)

        // HockeyApp exception handling interferes with the test runner, so enable it only for
        // beta and stable releases
        if (!ReleaseUtil.isPreBetaRelease()) {
            initExceptionHandling()
        }

        refWatcher = if (Prefs.isMemoryLeakTestEnabled()) LeakCanary.install(this) else RefWatcher.DISABLED

        // See Javadocs and http://developer.android.com/tools/support-library/index.html#rev23-4-0
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        bus = ThreadSafeBus()

        ViewAnimations.init(resources)
        currentTheme = unmarshalCurrentTheme()

        appLanguageState = AppLanguageState(this)
        funnelManager = FunnelManager(this)
        sessionFunnel = SessionFunnel(this)
        database = Database(this)

        enableWebViewDebugging()

        val config = ImagePipelineConfig.newBuilder(this)
                .setNetworkFetcher(CacheableOkHttpNetworkFetcher(OkHttpConnectionFactory.getClient()))
                .setFileCacheFactory(DisabledCache.factory())
                .build()
        Fresco.initialize(this, config)

        // TODO: Remove when user accounts have been migrated to AccountManager (June 2018)
        AccountUtil.migrateAccountFromSharedPrefs()

        UserOptionContentResolver.registerAppSyncObserver(this)

        registerConnectivityReceiver()

        listenForNotifications()
    }

    /*fun getUserAgent(): String {
        if (userAgent == null) {
            var channel = getChannel(this)
            channel = if (channel == "") channel else " " + channel
            userAgent = String.format("WikipediaApp/%s (Android %s; %s)%s",
                    BuildConfig.VERSION_NAME,
                    Build.VERSION.RELEASE,
                    getString(R.string.device_type),
                    channel
            )
        }
        return userAgent!!
    }*/

    /**
     * @return the value that should go in the Accept-Language header.
     */
    fun getAcceptLanguage(wiki: WikiSite?): String {
        val wikiLang = if (wiki == null || "meta" == wiki.languageCode())
            ""
        else
            defaultString(wiki.languageCode())
        return AcceptLanguageUtil.getAcceptLanguage(wikiLang, appLanguageCode,
                appLanguageState!!.systemLanguageCode)
    }

    fun getWikiSite(): WikiSite{
        // TODO: why don't we ensure that the app language hasn't changed here instead of the client?
        if (wiki == null) {
            val lang = if (Prefs.getMediaWikiBaseUriSupportsLangCode()) appOrSystemLanguageCode else ""
            wiki = WikiSite.forLanguageCode(lang)
        }
        return wiki!!
    }

    fun <T> getDatabaseClient(cls: Class<T>): DatabaseClient<T> {
        if (!databaseClients.containsKey(cls)) {
            val client: DatabaseClient<*>
            if (cls == HistoryEntry::class.java) {
                client = DatabaseClient(this, HistoryEntry.DATABASE_TABLE)
            } else if (cls == PageImage::class.java) {
                client = DatabaseClient(this, PageImage.DATABASE_TABLE)
            } else if (cls == RecentSearch::class.java) {
                client = DatabaseClient(this, RecentSearch.DATABASE_TABLE)
            } else if (cls == EditSummary::class.java) {
                client = DatabaseClient(this, EditSummary.DATABASE_TABLE)
            } else if (cls == UserOption::class.java) {
                client = DatabaseClient(this, UserOptionRow.DATABASE_TABLE)
            } else if (cls == UserOptionRow::class.java) {
                client = DatabaseClient<HttpRow<UserOption>>(this, UserOptionRow.HTTP_DATABASE_TABLE)
            } else if (cls == ReadingListPageRow::class.java) {
                client = DatabaseClient(this, ReadingListPageRow.DATABASE_TABLE)
            } else if (cls == ReadingListPageHttpRow::class.java) {
                client = DatabaseClient<HttpRow<ReadingListPageRow>>(this, ReadingListPageRow.HTTP_DATABASE_TABLE)
            } else if (cls == ReadingListPageDiskRow::class.java) {
                client = DatabaseClient<DiskRow<ReadingListPageRow>>(this, ReadingListPageRow.DISK_DATABASE_TABLE)
            } else if (cls == ReadingListRow::class.java) {
                client = DatabaseClient(this, ReadingListRow.DATABASE_TABLE)
            } else {
                throw RuntimeException("No persister found for class " + cls.canonicalName)
            }
            databaseClients.put(cls, client)
        }

        return databaseClients[cls] as DatabaseClient<T>
    }

    /**
     * Sets the theme of the app. If the new theme is the same as the current theme, nothing happens.
     * Otherwise, an event is sent to notify of the theme change.
     */
    fun setCurrentTheme(theme: Theme) {
        if (theme != currentTheme) {
            currentTheme = theme
            Prefs.setThemeId(currentTheme.marshallingId)
            bus!!.post(ThemeChangeEvent())
        }
    }

    fun setFontSizeMultiplier(multiplier: Int): Boolean {
        var multiplier = multiplier
        val minMultiplier = resources.getInteger(R.integer.minTextSizeMultiplier)
        val maxMultiplier = resources.getInteger(R.integer.maxTextSizeMultiplier)
        if (multiplier < minMultiplier) {
            multiplier = minMultiplier
        } else if (multiplier > maxMultiplier) {
            multiplier = maxMultiplier
        }
        if (multiplier != getTextSizeMultiplier()) {
            Prefs.setTextSizeMultiplier(multiplier)
            bus!!.post(ChangeTextSizeEvent())
            return true
        }
        return false
    }

    fun putCrashReportProperty(key: String, value: String) {
        if (!ReleaseUtil.isPreBetaRelease()) {
            crashReporter!!.putReportProperty(key, value)
        }
    }

    fun checkCrashes(activity: Activity) {
        if (!ReleaseUtil.isPreBetaRelease()) {
            crashReporter!!.checkCrashes(activity)
        }
    }

    fun runOnMainThread(runnable: Runnable) {
        Handler(mainLooper).post(runnable)
    }

    /**
     * Gets the current size of the app's font. This is given as a device-specific size (not "sp"),
     * and can be passed directly to setTextSize() functions.
     * @param window The window on which the font will be displayed.
     * @return Actual current size of the font.
     */
    fun getFontSize(window: Window): Float {
        return getFontSizeFromSp(window,
                resources.getDimension(R.dimen.textSize)) * (1.0f + getTextSizeMultiplier() * DimenUtil.getFloat(R.dimen.textSizeMultiplierFactor))
    }

    fun resetWikiSite() {
        wiki = null
    }

    fun logOut() {
        L.v("logging out")
        AccountUtil.removeAccount()
        UserOptionDao.instance().clear()
        SharedPreferenceCookieManager.getInstance().clearAllCookies()
    }

    fun listenForNotifications() {
        if (!Prefs.suppressNotificationPolling()) {
            notificationReceiver.startPollTask(this)
        }
    }

    private fun initExceptionHandling() {
        crashReporter = HockeyAppCrashReporter(getString(R.string.hockeyapp_app_id), consentAccessor())
        crashReporter!!.registerCrashHandler(this)

        L.setRemoteLogger(crashReporter)
    }

    private fun consentAccessor(): CrashReporter.AutoUploadConsentAccessor {
        return CrashReporter.AutoUploadConsentAccessor { Prefs.isCrashReportAutoUploadEnabled() }
    }

    private fun enableWebViewDebugging() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    private fun unmarshalCurrentTheme(): Theme {
        val id = Prefs.getThemeId()
        var result = Theme.ofMarshallingId(id)
        if (result == null) {
            L.d("Theme id=$id is invalid, using fallback.")
            result = Theme.getFallback()
        }
        return result!!
    }

    // Register here rather than in AndroidManifest.xml so that we can target Android N.
    // https://developer.android.com/topic/performance/background-optimization.html#connectivity-action
    private fun registerConnectivityReceiver() {
        registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }

    private fun getUserIdForLanguage(code: String) {
        if (!AccountUtil.isLoggedIn()) {
            return
        }
        val wikiSite = WikiSite.forLanguageCode(code)
        userIdClient.request(wikiSite, object : UserIdClient.Callback {
            override fun success(call: Call<MwQueryResponse>, id: Int) {
                if (AccountUtil.isLoggedIn()) {
                    AccountUtil.putUserIdForLanguage(code, id)
                    L.v("Found user ID $id for $code")
                }
            }

            override fun failure(call: Call<MwQueryResponse>, caught: Throwable) {
                L.e("Failed to get user ID for " + code, caught)
            }
        })
    }
}
