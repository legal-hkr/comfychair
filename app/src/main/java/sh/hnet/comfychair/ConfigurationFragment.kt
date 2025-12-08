package sh.hnet.comfychair

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONObject

/**
 * ConfigurationFragment - Configuration and server management screen
 */
class ConfigurationFragment : Fragment() {

    // UI element references
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var hostnameValue: TextView
    private lateinit var portValue: TextView
    private lateinit var systemStatsValue: TextView
    private lateinit var clearQueueButton: Button
    private lateinit var clearHistoryButton: Button

    // ComfyUI client
    private lateinit var comfyUIClient: ComfyUIClient

    // Connection information
    private var hostname: String = ""
    private var port: Int = 8188

    companion object {
        private const val ARG_HOSTNAME = "hostname"
        private const val ARG_PORT = "port"

        fun newInstance(hostname: String, port: Int): ConfigurationFragment {
            val fragment = ConfigurationFragment()
            val args = Bundle()
            args.putString(ARG_HOSTNAME, hostname)
            args.putInt(ARG_PORT, port)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get connection information from arguments
        arguments?.let {
            hostname = it.getString(ARG_HOSTNAME) ?: ""
            port = it.getInt(ARG_PORT, 8188)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_configuration, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle window insets
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, 0)
            insets
        }

        // Get ComfyUI client from activity (reuse the connected client)
        val activity = requireActivity() as MainContainerActivity
        comfyUIClient = activity.getComfyUIClient()

        // Initialize UI components
        initializeViews(view)

        // Load server information (wait for connection to be ready)
        loadServerInfo()

        // Setup button listeners
        setupButtonListeners()
    }

    private fun initializeViews(view: View) {
        topAppBar = view.findViewById(R.id.topAppBar)
        hostnameValue = view.findViewById(R.id.hostnameValue)
        portValue = view.findViewById(R.id.portValue)
        systemStatsValue = view.findViewById(R.id.systemStatsValue)
        clearQueueButton = view.findViewById(R.id.clearQueueButton)
        clearHistoryButton = view.findViewById(R.id.clearHistoryButton)

        setupTopAppBar()
    }

    private fun setupTopAppBar() {
        topAppBar.setNavigationOnClickListener {
            requireActivity().finish()
        }
    }

    private fun loadServerInfo() {
        // Display hostname and port
        hostnameValue.text = hostname
        portValue.text = port.toString()

        // Fetch system stats when connection is ready
        val activity = requireActivity() as MainContainerActivity
        activity.onConnectionReady {
            activity.runOnUiThread {
                fetchSystemStats()
            }
        }
    }

    private fun fetchSystemStats() {
        comfyUIClient.getSystemStats { statsJson ->
            activity?.runOnUiThread {
                if (statsJson != null) {
                    displaySystemStats(statsJson)
                } else {
                    systemStatsValue.text = "Failed to fetch system stats"
                }
            }
        }
    }

    private fun displaySystemStats(statsJson: JSONObject) {
        val system = statsJson.optJSONObject("system")
        val devices = statsJson.optJSONArray("devices")

        val statsText = buildString {
            // System info
            system?.let {
                val osName = it.optString("os", "Unknown")
                val comfyuiVersion = it.optString("comfyui_version", "Unknown")
                val pythonVersion = it.optString("python_version", "Unknown")
                val pytorchVersion = it.optString("pytorch_version", "Unknown")

                appendLine("OS: $osName")
                appendLine("ComfyUI: $comfyuiVersion")
                appendLine("Python: $pythonVersion")
                appendLine("PyTorch: $pytorchVersion")

                // RAM info
                val ramTotal = it.optLong("ram_total", 0)
                val ramFree = it.optLong("ram_free", 0)
                if (ramTotal > 0) {
                    val ramTotalGB = ramTotal / (1024.0 * 1024.0 * 1024.0)
                    val ramFreeGB = ramFree / (1024.0 * 1024.0 * 1024.0)
                    appendLine("Available RAM: %.2f GB / %.2f GB".format(ramFreeGB, ramTotalGB))
                }
            }

            // Device info (GPUs)
            if (devices != null && devices.length() > 0) {
                appendLine()
                for (i in 0 until devices.length()) {
                    val device = devices.optJSONObject(i)
                    device?.let { dev ->
                        val name = dev.optString("name", "Unknown")
                        val type = dev.optString("type", "Unknown")
                        val vramTotal = dev.optLong("vram_total", 0)
                        val vramFree = dev.optLong("vram_free", 0)

                        // Extract GPU name from the full name string
                        // Format: "cuda:0 NVIDIA GeForce RTX 4080 SUPER : cudaMallocAsync"
                        val gpuName = name.split(":").getOrNull(1)?.trim() ?: name

                        appendLine("GPU ${i}: $gpuName")
                        if (vramTotal > 0) {
                            val vramTotalGB = vramTotal / (1024.0 * 1024.0 * 1024.0)
                            val vramFreeGB = vramFree / (1024.0 * 1024.0 * 1024.0)
                            appendLine("Available VRAM: %.2f GB / %.2f GB".format(vramFreeGB, vramTotalGB))
                        }

                        if (i < devices.length() - 1) {
                            appendLine()
                        }
                    }
                }
            }
        }

        systemStatsValue.text = statsText.trim()
    }

    private fun setupButtonListeners() {
        clearQueueButton.setOnClickListener {
            clearQueue()
        }

        clearHistoryButton.setOnClickListener {
            clearHistory()
        }
    }

    private fun clearQueue() {
        clearQueueButton.isEnabled = false
        comfyUIClient.clearQueue { success ->
            activity?.runOnUiThread {
                clearQueueButton.isEnabled = true
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.queue_cleared_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.queue_cleared_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun clearHistory() {
        clearHistoryButton.isEnabled = false
        comfyUIClient.clearHistory { success ->
            activity?.runOnUiThread {
                clearHistoryButton.isEnabled = true
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.history_cleared_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.history_cleared_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't shutdown client - it's managed by MainContainerActivity
    }
}
