package org.wikipedia.readinglist

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.support.annotation.PluralsRes
import android.support.design.widget.AppBarLayout
import android.support.design.widget.CollapsingToolbarLayout
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.view.ActionMode
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.Toolbar
import android.support.v7.widget.helper.ItemTouchHelper
import android.text.Spanned
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast

import com.squareup.otto.Subscribe

import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.analytics.ReadingListsFunnel
import org.wikipedia.concurrency.CallbackTask
import org.wikipedia.history.HistoryEntry
import org.wikipedia.history.SearchActionModeCallback
import org.wikipedia.main.MainActivity
import org.wikipedia.page.ExclusiveBottomSheetPresenter
import org.wikipedia.page.PageActivity
import org.wikipedia.page.PageTitle
import org.wikipedia.readinglist.page.ReadingListPage
import org.wikipedia.readinglist.page.database.ReadingListDaoProxy
import org.wikipedia.readinglist.page.database.ReadingListPageDao
import org.wikipedia.readinglist.sync.ReadingListSyncEvent
import org.wikipedia.readinglist.sync.ReadingListSynchronizer
import org.wikipedia.settings.Prefs
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.ShareUtil
import org.wikipedia.util.StringUtil
import org.wikipedia.views.DefaultViewHolder
import org.wikipedia.views.DrawableItemDecoration
import org.wikipedia.views.MultiSelectActionModeCallback
import org.wikipedia.views.PageItemView
import org.wikipedia.views.SearchEmptyView
import org.wikipedia.views.SwipeableItemTouchHelperCallback
import org.wikipedia.views.TextInputDialog

import java.util.ArrayList
import java.util.Collections
import java.util.Locale

import butterknife.BindView
import butterknife.ButterKnife
import butterknife.Unbinder

import org.wikipedia.readinglist.ReadingListActivity.EXTRA_READING_LIST_TITLE
import org.wikipedia.readinglist.ReadingLists.SORT_BY_NAME_ASC

class ReadingListFragment : Fragment(), ReadingListItemActionsDialog.Callback {
    @BindView(R.id.reading_list_toolbar) @JvmField var toolbar: Toolbar? = null
    @BindView(R.id.reading_list_toolbar_container) @JvmField var toolBarLayout: CollapsingToolbarLayout? = null
    @BindView(R.id.reading_list_app_bar) @JvmField var appBarLayout: AppBarLayout? = null
    @BindView(R.id.reading_list_header) @JvmField var headerImageView: ReadingListHeaderView? = null
    @BindView(R.id.reading_list_contents) @JvmField var recyclerView: RecyclerView? = null
    @BindView(R.id.reading_list_empty_text) @JvmField var emptyView: TextView? = null
    @BindView(R.id.search_empty_view) @JvmField var searchEmptyView: SearchEmptyView? = null
    private var unbinder: Unbinder? = null

    private val eventBusMethods = EventBusMethods()
    private var readingList: ReadingList? = null
    private var readingListTitle: String? = null
    private val adapter = ReadingListPageItemAdapter()
    private var headerView: ReadingListItemView? = null
    private var actionMode: ActionMode? = null
    private val appBarListener = AppBarListener()
    private var showOverflowMenu: Boolean = false

    private val readingLists = ReadingLists()
    private val funnel = ReadingListsFunnel()
    private val headerCallback = HeaderCallback()
    private val itemCallback = ItemCallback()
    private val searchActionModeCallback = SearchCallback()
    private val multiSelectActionModeCallback = MultiSelectCallback()
    private val bottomSheetPresenter = ExclusiveBottomSheetPresenter()

    private val displayedPages = ArrayList<ReadingListPage>()
    private var currentSearchQuery: String? = null

    private val appCompatActivity: AppCompatActivity
        get() = activity as AppCompatActivity

    private val selectedPageCount: Int
        get() {
            var selectedCount = 0
            for (page in displayedPages) {
                if (page.isSelected) {
                    selectedCount++
                }
            }
            return selectedCount
        }

    private val selectedPages: List<ReadingListPage>
        get() {
            val result = ArrayList<ReadingListPage>()
            if (readingList == null) {
                return result
            }
            for (page in displayedPages) {
                if (page.isSelected) {
                    result.add(page)
                    page.isSelected = false
                }
            }
            return result
        }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        val view = inflater!!.inflate(R.layout.fragment_reading_list, container, false)
        unbinder = ButterKnife.bind(this, view)

        appCompatActivity.setSupportActionBar(toolbar)
        appCompatActivity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        appCompatActivity.supportActionBar!!.title = ""

        appBarLayout!!.addOnOffsetChangedListener(appBarListener)
        toolBarLayout!!.setCollapsedTitleTextColor(Color.WHITE)

        val touchCallback = SwipeableItemTouchHelperCallback(context)
        val itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        recyclerView!!.layoutManager = LinearLayoutManager(context)
        recyclerView!!.adapter = adapter
        recyclerView!!.addItemDecoration(DrawableItemDecoration(context, R.attr.list_separator_drawable))

        headerView = ReadingListItemView(context)
        headerView!!.setCallback(headerCallback)
        headerView!!.isClickable = false
        headerView!!.setThumbnailVisible(false)
        headerView!!.setShowDescriptionEmptyHint(true)
        headerView!!.setTitleTextAppearance(R.style.ReadingListTitleTextAppearance)

        readingListTitle = arguments.getString(EXTRA_READING_LIST_TITLE)

        WikipediaApp.instance.bus!!.register(eventBusMethods)

        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onResume() {
        super.onResume()
        updateReadingListData()
    }

    override fun onDestroyView() {
        WikipediaApp.instance.bus!!.unregister(eventBusMethods)

        readingList = null
        readingLists.set(emptyList())
        recyclerView!!.adapter = null
        appBarLayout!!.removeOnOffsetChangedListener(appBarListener)
        unbinder!!.unbind()
        unbinder = null
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        inflater!!.inflate(R.menu.menu_reading_lists, menu)
        if (showOverflowMenu) {
            inflater.inflate(R.menu.menu_reading_list_item, menu)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu?) {
        super.onPrepareOptionsMenu(menu)
        val sortByNameItem = menu!!.findItem(R.id.menu_sort_by_name)
        val sortByRecentItem = menu.findItem(R.id.menu_sort_by_recent)
        val sortMode = Prefs.getReadingListPageSortMode(ReadingLists.SORT_BY_NAME_ASC)
        sortByNameItem.setTitle(if (sortMode == ReadingLists.SORT_BY_NAME_ASC) R.string.reading_list_sort_by_name_desc else R.string.reading_list_sort_by_name)
        sortByRecentItem.setTitle(if (sortMode == ReadingLists.SORT_BY_RECENT_DESC) R.string.reading_list_sort_by_recent_desc else R.string.reading_list_sort_by_recent)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item!!.itemId) {
            R.id.menu_search_lists -> {
                appCompatActivity.startSupportActionMode(searchActionModeCallback)
                return true
            }
            R.id.menu_sort_by_name -> {
                setSortMode(ReadingLists.SORT_BY_NAME_ASC, ReadingLists.SORT_BY_NAME_DESC)
                return true
            }
            R.id.menu_sort_by_recent -> {
                setSortMode(ReadingLists.SORT_BY_RECENT_DESC, ReadingLists.SORT_BY_RECENT_ASC)
                return true
            }
            R.id.menu_reading_list_rename -> {
                rename()
                return true
            }
            R.id.menu_reading_list_edit_description -> {
                editDescription()
                return true
            }
            R.id.menu_reading_list_delete -> {
                delete()
                return true
            }
            R.id.menu_reading_list_save_all_offline -> {
                readingList?.let {
                    saveSelectedPagesForOffline(it.pages)
                }
                return true
            }
            R.id.menu_reading_list_remove_all_offline -> {
                if (readingList != null) {
                    removeSelectedPagesFromOffline(readingList!!.pages)
                }
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun update() {
        if (readingList == null) {
            return
        }
        emptyView!!.visibility = if (readingList!!.pages.isEmpty()) View.VISIBLE else View.GONE
        headerView!!.setReadingList(readingList!!, ReadingListItemView.Description.DETAIL)
        headerImageView!!.setReadingList(readingList!!)
        readingList!!.sort(Prefs.getReadingListPageSortMode(SORT_BY_NAME_ASC))
        setSearchQuery(currentSearchQuery)
        updateListDetailsAsync(readingList!!)
    }

    private fun updateListDetailsAsync(list: ReadingList) {
        ReadingListPageDetailFetcher.updateInfo(list, object : ReadingListPageDetailFetcher.Callback {
            override fun success() {
                if (!isAdded) {
                    return
                }
                adapter.notifyDataSetChanged()
            }

            override fun failure(e: Throwable) {}
        })
    }

    private fun updateReadingListData() {
        ReadingList.DAO.queryMruLists(null, object : CallbackTask.DefaultCallback<List<ReadingList>>() {
            override fun success(lists: List<ReadingList>) {
                if (activity == null) {
                    return
                }
                readingLists.set(lists)
                readingList = readingLists.get(readingListTitle)
                if (readingList != null) {
                    searchEmptyView!!.setEmptyText(getString(R.string.search_reading_list_no_results,
                            readingList!!.title))
                }
                update()
            }
        })
    }

    private fun setSearchQuery(query: String?) {
        var query = query
        if (readingList == null) {
            return
        }
        currentSearchQuery = query
        displayedPages.clear()
        if (TextUtils.isEmpty(query)) {
            displayedPages.addAll(readingList!!.pages)
        } else {
            query = query!!.toUpperCase(Locale.getDefault())
            for (page in readingList!!.pages) {
                if (page.title().toUpperCase(Locale.getDefault())
                        .contains(query.toUpperCase(Locale.getDefault()))) {
                    displayedPages.add(page)
                }
            }
        }
        adapter.notifyDataSetChanged()
        updateEmptyState(query)
    }

    private fun updateEmptyState(searchQuery: String?) {
        if (TextUtils.isEmpty(searchQuery)) {
            searchEmptyView!!.visibility = View.GONE
            recyclerView!!.visibility = View.VISIBLE
            emptyView!!.visibility = if (displayedPages.isEmpty()) View.VISIBLE else View.GONE
        } else {
            recyclerView!!.visibility = if (displayedPages.isEmpty()) View.GONE else View.VISIBLE
            searchEmptyView!!.visibility = if (displayedPages.isEmpty()) View.VISIBLE else View.GONE
            emptyView!!.visibility = View.GONE
        }
    }

    private fun setSortMode(sortModeAsc: Int, sortModeDesc: Int) {
        var sortMode = Prefs.getReadingListPageSortMode(ReadingLists.SORT_BY_NAME_ASC)
        if (sortMode != sortModeAsc) {
            sortMode = sortModeAsc
        } else {
            sortMode = sortModeDesc
        }
        Prefs.setReadingListPageSortMode(sortMode)
        activity.invalidateOptionsMenu()
        update()
    }

    private fun showMultiSelectOfflineStateChangeSnackbar(pages: List<ReadingListPage>, offline: Boolean) {
        val message = if (offline)
            getQuantityString(R.plurals.reading_list_article_offline_message, pages.size)
        else
            getQuantityString(R.plurals.reading_list_article_not_offline_message, pages.size)
        FeedbackUtil.showMessage(activity, message)
    }

    private fun showDeleteItemsUndoSnackbar(readingList: ReadingList, pages: List<ReadingListPage>) {
        val message = if (pages.size == 1)
            String.format(getString(R.string.reading_list_item_deleted), pages[0].title())
        else
            String.format(getString(R.string.reading_list_items_deleted), pages.size)
        val snackbar = FeedbackUtil.makeSnackbar(activity, message,
                FeedbackUtil.LENGTH_DEFAULT)
        snackbar.setAction(R.string.reading_list_item_delete_undo) {
            for (page in pages) {
                ReadingList.DAO.addTitleToList(readingList, page, true)
                ReadingListSynchronizer.instance().bumpRevAndSync()
                ReadingListPageDao.instance().markOutdated(page)
            }
            update()
        }
        snackbar.show()
    }

    private fun rename() {
        if (readingList == null) {
            return
        }
        ReadingListTitleDialog.readingListTitleDialog(context, readingList!!.title,
                readingLists.getTitlesExcept(readingList!!.title)
        ) { text ->
            readingListTitle = text.toString()
            ReadingList.DAO.renameAndSaveListInfo(readingList!!, readingListTitle)
            ReadingListSynchronizer.instance().bumpRevAndSync()
            update()
            funnel.logModifyList(readingList, readingLists.size())
        }.show()
    }

    private fun editDescription() {
        if (readingList == null) {
            return
        }
        TextInputDialog.newInstance(context, object : TextInputDialog.DefaultCallback() {
            override fun onShow(dialog: TextInputDialog) {
                dialog.setHint(R.string.reading_list_description_hint)
                dialog.setText(readingList!!.description)
            }

            override fun onSuccess(text: CharSequence) {
                readingList!!.setDescription(text.toString())
                ReadingList.DAO.saveListInfo(readingList!!)
                ReadingListSynchronizer.instance().bumpRevAndSync()
                update()
                funnel.logModifyList(readingList, readingLists.size())
            }
        }).show()
    }

    private fun finishActionMode() {
        if (actionMode != null) {
            actionMode!!.finish()
        }
    }

    private fun beginMultiSelect() {
        if (SearchActionModeCallback.isMode(actionMode)) {
            finishActionMode()
        }
        if (!MultiSelectActionModeCallback.isMode(actionMode)) {
            appCompatActivity.startSupportActionMode(multiSelectActionModeCallback)
        }
    }

    private fun toggleSelectPage(page: ReadingListPage?) {
        if (page == null) {
            return
        }
        page.isSelected = !page.isSelected
        val selectedCount = selectedPageCount
        if (selectedCount == 0) {
            finishActionMode()
        } else if (actionMode != null) {
            actionMode!!.title = getString(R.string.multi_select_items_selected, selectedCount)
        }
        adapter.notifyDataSetChanged()
    }

    private fun unselectAllPages() {
        if (readingList == null) {
            return
        }
        for (page in readingList!!.pages) {
            page.isSelected = false
        }
        adapter.notifyDataSetChanged()
    }

    private fun deleteSelectedPages() {
        val selectedPages = selectedPages
        if (!selectedPages.isEmpty()) {
            for (page in selectedPages) {
                ReadingList.DAO.removeTitleFromList(readingList!!, page)
            }
            ReadingListSynchronizer.instance().bumpRevAndSync()
            funnel.logDeleteItem(readingList, readingLists.size())
            readingList?.let {
                showDeleteItemsUndoSnackbar(it, selectedPages)
            }
            update()
        }
    }

    private fun removeSelectedPagesFromOffline(selectedPages: List<ReadingListPage>) {
        if (!selectedPages.isEmpty()) {
            selectedPages
                    .filter { it.isOffline }
                    .forEach { ReadingListData.instance().setPageOffline(it, false) }
            ReadingListSynchronizer.instance().sync()
            showMultiSelectOfflineStateChangeSnackbar(selectedPages, false)
            adapter.notifyDataSetChanged()
            update()
        }
    }

    private fun saveSelectedPagesForOffline(selectedPages: List<ReadingListPage>) {
        if (!selectedPages.isEmpty()) {
            selectedPages
                    .filterNot { it.isOffline }
                    .forEach { ReadingListData.instance().setPageOffline(it, true) }
            ReadingListSynchronizer.instance().sync()
            showMultiSelectOfflineStateChangeSnackbar(selectedPages, true)
            adapter.notifyDataSetChanged()
            update()
        }
    }

    private fun addSelectedPagesToList() {
        val selectedPages = selectedPages
        if (!selectedPages.isEmpty()) {
            val titles = ArrayList<PageTitle>()
            for (page in selectedPages) {
                titles.add(ReadingListDaoProxy.pageTitle(page))
            }
            bottomSheetPresenter.show(childFragmentManager,
                    AddToReadingListDialog.newInstance(titles,
                            AddToReadingListDialog.InvokeSource.READING_LIST_ACTIVITY))
            update()
        }
    }

    private fun deleteSinglePage(page: ReadingListPage?) {
        if (readingList == null || page == null) {
            return
        }
        readingList?.let {
            showDeleteItemsUndoSnackbar(it, listOf(page))
        }
        ReadingList.DAO.removeTitleFromList(readingList!!, page)
        ReadingListSynchronizer.instance().bumpRevAndSync()
        funnel.logDeleteItem(readingList, readingLists.size())
        update()
    }

    private fun delete() {
        if (readingList != null) {
            startActivity(MainActivity.newIntent(context)
                    .putExtra(Constants.INTENT_EXTRA_DELETE_READING_LIST, readingList!!.title))
            activity.finish()
        }
    }

    override fun onToggleItemOffline(pageIndex: Int) {
        val page = (if (readingList == null) null else readingList!!.get(pageIndex)) ?: return
        if (page.isOffline) {
            ReadingList.DAO.anyListContainsTitleAsync(page.key(), PageListCountCallback(page))
        } else {
            toggleOffline(page)
        }
    }

    override fun onShareItem(pageIndex: Int) {
        val page = if (readingList == null) null else readingList!!.get(pageIndex)
        if (page != null) {
            ShareUtil.shareText(context, ReadingListDaoProxy.pageTitle(page))
        }
    }

    override fun onAddItemToOther(pageIndex: Int) {
        val page = if (readingList == null) null else readingList!!.get(pageIndex)
        if (page != null) {
            bottomSheetPresenter.show(childFragmentManager,
                    AddToReadingListDialog.newInstance(ReadingListDaoProxy.pageTitle(page),
                            AddToReadingListDialog.InvokeSource.READING_LIST_ACTIVITY))
        }
    }

    override fun onDeleteItem(pageIndex: Int) {
        val page = if (readingList == null) null else readingList!!.get(pageIndex)
        deleteSinglePage(page)
    }

    private fun toggleOffline(page: ReadingListPage) {
        ReadingListData.instance().setPageOffline(page, !page.isOffline)
        if (activity != null) {
            FeedbackUtil.showMessage(activity, if (page.isOffline)
                getQuantityString(R.plurals.reading_list_article_offline_message, 1)
            else
                getQuantityString(R.plurals.reading_list_article_not_offline_message, 1))
            adapter.notifyDataSetChanged()
            ReadingListSynchronizer.instance().syncSavedPages()
        }
    }

    private fun showMultiListPageConfirmToggleDialog(page: ReadingListPage) {
        if (activity == null) {
            return
        }
        val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.reading_list_confirm_remove_article_from_offline_title)
                .setMessage(getConfirmToggleOfflineMessage(page))
                .setPositiveButton(R.string.reading_list_confirm_remove_article_from_offline,
                        ConfirmRemoveFromOfflineListener(page))
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.show()
        val text = dialog.findViewById<TextView>(android.R.id.message)
        text!!.setLineSpacing(0f, 1.3f)
    }

    private fun getConfirmToggleOfflineMessage(page: ReadingListPage): Spanned {
        var result = getString(R.string.reading_list_confirm_remove_article_from_offline_message,
                "<b>" + page.title() + "</b>")
        for (key in page.listKeys()) {
            result += "<br>&nbsp;&nbsp;<b>&#8226; " + ReadingListDaoProxy.listName(key) + "</b>"
        }
        return StringUtil.fromHtml(result)
    }

    private fun getQuantityString(@PluralsRes id: Int, quantity: Int, vararg formatArgs: Any): String {
        return resources.getQuantityString(id, quantity, *formatArgs)
    }

    private inner class AppBarListener : AppBarLayout.OnOffsetChangedListener {
        override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
            if (verticalOffset > -appBarLayout.totalScrollRange && showOverflowMenu) {
                showOverflowMenu = false
                toolBarLayout!!.title = ""
                appCompatActivity.supportInvalidateOptionsMenu()
            } else if (verticalOffset <= -appBarLayout.totalScrollRange && !showOverflowMenu) {
                showOverflowMenu = true
                toolBarLayout!!.title = if (readingList != null) readingList!!.title else null
                appCompatActivity.supportInvalidateOptionsMenu()
            }
        }
    }

    private inner class HeaderCallback : ReadingListItemView.Callback {
        override fun onClick(readingList: ReadingList) {}

        override fun onRename(readingList: ReadingList) {
            rename()
        }

        override fun onEditDescription(readingList: ReadingList) {
            editDescription()
        }

        override fun onDelete(readingList: ReadingList) {
            delete()
        }

        override fun onSaveAllOffline(readingList: ReadingList) {
            saveSelectedPagesForOffline(readingList.pages)
        }

        override fun onRemoveAllOffline(readingList: ReadingList) {
            removeSelectedPagesFromOffline(readingList.pages)
        }
    }

    private inner class ReadingListPageItemHolder internal constructor(itemView: PageItemView<ReadingListPage>) : DefaultViewHolder<PageItemView<ReadingListPage>>(itemView), SwipeableItemTouchHelperCallback.Callback {
        private var page: ReadingListPage? = null

        internal fun bindItem(page: ReadingListPage) {
            this.page = page
            view.setItem(page)
            view.setTitle(page.title())
            view.setDescription(page.description())
            view.setImageUrl(page.thumbnailUrl())
            view.isSelected = page.isSelected
            view.setActionIcon(R.drawable.ic_more_vert_white_24dp)
            view.setActionHint(R.string.abc_action_menu_overflow_description)
            view.setSecondaryActionIcon(if (page.isSaving)
                R.drawable.ic_download_started
            else
                R.drawable.ic_download_circle_gray_24dp,
                    !page.isOffline || page.isSaving)
            view.setSecondaryActionHint(R.string.reading_list_article_make_offline)
        }

        override fun onSwipe() {
            deleteSinglePage(page)
        }
    }

    private inner class ReadingListHeaderHolder internal constructor(itemView: View?) : RecyclerView.ViewHolder(itemView)

    private inner class ReadingListPageItemAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int {
            return 1 + displayedPages.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            return if (type == TYPE_HEADER) {
                ReadingListHeaderHolder(headerView)
            } else ReadingListPageItemHolder(PageItemView(context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            if (readingList != null && holder is ReadingListPageItemHolder) {
                holder.bindItem(displayedPages[pos - 1])
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) TYPE_HEADER else TYPE_ITEM
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder?) {
            super.onViewAttachedToWindow(holder)
            (holder as? ReadingListPageItemHolder)?.view?.setCallback(itemCallback)
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder?) {
            (holder as? ReadingListPageItemHolder)?.view?.setCallback(null)
            super.onViewDetachedFromWindow(holder)
        }


    }

    private inner class ItemCallback : PageItemView.Callback<ReadingListPage> {
        override fun onClick(page: ReadingListPage?) {
            if (MultiSelectActionModeCallback.isMode(actionMode)) {
                toggleSelectPage(page)
            } else if (page != null && readingList != null) {
                val title = ReadingListDaoProxy.pageTitle(page)
                val entry = HistoryEntry(title, HistoryEntry.SOURCE_READING_LIST)
                ReadingList.DAO.makeListMostRecent(readingList!!)
                startActivity(PageActivity.newIntentForNewTab(context, entry, entry.title))
            }
        }

        override fun onLongClick(item: ReadingListPage?): Boolean {
            beginMultiSelect()
            toggleSelectPage(item)
            return true
        }

        override fun onThumbClick(item: ReadingListPage?) {
            onClick(item)
        }

        override fun onActionClick(page: ReadingListPage?, view: View) {
            if (page == null || readingList == null) {
                return
            }
            bottomSheetPresenter.show(childFragmentManager,
                    ReadingListItemActionsDialog.newInstance(page, readingList!!))
        }

        override fun onSecondaryActionClick(page: ReadingListPage?, view: View) {
            if (page != null) {
                if (page.isSaving) {
                    Toast.makeText(context, R.string.reading_list_article_save_in_progress, Toast.LENGTH_LONG).show()
                } else {
                    toggleOffline(page)
                }
            }
        }
    }

    private inner class SearchCallback : SearchActionModeCallback() {

        override val searchHintString: String
            get() = getString(R.string.search_hint_search_reading_list)

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            recyclerView!!.stopScroll()
            appBarLayout!!.setExpanded(false, false)
            return super.onCreateActionMode(mode, menu)
        }

        override fun onQueryChange(s: String) {
            setSearchQuery(s.trim { it <= ' ' })
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            super.onDestroyActionMode(mode)
            actionMode = null
            setSearchQuery(null)
        }
    }

    private inner class MultiSelectCallback : MultiSelectActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            super.onCreateActionMode(mode, menu)
            mode.menuInflater.inflate(R.menu.menu_action_mode_reading_list, menu)
            actionMode = mode
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.menu_delete_selected -> {
                    onDeleteSelected()
                    finishActionMode()
                    return true
                }
                R.id.menu_remove_from_offline -> {
                    removeSelectedPagesFromOffline(selectedPages)
                    finishActionMode()
                    return true
                }
                R.id.menu_save_for_offline -> {
                    saveSelectedPagesForOffline(selectedPages)
                    finishActionMode()
                    return true
                }
                R.id.menu_add_to_another_list -> {
                    addSelectedPagesToList()
                    finishActionMode()
                    return true
                }
            }
            return false
        }

        override fun onDeleteSelected() {
            deleteSelectedPages()
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            unselectAllPages()
            actionMode = null
            super.onDestroyActionMode(mode)
        }
    }

    private inner class PageListCountCallback (private val page: ReadingListPage) : CallbackTask.DefaultCallback<ReadingListPage>() {
        override fun success(fromDb: ReadingListPage) {
            if (fromDb.listKeys().size > 1) {
                showMultiListPageConfirmToggleDialog(page)
            } else {
                toggleOffline(page)
            }
        }
    }

    private inner class ConfirmRemoveFromOfflineListener (private val page: ReadingListPage) : DialogInterface.OnClickListener {
        override fun onClick(dialog: DialogInterface, which: Int) {
            toggleOffline(page)
        }
    }

    private inner class EventBusMethods {
        @Subscribe
        fun on(event: ReadingListSyncEvent) {
            if (isAdded) {
                updateReadingListData()
            }
        }
    }

    companion object {

        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        fun newInstance(listTitle: String): ReadingListFragment {
            val instance = ReadingListFragment()
            val args = Bundle()
            args.putString(EXTRA_READING_LIST_TITLE, listTitle)
            instance.arguments = args
            return instance
        }
    }
}
