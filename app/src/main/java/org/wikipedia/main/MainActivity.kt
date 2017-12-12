package org.wikipedia.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.view.ActionMode
import android.view.View

import org.wikipedia.R
import org.wikipedia.activity.SingleFragmentToolbarActivity
import org.wikipedia.appshortcuts.AppShortcuts
import org.wikipedia.navtab.NavTab
import org.wikipedia.onboarding.InitialOnboardingActivity
import org.wikipedia.settings.Prefs
import org.wikipedia.util.ResourceUtil

class MainActivity : SingleFragmentToolbarActivity<MainFragment>(), MainFragment.Callback {

    companion object {

        fun newIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSharedElementTransitions()
        AppShortcuts().init()

        if (Prefs.isInitialOnboardingEnabled() && savedInstanceState == null) {
            startActivity(InitialOnboardingActivity.newIntent(this))
        }
    }

    override fun createFragment(): MainFragment {
        return MainFragment.newInstance()
    }

    override fun onTabChanged(tab: NavTab) {
        if (tab == NavTab.EXPLORE) {
            toolbarWordmark.visibility = View.VISIBLE
            supportActionBar!!.title = ""
        } else {
            toolbarWordmark.visibility = View.GONE
            supportActionBar!!.setTitle(tab.text())
        }
        fragment.requestUpdateToolbarElevation()
    }

    override fun onSearchOpen() {
        toolbar.visibility = View.GONE
        setStatusBarColor(ResourceUtil.getThemedAttributeId(this, R.attr.page_status_bar_color))
    }

    override fun onSearchClose(shouldFinishActivity: Boolean) {
        toolbar.visibility = View.VISIBLE
        setStatusBarColor(ResourceUtil.getThemedAttributeId(this, R.attr.main_status_bar_color))
        if (shouldFinishActivity) {
            finish()
        }
    }

    override fun onSupportActionModeStarted(mode: ActionMode) {
        super.onSupportActionModeStarted(mode)
        fragment.setBottomNavVisible(false)
    }

    override fun onSupportActionModeFinished(mode: ActionMode) {
        super.onSupportActionModeFinished(mode)
        fragment.setBottomNavVisible(true)
    }

    override fun getOverflowMenuAnchor(): View {
        val view = toolbar.findViewById<View>(R.id.menu_overflow_button)
        return view ?: toolbar
    }

    override fun updateToolbarElevation(elevate: Boolean) {
        if (elevate) {
            setToolbarElevationDefault()
        } else {
            clearToolbarElevation()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fragment.handleIntent(intent)
    }

    override fun onGoOffline() {
        fragment.onGoOffline()
    }

    override fun onGoOnline() {
        fragment.onGoOnline()
    }

    override fun onBackPressed() {
        if (fragment.onBackPressed()) {
            return
        }
        finish()
    }
}
