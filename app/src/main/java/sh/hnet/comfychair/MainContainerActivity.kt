package sh.hnet.comfychair

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Container activity that hosts fragments with persistent bottom navigation.
 * Ensures navigation bar stays visible during fragment transitions.
 */
class MainContainerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_container)

        // Apply window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // Get connection parameters from intent
        val hostname = intent.getStringExtra("hostname") ?: ""
        val port = intent.getIntExtra("port", 8188)

        // Setup bottom navigation
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_query -> {
                    switchFragment(QueryFragment.newInstance(hostname, port))
                    true
                }
                R.id.nav_gallery -> {
                    switchFragment(GalleryFragment.newInstance(hostname, port))
                    true
                }
                else -> false
            }
        }

        // Load initial fragment (Query) if this is the first creation
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_query
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
