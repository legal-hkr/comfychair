package sh.hnet.comfychair.workflow.routing

import android.content.Context
import sh.hnet.comfychair.storage.AppSettings

/**
 * Provider for edge routing algorithms.
 * Manages router registration and selection based on user settings.
 */
object RouterProvider {

    private const val DEFAULT_ROUTER_ID = "hermite"

    private val routers: Map<String, EdgeRouter> = linkedMapOf(
        "hermite" to CubicHermiteRouter(),
        "bezier" to CubicBezierRouter()
    )

    /**
     * Get a router by its ID.
     * Returns null if the ID is not registered.
     */
    fun getRouter(id: String): EdgeRouter? = routers[id]

    /**
     * Get the default router (Hermite).
     */
    fun getDefault(): EdgeRouter = routers[DEFAULT_ROUTER_ID]!!

    /**
     * Get the active router based on user settings.
     * Falls back to default if the saved ID is invalid.
     */
    fun getActiveRouter(context: Context): EdgeRouter {
        val savedId = AppSettings.getEdgeRouterId(context)
        return routers[savedId] ?: getDefault()
    }

    /**
     * Get all available routers for settings UI.
     */
    fun getAvailable(): List<EdgeRouter> = routers.values.toList()
}
