package org.wikipedia.history

import android.content.DialogInterface
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v4.app.LoaderManager
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.view.ActionMode
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup

import org.wikipedia.BackPressedHandler
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.FragmentUtil
import org.wikipedia.database.DatabaseClient
import org.wikipedia.database.contract.PageHistoryContract
import org.wikipedia.page.PageTitle
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.views.DefaultViewHolder
import org.wikipedia.views.MultiSelectActionModeCallback
import org.wikipedia.views.PageItemView
import org.wikipedia.views.SearchEmptyView
import org.wikipedia.views.SwipeableItemTouchHelperCallback

import java.text.DateFormat
import java.util.ArrayList
import java.util.Date
import java.util.HashSet

import butterknife.BindView
import butterknife.ButterKnife
import butterknife.Unbinder

import org.wikipedia.Constants.HISTORY_FRAGMENT_LOADER_ID

class HistoryFragment : Fragment(), BackPressedHandler {

    private var unbinder: Unbinder? = null
    @BindView(R.id.history_list) @JvmField var historyList: RecyclerView? = null
    @BindView(R.id.history_empty_container) @JvmField var historyEmptyView: View? = null
    @BindView(R.id.search_empty_view) @JvmField var searchEmptyView: SearchEmptyView? = null

    private var app: WikipediaApp? = null

    private var currentSearchQuery: String? = null
    private val loaderCallback = LoaderCallback()
    private val adapter = HistoryEntryItemAdapter()

    private val itemCallback = ItemCallback()
    private var actionMode: ActionMode? = null
    private val searchActionModeCallback = HistorySearchCallback()
    private val multiSelectCallback = MultiSelectCallback()
    private val selectedIndices = HashSet<Int>()

    interface Callback {
        fun onLoadPage(title: PageTitle, entry: HistoryEntry)
        fun onClearHistory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        app = WikipediaApp.instance
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater!!.inflate(R.layout.fragment_history, container, false)
        unbinder = ButterKnife.bind(this, view)

        searchEmptyView!!.setEmptyText(R.string.search_history_no_results)

        val touchCallback = SwipeableItemTouchHelperCallback(context)
        val itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(historyList)

        historyList!!.layoutManager = LinearLayoutManager(context)
        historyList!!.adapter = adapter

        activity.supportLoaderManager.initLoader(HISTORY_FRAGMENT_LOADER_ID, Bundle(), loaderCallback)
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onDestroyView() {
        activity.supportLoaderManager.destroyLoader(HISTORY_FRAGMENT_LOADER_ID)
        historyList!!.adapter = null
        adapter.setCursor(null)
        unbinder!!.unbind()
        unbinder = null
        super.onDestroyView()
    }

    override fun setUserVisibleHint(visible: Boolean) {
        if (!isAdded) {
            return
        }
        if (!visible && actionMode != null) {
            actionMode!!.finish()
        }
    }

    override fun onBackPressed(): Boolean {
        if (actionMode != null) {
            actionMode!!.finish()
            return true
        }
        return false
    }

    private fun updateEmptyState(searchQuery: String?) {
        if (TextUtils.isEmpty(searchQuery)) {
            searchEmptyView!!.visibility = View.GONE
            historyEmptyView!!.visibility = if (adapter.isEmpty) View.VISIBLE else View.GONE
        } else {
            searchEmptyView!!.visibility = if (adapter.isEmpty) View.VISIBLE else View.GONE
            historyEmptyView!!.visibility = View.GONE
        }
        historyList!!.visibility = if (adapter.isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        app!!.refWatcher!!.watch(this)
    }

    override fun onCreateOptionsMenu(menu: Menu?, inflater: MenuInflater?) {
        inflater!!.inflate(R.menu.menu_history, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?) {
        super.onPrepareOptionsMenu(menu)
        val isHistoryAvailable = !adapter.isEmpty
        menu!!.findItem(R.id.menu_clear_all_history)
                .setVisible(isHistoryAvailable).isEnabled = isHistoryAvailable
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item!!.itemId) {
            R.id.menu_clear_all_history -> {
                AlertDialog.Builder(context)
                        .setTitle(R.string.dialog_title_clear_history)
                        .setMessage(R.string.dialog_message_clear_history)
                        .setPositiveButton(R.string.dialog_message_clear_history_yes) { dialog, which ->
                            // Clear history!
                            DeleteAllHistoryTask(app).execute()
                            onClearHistoryClick()
                        }
                        .setNegativeButton(R.string.dialog_message_clear_history_no, null).create().show()
                return true
            }
            R.id.menu_search_history -> {
                if (actionMode == null) {
                    actionMode = (activity as AppCompatActivity)
                            .startSupportActionMode(searchActionModeCallback)
                }
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun onPageClick(title: PageTitle, entry: HistoryEntry) {
        val callback = callback()
        callback?.onLoadPage(title, entry)
    }

    private fun onClearHistoryClick() {
        val callback = callback()
        callback?.onClearHistory()
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

        if(!MultiSelectActionModeCallback.isMode(actionMode)){
            (activity as AppCompatActivity).startSupportActionMode(multiSelectCallback)
        }
    }

    private fun toggleSelectPage(indexedEntry: IndexedHistoryEntry?) {
        if (indexedEntry == null) {
            return
        }
        if (selectedIndices.contains(indexedEntry.index)) {
            selectedIndices.remove(indexedEntry.index)
        } else {
            selectedIndices.add(indexedEntry.index)
        }
        val selectedCount = selectedIndices.size
        if (selectedCount == 0) {
            finishActionMode()
        } else if (actionMode != null) {
            actionMode!!.title = getString(R.string.multi_select_items_selected, selectedCount)
        }
        adapter.notifyDataSetChanged()
    }

    private fun unselectAllPages() {
        selectedIndices.clear()
        adapter.notifyDataSetChanged()
    }

    private fun deleteSelectedPages() {
        val selectedEntries = ArrayList<HistoryEntry>()
        for (index in selectedIndices) {
            val entry = adapter.getItem(index)
            if (entry != null) {
                selectedEntries.add(entry)
                app!!.getDatabaseClient(HistoryEntry::class.java).delete(entry,
                        PageHistoryContract.PageWithImage.SELECTION)
            }
        }
        selectedIndices.clear()
        if (!selectedEntries.isEmpty()) {
            showDeleteItemsUndoSnackbar(selectedEntries)
            adapter.notifyDataSetChanged()
        }
    }

    private fun showDeleteItemsUndoSnackbar(entries: List<HistoryEntry>) {
        val message = if (entries.size == 1)
            String.format(getString(R.string.history_item_deleted), entries[0].title.displayText)
        else
            String.format(getString(R.string.history_items_deleted), entries.size)
        val snackbar = FeedbackUtil.makeSnackbar(activity, message,
                FeedbackUtil.LENGTH_DEFAULT)
        snackbar.setAction(R.string.history_item_delete_undo) {
            val client = app!!.getDatabaseClient(HistoryEntry::class.java)
            for (entry in entries) {
                client.upsert(entry, PageHistoryContract.PageWithImage.SELECTION)
            }
            adapter.notifyDataSetChanged()
        }
        snackbar.show()
    }

    private fun restartLoader() {
        activity.supportLoaderManager.restartLoader(HISTORY_FRAGMENT_LOADER_ID, null, loaderCallback)
    }

    private inner class LoaderCallback : LoaderManager.LoaderCallbacks<Cursor> {
        override fun onCreateLoader(id: Int, args: Bundle): Loader<Cursor> {
            val titleCol = PageHistoryContract.PageWithImage.TITLE.qualifiedName()
            var selection: String? = null
            var selectionArgs: Array<String>? = null
            var searchStr = currentSearchQuery
            if (!TextUtils.isEmpty(searchStr)) {
                searchStr = searchStr!!.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                selection = "UPPER($titleCol) LIKE UPPER(?) ESCAPE '\\'"
                selectionArgs = arrayOf("%$searchStr%")
            }

            val uri = PageHistoryContract.PageWithImage.URI
            val projection: Array<String>? = null
            val order = PageHistoryContract.PageWithImage.ORDER_MRU
            return CursorLoader(context.applicationContext,
                    uri, projection, selection, selectionArgs, order)
        }

        override fun onLoadFinished(cursorLoader: Loader<Cursor>, cursor: Cursor) {
            adapter.setCursor(cursor)
            if (!isAdded) {
                return
            }
            updateEmptyState(currentSearchQuery)
            activity.invalidateOptionsMenu()
        }

        override fun onLoaderReset(loader: Loader<Cursor>) {
            adapter.setCursor(null)
        }
    }

    private class IndexedHistoryEntry internal constructor(val entry: HistoryEntry, val index: Int)

    private inner class HistoryEntryItemHolder internal constructor(itemView: PageItemView<IndexedHistoryEntry>) : DefaultViewHolder<PageItemView<IndexedHistoryEntry>>(itemView), SwipeableItemTouchHelperCallback.Callback {
        private var index: Int = 0

        internal fun bindItem(cursor: Cursor) {
            index = cursor.position
            val indexedEntry = IndexedHistoryEntry(HistoryEntry.DATABASE_TABLE.fromCursor(cursor), index)
            view.setItem(indexedEntry)
            view.setTitle(indexedEntry.entry.title.displayText)
            view.setDescription(indexedEntry.entry.title.description)
            view.setImageUrl(PageHistoryContract.PageWithImage.IMAGE_NAME.`val`(cursor))
            view.isSelected = selectedIndices.contains(indexedEntry.index)

            // Check the previous item, see if the times differ enough
            // If they do, display the section header.
            // Always do it this is the first item.
            val curTime = getDateString(indexedEntry.entry.timestamp)
            var prevTime = ""
            if (cursor.position != 0) {
                cursor.moveToPrevious()
                val prevEntry = HistoryEntry.DATABASE_TABLE.fromCursor(cursor)
                prevTime = getDateString(prevEntry.timestamp)
                cursor.moveToNext()
            }
            view.setHeaderText(if (curTime == prevTime) null else curTime)
        }

        private fun getDateString(date: Date): String {
            return DateFormat.getDateInstance().format(date)
        }

        override fun onSwipe() {
            selectedIndices.add(index)
            deleteSelectedPages()
        }
    }

    private inner class HistoryEntryItemAdapter : RecyclerView.Adapter<HistoryEntryItemHolder>() {
        private var cursor: Cursor? = null

        val isEmpty: Boolean
            get() = itemCount == 0

        override fun getItemCount(): Int {
            return if (cursor == null) 0 else cursor!!.count
        }

        fun getItem(position: Int): HistoryEntry? {
            if (cursor == null) {
                return null
            }
            val prevPosition = cursor!!.position
            cursor!!.moveToPosition(position)
            val entry = HistoryEntry.DATABASE_TABLE.fromCursor(cursor)
            cursor!!.moveToPosition(prevPosition)
            return entry
        }

        fun setCursor(newCursor: Cursor?) {
            if (cursor === newCursor) {
                return
            }
            if (cursor != null) {
                cursor!!.close()
            }
            cursor = newCursor
            this.notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): HistoryEntryItemHolder {
            return HistoryEntryItemHolder(PageItemView(context))
        }

        override fun onBindViewHolder(holder: HistoryEntryItemHolder, pos: Int) {
            if (cursor == null) {
                return
            }
            cursor!!.moveToPosition(pos)
            holder.bindItem(cursor!!)
        }

        override fun onViewAttachedToWindow(holder: HistoryEntryItemHolder?) {
            super.onViewAttachedToWindow(holder)
            holder!!.view.setCallback(itemCallback)
        }

        override fun onViewDetachedFromWindow(holder: HistoryEntryItemHolder?) {
            holder!!.view.setCallback(null)
            super.onViewDetachedFromWindow(holder)
        }
    }

    private inner class ItemCallback : PageItemView.Callback<IndexedHistoryEntry> {
        override fun onClick(indexedEntry: IndexedHistoryEntry?) {
            if (SearchActionModeCallback.isMode(actionMode)) {
                toggleSelectPage(indexedEntry)
            } else if (indexedEntry != null) {
                val newEntry = HistoryEntry(indexedEntry.entry.title, HistoryEntry.SOURCE_HISTORY)
                onPageClick(indexedEntry.entry.title, newEntry)
            }
        }

        override fun onLongClick(indexedEntry: IndexedHistoryEntry?): Boolean {
            beginMultiSelect()
            toggleSelectPage(indexedEntry)
            return true
        }

        override fun onThumbClick(indexedEntry: IndexedHistoryEntry?) {
            onClick(indexedEntry)
        }

        override fun onActionClick(entry: IndexedHistoryEntry?, view: View) {}
        override fun onSecondaryActionClick(entry: IndexedHistoryEntry?, view: View) {}
    }

    private inner class HistorySearchCallback : SearchActionModeCallback() {
        override val searchHintString: String
            get() = context.resources.getString(R.string.search_hint_search_history)

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            return super.onCreateActionMode(mode, menu)
        }

        override fun onQueryChange(s: String) {
            currentSearchQuery = s.trim { it <= ' ' }
            restartLoader()
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            super.onDestroyActionMode(mode)
            if (!TextUtils.isEmpty(currentSearchQuery)) {
                currentSearchQuery = ""
                restartLoader()
            }
            actionMode = null
        }

       /* override fun getSearchHintString(): String {
            return context.resources.getString(R.string.search_hint_search_history)
        }*/
    }

    private inner class MultiSelectCallback : MultiSelectActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            super.onCreateActionMode(mode, menu)
            mode.menuInflater.inflate(R.menu.menu_action_mode_history, menu)
            actionMode = mode
            selectedIndices.clear()
            return super.onCreateActionMode(mode, menu)
        }

        override fun onDeleteSelected() {
            deleteSelectedPages()
            finishActionMode()
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            unselectAllPages()
            actionMode = null
            super.onDestroyActionMode(mode)
        }
    }

    private fun callback(): Callback? {
        return FragmentUtil.getCallback(this, Callback::class.java)
    }

    companion object {

        fun newInstance(): HistoryFragment {
            return HistoryFragment()
        }
    }
}
