package org.wikipedia.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.support.v4.app.ActivityOptionsCompat
import android.support.v4.app.Fragment
import android.support.v4.view.ViewPager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import org.apache.commons.lang3.StringUtils
import org.wikipedia.BackPressedHandler
import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.FragmentUtil
import org.wikipedia.analytics.GalleryFunnel
import org.wikipedia.analytics.IntentFunnel
import org.wikipedia.analytics.LoginFunnel
import org.wikipedia.feed.FeedFragment
import org.wikipedia.feed.featured.FeaturedArticleCardView
import org.wikipedia.feed.image.FeaturedImage
import org.wikipedia.feed.image.FeaturedImageCard
import org.wikipedia.feed.news.NewsActivity
import org.wikipedia.feed.news.NewsItemCard
import org.wikipedia.feed.view.HorizontalScrollingListCardItemView
import org.wikipedia.gallery.GalleryActivity
import org.wikipedia.gallery.ImagePipelineBitmapGetter
import org.wikipedia.gallery.MediaDownloadReceiver
import org.wikipedia.history.HistoryEntry
import org.wikipedia.history.HistoryFragment
import org.wikipedia.login.LoginActivity
import org.wikipedia.navtab.NavTab
import org.wikipedia.navtab.NavTabFragmentPagerAdapter
import org.wikipedia.navtab.NavTabLayout
import org.wikipedia.nearby.NearbyFragment
import org.wikipedia.page.ExclusiveBottomSheetPresenter
import org.wikipedia.page.PageActivity
import org.wikipedia.page.PageTitle
import org.wikipedia.page.linkpreview.LinkPreviewDialog
import org.wikipedia.random.RandomActivity
import org.wikipedia.readinglist.AddToReadingListDialog
import org.wikipedia.search.SearchFragment
import org.wikipedia.search.SearchInvokeSource
import org.wikipedia.settings.Prefs
import org.wikipedia.util.ClipboardUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.PermissionUtil
import org.wikipedia.util.ShareUtil
import org.wikipedia.util.log.L

import java.io.File
import java.util.concurrent.TimeUnit

import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnPageChange
import butterknife.Unbinder

class MainFragment : Fragment(), BackPressedHandler, FeedFragment.Callback, NearbyFragment.Callback, HistoryFragment.Callback, SearchFragment.Callback, LinkPreviewDialog.Callback {
    @BindView(R.id.fragment_main_view_pager)  @JvmField var viewPager: ViewPager? = null
    @BindView(R.id.fragment_main_nav_tab_layout) @JvmField var tabLayout: NavTabLayout? = null
    private var unbinder: Unbinder? = null
    private val bottomSheetPresenter = ExclusiveBottomSheetPresenter()
    private val downloadReceiver = MediaDownloadReceiver()
    private val downloadReceiverCallback = MediaDownloadReceiverCallback()

    // The permissions request API doesn't take a callback, so in the event we have to
    // ask for permission to download a featured image from the feed, we'll have to hold
    // the image we're waiting for permission to download as a bit of state here. :(
    private var pendingDownloadImage: FeaturedImage? = null

    interface Callback {
        fun getOverflowMenuAnchor(): View
        fun onTabChanged(tab: NavTab)
        fun onSearchOpen()
        fun onSearchClose(shouldFinishActivity: Boolean)
        fun updateToolbarElevation(elevate: Boolean)
    }

    override fun onCreateView(inflater: LayoutInflater?,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        val view = inflater!!.inflate(R.layout.fragment_main, container, false)
        unbinder = ButterKnife.bind(this, view)

        viewPager?.adapter = NavTabFragmentPagerAdapter(childFragmentManager)
        tabLayout?.setOnNavigationItemSelectedListener { item ->
            val fragment = (viewPager?.adapter as NavTabFragmentPagerAdapter).currentFragment
            if (fragment is FeedFragment && item.order == 0) {
                fragment.scrollToTop()
            }
            viewPager?.currentItem = item.order
            true
        }

        if (savedInstanceState == null) {
            handleIntent(activity.intent)
        }
        return view
    }

    override fun onPause() {
        super.onPause()
        downloadReceiver.setCallback(null)
        context.unregisterReceiver(downloadReceiver)
    }

    override fun onResume() {
        super.onResume()
        context.registerReceiver(downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        downloadReceiver.setCallback(downloadReceiverCallback)
        // update toolbar, since Tab count might have changed
        activity.invalidateOptionsMenu()
        // reset the last-page-viewed timer
        Prefs.pageLastShown(0)
    }

    override fun onDestroyView() {
        unbinder!!.unbind()
        unbinder = null
        super.onDestroyView()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == Constants.ACTIVITY_REQUEST_VOICE_SEARCH
                && resultCode == Activity.RESULT_OK && data != null
                && data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) != null) {
            val searchQuery = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)[0]
            openSearchFragment(SearchInvokeSource.VOICE, searchQuery)
        } else if (requestCode == Constants.ACTIVITY_REQUEST_GALLERY && resultCode == GalleryActivity.ACTIVITY_RESULT_PAGE_SELECTED) {
            startActivity(data)
        } else if (requestCode == Constants.ACTIVITY_REQUEST_LOGIN && resultCode == LoginActivity.RESULT_LOGIN_SUCCESS) {
            FeedbackUtil.showMessage(this, R.string.login_success_toast)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            Constants.ACTIVITY_REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION -> if (PermissionUtil.isPermitted(grantResults)) {
                if (pendingDownloadImage != null) {
                    download(pendingDownloadImage!!)
                }
            } else {
                setPendingDownload(null)
                L.i("Write permission was denied by user")
                FeedbackUtil.showMessage(this,
                        R.string.gallery_save_image_write_permission_rationale)
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    fun handleIntent(intent: Intent) {
        val funnel = IntentFunnel(WikipediaApp.instance)
        if (intent.hasExtra(Constants.INTENT_APP_SHORTCUT_SEARCH)) {
            openSearchFragment(SearchInvokeSource.APP_SHORTCUTS, null)
        } else if (intent.hasExtra(Constants.INTENT_APP_SHORTCUT_RANDOM)) {
            startActivity(RandomActivity.newIntent(activity, RandomActivity.INVOKE_SOURCE_SHORTCUT))
        } else if (Intent.ACTION_SEND == intent.action && Constants.PLAIN_TEXT_MIME_TYPE == intent.type) {
            funnel.logShareIntent()
            openSearchFragment(SearchInvokeSource.INTENT_SHARE,
                    intent.getStringExtra(Intent.EXTRA_TEXT))
        } else if (Intent.ACTION_PROCESS_TEXT == intent.action
                && Constants.PLAIN_TEXT_MIME_TYPE == intent.type
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            funnel.logProcessTextIntent()
            openSearchFragment(SearchInvokeSource.INTENT_PROCESS_TEXT,
                    intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        } else if (intent.hasExtra(Constants.INTENT_SEARCH_FROM_WIDGET)) {
            funnel.logSearchWidgetTap()
            openSearchFragment(SearchInvokeSource.WIDGET, null)
        } else if (intent.hasExtra(Constants.INTENT_EXTRA_DELETE_READING_LIST)) {
            goToTab(NavTab.READING_LISTS)
        } else if (lastPageViewedWithin(1) && !intent.hasExtra(Constants.INTENT_RETURN_TO_MAIN)) {
            startActivity(PageActivity.newIntent(context))
        }
    }

    override fun onFeedTabListRequested() {
        startActivity(PageActivity.newIntentForTabList(context))
    }

    override fun onFeedSearchRequested() {
        openSearchFragment(SearchInvokeSource.FEED_BAR, null)
    }

    override fun onFeedVoiceSearchRequested() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        try {
            startActivityForResult(intent, Constants.ACTIVITY_REQUEST_VOICE_SEARCH)
        } catch (a: ActivityNotFoundException) {
            FeedbackUtil.showMessage(this, R.string.error_voice_search_not_available)
        }

    }

    override fun onFeedSelectPage(entry: HistoryEntry) {
        startActivity(PageActivity.newIntentForNewTab(context, entry, entry.title))
    }

    override fun onFeedSelectPageFromExistingTab(entry: HistoryEntry) {
        startActivity(PageActivity.newIntentForExistingTab(context, entry, entry.title))
    }

    override fun onFeedAddPageToList(entry: HistoryEntry) {
        bottomSheetPresenter.show(childFragmentManager,
                AddToReadingListDialog.newInstance(entry.title,
                        AddToReadingListDialog.InvokeSource.FEED))
    }

    override fun onFeedAddFeaturedPageToList(view: FeaturedArticleCardView,
                                             entry: HistoryEntry) {
        bottomSheetPresenter.show(childFragmentManager,
                AddToReadingListDialog.newInstance(entry.title,
                        AddToReadingListDialog.InvokeSource.FEED
                ) { view.updateFooter() })
    }

    override fun onFeedRemovePageFromList(view: FeaturedArticleCardView,
                                          entry: HistoryEntry) {
        FeedbackUtil.showMessage(activity,
                getString(R.string.reading_list_item_deleted, entry.title.displayText))
        view.updateFooter()
    }

    override fun onFeedSharePage(entry: HistoryEntry) {
        ShareUtil.shareText(context, entry.title)
    }

    override fun onFeedNewsItemSelected(card: NewsItemCard, view: HorizontalScrollingListCardItemView) {
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(activity, view.imageView, getString(R.string.transition_news_item))
        startActivity(NewsActivity.newIntent(activity, card.item(), card.wikiSite()), options.toBundle())
    }

    override fun onFeedShareImage(card: FeaturedImageCard) {
        val thumbUrl = card.baseImage().thumbnail().source().toString()
        val fullSizeUrl = card.baseImage().image().source().toString()
        object : ImagePipelineBitmapGetter(thumbUrl) {
            override fun onSuccess(bitmap: Bitmap?) {
                if (bitmap != null) {
                    ShareUtil.shareImage(context,
                            bitmap,
                            File(thumbUrl).name,
                            ShareUtil.getFeaturedImageShareSubject(context, card.age()),
                            fullSizeUrl)
                } else {
                    FeedbackUtil.showMessage(this@MainFragment, getString(R.string.gallery_share_error, card.baseImage().title()))
                }
            }
        }.get()
    }

    override fun onFeedDownloadImage(image: FeaturedImage) {
        if (!PermissionUtil.hasWriteExternalStoragePermission(context)) {
            setPendingDownload(image)
            requestWriteExternalStoragePermission()
        } else {
            download(image)
        }
    }

    override fun onFeaturedImageSelected(card: FeaturedImageCard) {
        startActivityForResult(GalleryActivity.newIntent(activity, card.age(),
                card.filename(), card.baseImage(), card.wikiSite(),
                GalleryFunnel.SOURCE_FEED_FEATURED_IMAGE), Constants.ACTIVITY_REQUEST_GALLERY)
    }

    override fun onLoginRequested() {
        startActivityForResult(LoginActivity.newIntent(context, LoginFunnel.SOURCE_NAV),
                Constants.ACTIVITY_REQUEST_LOGIN)
    }

    override fun getOverflowMenuAnchor(): View {
        val callback = callback()
        return callback?.getOverflowMenuAnchor() ?: viewPager!!
    }

    override fun updateToolbarElevation(elevate: Boolean) {
        if (callback() != null) {
            callback()!!.updateToolbarElevation(elevate)
        }
    }

    fun requestUpdateToolbarElevation() {
        val fragment = (viewPager?.adapter as NavTabFragmentPagerAdapter).currentFragment
        updateToolbarElevation((fragment as? FeedFragment)?.shouldElevateToolbar() ?: true)
    }

    override fun onLoading() {
        // todo: [overhaul] update loading indicator.
    }

    override fun onLoaded() {
        // todo: [overhaul] update loading indicator.
    }

    override fun onLoadPage(title: PageTitle, entrySource: Int, location: Location?) {
        showLinkPreview(title, entrySource, location)
    }

    override fun onLoadPage(title: PageTitle, entry: HistoryEntry) {
        startActivity(PageActivity.newIntentForNewTab(context, entry, entry.title))
    }

    override fun onClearHistory() {
        // todo: [overhaul] clear history.
    }

    override fun onSearchResultCopyLink(title: PageTitle) {
        copyLink(title.canonicalUri)
    }

    override fun onSearchResultAddToList(title: PageTitle,
                                         source: AddToReadingListDialog.InvokeSource) {
        bottomSheetPresenter.show(childFragmentManager,
                AddToReadingListDialog.newInstance(title, source))
    }

    override fun onSearchResultShareLink(title: PageTitle) {
        ShareUtil.shareText(context, title)
    }

    override fun onSearchSelectPage(entry: HistoryEntry, inNewTab: Boolean) {
        startActivity(PageActivity.newIntentForNewTab(context, entry, entry.title))
    }

    override fun onSearchOpen() {
        val callback = callback()
        callback?.onSearchOpen()
    }

    override fun onSearchClose(launchedFromIntent: Boolean) {
        val fragment = searchFragment()
        if (fragment != null) {
            closeSearchFragment(fragment)
        }

        val callback = callback()
        callback?.onSearchClose(launchedFromIntent)
    }

    override fun onLinkPreviewLoadPage(title: PageTitle, entry: HistoryEntry, inNewTab: Boolean) {
        startActivity(PageActivity.newIntentForNewTab(context, entry, entry.title))
    }

    override fun onLinkPreviewCopyLink(title: PageTitle) {
        copyLink(title.canonicalUri)
    }

    override fun onLinkPreviewAddToList(title: PageTitle) {
        bottomSheetPresenter.show(childFragmentManager,
                AddToReadingListDialog.newInstance(title,
                        AddToReadingListDialog.InvokeSource.LINK_PREVIEW_MENU))
    }

    override fun onLinkPreviewShareLink(title: PageTitle) {
        ShareUtil.shareText(context, title)
    }

    override fun onBackPressed(): Boolean {
        val searchFragment = searchFragment()
        if (searchFragment != null && searchFragment.onBackPressed()) {
            return true
        }

        val fragment = (viewPager?.adapter as NavTabFragmentPagerAdapter).currentFragment
        return if (fragment is BackPressedHandler && (fragment as BackPressedHandler).onBackPressed()) {
            true
        } else false

    }

    fun setBottomNavVisible(visible: Boolean) {
        tabLayout!!.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun onGoOffline() {
        val fragment = (viewPager?.adapter as NavTabFragmentPagerAdapter).currentFragment
        (fragment as? FeedFragment)?.onGoOffline()
    }

    fun onGoOnline() {
        val fragment = (viewPager?.adapter as NavTabFragmentPagerAdapter).currentFragment
        (fragment as? FeedFragment)?.onGoOnline()
    }

    @OnPageChange(R.id.fragment_main_view_pager) internal fun onTabChanged(position: Int) {
        val callback = callback()
        if (callback != null) {
            val tab = NavTab.of(position)
            callback.onTabChanged(tab)
        }
    }

    private fun showLinkPreview(title: PageTitle, entrySource: Int, location: Location?) {
        bottomSheetPresenter.show(childFragmentManager, LinkPreviewDialog.newInstance(title, entrySource, location))
    }

    private fun copyLink(url: String) {
        ClipboardUtil.setPlainText(context, null, url)
        FeedbackUtil.showMessage(this, R.string.address_copied)
    }

    private fun lastPageViewedWithin(days: Int): Boolean {
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - Prefs.pageLastShown()) < days
    }

    private fun download(image: FeaturedImage) {
        setPendingDownload(null)
        downloadReceiver.download(context, image)
        FeedbackUtil.showMessage(this, R.string.gallery_save_progress)
    }

    private fun setPendingDownload(image: FeaturedImage?) {
        pendingDownloadImage = image
    }

    private fun requestWriteExternalStoragePermission() {
        PermissionUtil.requestWriteStorageRuntimePermissions(this,
                Constants.ACTIVITY_REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION)
    }

    @SuppressLint("CommitTransaction")
    private fun openSearchFragment(source: SearchInvokeSource, query: String?) {
        var fragment: Fragment? = searchFragment()
        if (fragment == null) {
            fragment = SearchFragment.newInstance(source, StringUtils.trim(query))
            childFragmentManager
                    .beginTransaction()
                    .add(R.id.fragment_main_container, fragment)
                    .commitNowAllowingStateLoss()
        }
    }

    @SuppressLint("CommitTransaction")
    private fun closeSearchFragment(fragment: SearchFragment) {
        childFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
    }

    private fun searchFragment(): SearchFragment? {
        return childFragmentManager.findFragmentById(R.id.fragment_main_container) as SearchFragment
    }

    private fun cancelSearch() {
        val fragment = searchFragment()
        fragment?.closeSearch()
    }

    private fun goToTab(tab: NavTab) {
        tabLayout!!.selectedItemId = tab.code()
        cancelSearch()
    }

    private inner class MediaDownloadReceiverCallback : MediaDownloadReceiver.Callback {
        override fun onSuccess() {
            FeedbackUtil.showMessage(activity, R.string.gallery_save_success)
        }
    }

    private fun callback(): Callback? {
        return FragmentUtil.getCallback(this, Callback::class.java)
    }

    companion object {

        fun newInstance(): MainFragment {
            val fragment = MainFragment()
            fragment.retainInstance = true
            return fragment
        }
    }
}
