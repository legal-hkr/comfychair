package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Container activity that hosts settings fragments with bottom navigation.
 */
class SettingsContainerActivity : AppCompatActivity() {

    // Connection information (passed from MainContainerActivity)
    private var hostname: String = ""
    private var port: Int = 8188

    // Navigation history stack for back button support
    private val navigationHistory = mutableListOf<Int>()
    private var currentNavItemId: Int = R.id.nav_application_settings
    private var isNavigatingBack = false
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_container)

        // Apply window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // Get connection parameters from intent
        hostname = intent.getStringExtra("hostname") ?: ""
        port = intent.getIntExtra("port", 8188)

        // Setup bottom navigation
        bottomNav = findViewById(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_application_settings -> ApplicationSettingsFragment.newInstance()
                R.id.nav_server_settings -> ServerSettingsFragment.newInstance(hostname, port)
                else -> null
            }

            if (fragment != null) {
                // Add current item to history before switching (if different from new item)
                // Don't add to history if we're navigating back
                if (!isNavigatingBack && currentNavItemId != item.itemId) {
                    navigationHistory.add(currentNavItemId)
                    currentNavItemId = item.itemId
                }
                switchFragment(fragment)
                true
            } else {
                false
            }
        }

        // Load initial fragment (ApplicationSettings)
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_application_settings
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * Navigate back to the previous fragment in history
     * Returns true if navigation occurred, false if at the start of history
     */
    fun navigateBack(): Boolean {
        if (navigationHistory.isNotEmpty()) {
            val previousNavItemId = navigationHistory.removeAt(navigationHistory.lastIndex)
            currentNavItemId = previousNavItemId

            // Use a flag to prevent adding to history when navigating back
            isNavigatingBack = true
            bottomNav.selectedItemId = previousNavItemId
            isNavigatingBack = false

            return true
        }
        return false
    }

    /**
     * Navigate to Generation (MainContainerActivity)
     */
    fun navigateToGeneration() {
        // Just finish this activity to return to MainContainerActivity
        finish()
    }

    /**
     * Log out from the server and return to MainActivity
     */
    fun logout() {
        // Clear all activities and go back to MainActivity
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Get connection hostname
     */
    fun getHostname(): String = hostname

    /**
     * Get connection port
     */
    fun getPort(): Int = port
}
