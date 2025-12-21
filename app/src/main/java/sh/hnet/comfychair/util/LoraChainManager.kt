package sh.hnet.comfychair.util

import sh.hnet.comfychair.model.LoraSelection

/**
 * Centralized LoRA chain management utilities.
 * Reduces duplicate LoRA chain logic across ViewModels.
 */
object LoraChainManager {

    /**
     * Add a new LoRA to the chain.
     * @param chain Current LoRA chain
     * @param availableLoras List of available LoRA models
     * @param maxSize Maximum chain size (default 5)
     * @return New chain with added LoRA, or original chain if at max size or no available LoRAs
     */
    fun addLora(
        chain: List<LoraSelection>,
        availableLoras: List<String>,
        maxSize: Int = LoraSelection.MAX_CHAIN_LENGTH
    ): List<LoraSelection> {
        if (chain.size >= maxSize) return chain
        if (availableLoras.isEmpty()) return chain

        val newLora = LoraSelection(
            name = availableLoras.first(),
            strength = LoraSelection.DEFAULT_STRENGTH
        )
        return chain + newLora
    }

    /**
     * Remove a LoRA from the chain at the specified index.
     * @param chain Current LoRA chain
     * @param index Index of LoRA to remove
     * @return New chain with LoRA removed, or original chain if index is invalid
     */
    fun removeLora(chain: List<LoraSelection>, index: Int): List<LoraSelection> {
        if (index !in chain.indices) return chain
        return chain.toMutableList().apply { removeAt(index) }
    }

    /**
     * Update the name of a LoRA at the specified index.
     * @param chain Current LoRA chain
     * @param index Index of LoRA to update
     * @param name New LoRA name
     * @return New chain with updated LoRA, or original chain if index is invalid
     */
    fun updateLoraName(chain: List<LoraSelection>, index: Int, name: String): List<LoraSelection> {
        if (index !in chain.indices) return chain
        return chain.toMutableList().apply {
            this[index] = this[index].copy(name = name)
        }
    }

    /**
     * Update the strength of a LoRA at the specified index.
     * @param chain Current LoRA chain
     * @param index Index of LoRA to update
     * @param strength New strength value (will be clamped to valid range)
     * @return New chain with updated LoRA, or original chain if index is invalid
     */
    fun updateLoraStrength(chain: List<LoraSelection>, index: Int, strength: Float): List<LoraSelection> {
        if (index !in chain.indices) return chain
        val clampedStrength = strength.coerceIn(LoraSelection.MIN_STRENGTH, LoraSelection.MAX_STRENGTH)
        return chain.toMutableList().apply {
            this[index] = this[index].copy(strength = clampedStrength)
        }
    }

    /**
     * Filter out LoRAs that are no longer available.
     * @param chain Current LoRA chain
     * @param availableLoras List of currently available LoRA models
     * @return Filtered chain containing only available LoRAs
     */
    fun filterUnavailable(chain: List<LoraSelection>, availableLoras: List<String>): List<LoraSelection> {
        return chain.filter { it.name in availableLoras }
    }
}
