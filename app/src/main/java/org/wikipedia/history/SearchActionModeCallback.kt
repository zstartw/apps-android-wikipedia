package org.wikipedia.history

import android.support.v7.view.ActionMode
import android.support.v7.widget.SearchView
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView

import org.wikipedia.R
import org.wikipedia.util.DeviceUtil

abstract class SearchActionModeCallback : ActionMode.Callback {
    private var searchView: SearchView? = null
    private var searchMagIcon: ImageView? = null

    protected abstract val searchHintString: String

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.tag = ACTION_MODE_TAG
        mode.menuInflater.inflate(R.menu.menu_action_mode_search, menu)
        searchView = menu.findItem(R.id.menu_search_view).actionView as SearchView
        searchMagIcon = searchView!!.findViewById(android.support.v7.appcompat.R.id.search_mag_icon)
        searchMagIcon!!.setImageDrawable(null)
        searchView!!.setIconifiedByDefault(false)
        searchView!!.queryHint = searchHintString
        searchView!!.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(s: String): Boolean {
                return false
            }

            override fun onQueryTextChange(s: String): Boolean {
                onQueryChange(s)
                return true
            }
        })
        return true
    }

    protected abstract fun onQueryChange(s: String)

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        searchView!!.requestFocus()
        searchMagIcon!!.visibility = View.GONE
        DeviceUtil.showSoftKeyboard(searchView)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean {
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (searchView != null) {
            searchView!!.setOnQueryTextListener(null)
        }
    }

    companion object {
        val ACTION_MODE_TAG = "searchActionMode"

        fun isMode(mode: ActionMode?): Boolean {
            return mode != null && ACTION_MODE_TAG == mode.tag
        }
    }
}
