package org.wikipedia.views

import android.support.v7.view.ActionMode
import android.view.Menu
import android.view.MenuItem

import org.wikipedia.R

abstract class MultiSelectActionModeCallback : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.tag = ACTION_MODE_TAG
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_delete_selected -> {
                onDeleteSelected()
                return true
            }
        }
        return false
    }

    protected abstract fun onDeleteSelected()

    override fun onDestroyActionMode(mode: ActionMode) {}

    companion object {
        private val ACTION_MODE_TAG = "multiSelectActionMode"

        fun isMode(mode: ActionMode?): Boolean {
            return mode != null && ACTION_MODE_TAG == mode.tag
        }
    }
}
