package sh.hnet.comfychair

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar

/**
 * ApplicationSettingsFragment - Application management settings screen
 */
class ApplicationSettingsFragment : Fragment() {

    // UI element references
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var clearCacheButton: Button
    private lateinit var restoreDefaultsButton: Button

    companion object {
        fun newInstance(): ApplicationSettingsFragment {
            return ApplicationSettingsFragment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_application_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle window insets
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, 0)
            insets
        }

        // Initialize UI components
        initializeViews(view)

        // Setup button listeners
        setupButtonListeners()
    }

    private fun initializeViews(view: View) {
        topAppBar = view.findViewById(R.id.topAppBar)
        clearCacheButton = view.findViewById(R.id.clearCacheButton)
        restoreDefaultsButton = view.findViewById(R.id.restoreDefaultsButton)

        setupTopAppBar()
    }

    private fun setupTopAppBar() {
        // Back button - navigate to previous fragment
        topAppBar.setNavigationOnClickListener {
            val activity = requireActivity() as? SettingsContainerActivity
            if (activity?.navigateBack() != true) {
                // No history - finish the activity
                activity?.finish()
            }
        }

        // Menu item click handler
        topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_generation -> {
                    (requireActivity() as? SettingsContainerActivity)?.navigateToGeneration()
                    true
                }
                R.id.action_logout -> {
                    (requireActivity() as? SettingsContainerActivity)?.logout()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtonListeners() {
        clearCacheButton.setOnClickListener {
            clearCache()
        }

        restoreDefaultsButton.setOnClickListener {
            restoreDefaults()
        }
    }

    private fun clearCache() {
        // List of cached image/video files to delete
        val cachedFiles = listOf(
            "last_generated_image.png",           // TextToImageFragment
            "last_generated_video.mp4",           // TextToVideoFragment
            "inpainting_last_preview.png",        // InpaintingFragment preview
            "inpainting_last_source.png",         // InpaintingFragment source
            "inpainting_last_mask.png"            // InpaintingFragment mask
        )

        // Delete each cached file
        cachedFiles.forEach { filename ->
            try {
                requireContext().deleteFile(filename)
            } catch (e: Exception) {
                println("Failed to delete $filename: ${e.message}")
            }
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.cache_cleared_success),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun restoreDefaults() {
        // List of SharedPreferences to clear
        val prefsToDelete = listOf(
            "TextToImageFragmentPrefs",
            "TextToVideoFragmentPrefs",
            "InpaintingFragmentPrefs"
        )

        // Delete each SharedPreferences file
        prefsToDelete.forEach { prefsName ->
            try {
                requireContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            } catch (e: Exception) {
                println("Failed to clear $prefsName: ${e.message}")
            }
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.defaults_restored_success),
            Toast.LENGTH_SHORT
        ).show()
    }
}
