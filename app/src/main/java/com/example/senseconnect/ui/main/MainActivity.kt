package com.example.senseconnect.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.example.senseconnect.R
import com.example.senseconnect.core.ui.BaseActivity
import com.example.senseconnect.core.ui.applySystemBarPadding
import com.example.senseconnect.databinding.ActivityMainBinding
import com.example.senseconnect.ui.history.HistoryFragment
import com.example.senseconnect.ui.home.HomeFragment
import com.example.senseconnect.ui.onboarding.OnboardingActivity
import com.example.senseconnect.ui.settings.SettingsFragment

/**
 * App shell: splash screen, first-launch routing to onboarding, and the three top-level
 * destinations (Home, Activity, Settings). Feature modules open as full screens from Home.
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = selectTab(R.id.nav_home)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (!container.settings.current.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Fragments scroll beneath a clipped status-bar area instead of under the clock.
        binding.fragmentContainer.applySystemBarPadding(top = true)
        onBackPressedDispatcher.addCallback(this, backToHome)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        binding.bottomNav.setOnItemReselectedListener { /* no-op: avoid recreating */ }

        val initial = savedInstanceState?.getInt(KEY_TAB) ?: intent.getIntExtra(EXTRA_TAB, R.id.nav_home)
        binding.bottomNav.selectedItemId = initial
        showTab(initial)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::binding.isInitialized && intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, R.id.nav_home))
    }

    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    private fun showTab(itemId: Int) {
        val tag = tagFor(itemId)
        val fm = supportFragmentManager
        fm.commitNow {
            setReorderingAllowed(true)
            TAGS.forEach { other ->
                if (other != tag) fm.findFragmentByTag(other)?.let { hide(it) }
            }
            val existing = fm.findFragmentByTag(tag)
            if (existing == null) add(R.id.fragmentContainer, createFragment(itemId), tag) else show(existing)
        }
        backToHome.isEnabled = itemId != R.id.nav_home
    }

    private fun tagFor(itemId: Int) = when (itemId) {
        R.id.nav_activity -> TAG_ACTIVITY
        R.id.nav_settings -> TAG_SETTINGS
        else -> TAG_HOME
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_activity -> HistoryFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) outState.putInt(KEY_TAB, binding.bottomNav.selectedItemId)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        private const val KEY_TAB = "selected_tab"
        private const val TAG_HOME = "home"
        private const val TAG_ACTIVITY = "activity"
        private const val TAG_SETTINGS = "settings"
        private val TAGS = listOf(TAG_HOME, TAG_ACTIVITY, TAG_SETTINGS)
    }
}
