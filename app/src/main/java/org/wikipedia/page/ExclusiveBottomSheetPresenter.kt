package org.wikipedia.page

import android.app.Dialog
import android.content.DialogInterface
import android.support.v4.app.DialogFragment
import android.support.v4.app.FragmentManager

import org.wikipedia.readinglist.AddToReadingListDialog

class ExclusiveBottomSheetPresenter {
    private var currentDialog: Dialog? = null

    companion object {
        private val BOTTOM_SHEET_FRAGMENT_TAG = "bottom_sheet_fragment"
    }

    fun showAddToListDialog(fm: FragmentManager, title: PageTitle,
                            source: AddToReadingListDialog.InvokeSource) {
        show(fm, AddToReadingListDialog.newInstance(title, source))
    }

    fun showAddToListDialog(fm: FragmentManager, title: PageTitle,
                            source: AddToReadingListDialog.InvokeSource,
                            listener: DialogInterface.OnDismissListener?) {
        show(fm, AddToReadingListDialog.newInstance(title, source, listener))
    }

    fun show(manager: FragmentManager, dialog: DialogFragment) {
        dismiss(manager)
        dialog.show(manager, BOTTOM_SHEET_FRAGMENT_TAG)
    }

    fun show(manager: FragmentManager, dialog: Dialog) {
        dismiss(manager)
        currentDialog = dialog
        currentDialog!!.setOnDismissListener { currentDialog = null }
        currentDialog!!.show()
    }

    fun dismiss(manager: FragmentManager) {
        val dialog = manager.findFragmentByTag(BOTTOM_SHEET_FRAGMENT_TAG) as DialogFragment
        dialog?.dismiss()
        if (currentDialog != null) {
            currentDialog!!.setOnDismissListener(null)
            currentDialog!!.dismiss()
        }
        currentDialog = null
    }
}
