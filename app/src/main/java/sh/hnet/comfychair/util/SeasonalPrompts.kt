package sh.hnet.comfychair.util

import java.time.LocalDate

/**
 * Seasonal Easter egg that provides dynamic default prompts based on the current date.
 * Prompts change according to seasons and special events.
 */
object SeasonalPrompts {

    /**
     * Seasonal events with priority order (special events override seasons).
     */
    enum class SeasonalEvent {
        SPRING,
        EASTER,
        APRIL_FOOLS,
        SUMMER,
        AUTUMN,
        HALLOWEEN,
        WINTER,
        CHRISTMAS
    }

    // region Date Calculation

    /**
     * Calculate Easter Sunday using the Gauss Easter Algorithm.
     * Works for years 1900-2099.
     */
    private fun calculateEasterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    private fun isEasterPeriod(date: LocalDate): Boolean {
        val easterSunday = calculateEasterSunday(date.year)
        val start = easterSunday.minusDays(3)  // 3 days before Easter Sunday
        val end = easterSunday.plusDays(1)     // Easter Monday
        return !date.isBefore(start) && !date.isAfter(end)
    }

    private fun isAprilFools(date: LocalDate): Boolean =
        date.monthValue == 4 && date.dayOfMonth == 1

    private fun isHalloween(date: LocalDate): Boolean =
        date.monthValue == 10 && date.dayOfMonth in 28..31

    private fun isChristmas(date: LocalDate): Boolean =
        date.monthValue == 12 && date.dayOfMonth in 20..26

    private fun getSeason(date: LocalDate): SeasonalEvent {
        val monthDay = date.monthValue * 100 + date.dayOfMonth
        return when {
            monthDay in 320..620 -> SeasonalEvent.SPRING   // March 20 - June 20
            monthDay in 621..922 -> SeasonalEvent.SUMMER   // June 21 - September 22
            monthDay in 923..1220 -> SeasonalEvent.AUTUMN  // September 23 - December 20
            else -> SeasonalEvent.WINTER                    // December 21 - March 19
        }
    }

    /**
     * Get current seasonal event with priority:
     * April Fools > Easter > Halloween > Christmas > Season
     */
    fun getCurrentEvent(date: LocalDate = LocalDate.now()): SeasonalEvent {
        return when {
            isAprilFools(date) -> SeasonalEvent.APRIL_FOOLS
            isEasterPeriod(date) -> SeasonalEvent.EASTER
            isHalloween(date) -> SeasonalEvent.HALLOWEEN
            isChristmas(date) -> SeasonalEvent.CHRISTMAS
            else -> getSeason(date)
        }
    }

    // endregion

    // region Text to Image Prompts

    private val textToImagePrompts = mapOf(
        SeasonalEvent.SPRING to "A warm and cozy photo of a woman sitting in a comfy chair by an open window, sipping coffee, soft spring light, blooming flowers outside",
        SeasonalEvent.EASTER to "A warm and cozy photo of a woman sitting in a comfy chair, sipping coffee, soft spring light, subtle Easter decorations",
        SeasonalEvent.APRIL_FOOLS to "A warm and cozy photo of a woman sitting on a wooden stool and sipping coffee, immediately regretting the first sip, her facial expression showing playful disgust, April Fools atmosphere",
        SeasonalEvent.SUMMER to "A warm and cozy photo of a woman sitting in a comfy chair, sipping iced coffee, golden summer light, relaxed and sunny mood",
        SeasonalEvent.AUTUMN to "A warm and cozy photo of a woman sitting in a comfy chair, sipping coffee, wrapped in a light sweater, autumn colors and falling leaves outside",
        SeasonalEvent.HALLOWEEN to "A warm and cozy photo of a woman sitting in a comfy chair, sipping coffee, dim candlelight, subtle Halloween decorations",
        SeasonalEvent.WINTER to "A warm and cozy photo of a woman sitting in a comfy chair, sipping hot coffee, wrapped in a blanket, winter light and snow outside",
        SeasonalEvent.CHRISTMAS to "A warm and cozy photo of a woman sitting in a comfy chair, sipping coffee, soft Christmas lights, decorated tree in the background"
    )

    // endregion

    // region Image to Image Prompts

    private val imageToImagePrompts = mapOf(
        SeasonalEvent.SPRING to "A woman with short, pink hair, soft spring lighting, fresh and natural tones",
        SeasonalEvent.EASTER to "A woman with short, pink hair, fresh spring lighting, gentle pastel tones",
        SeasonalEvent.APRIL_FOOLS to "A woman with short, pink hair styled in an intentionally uneven and chaotic haircut, as if done by an overenthusiastic hairdresser, playful April Fools mood",
        SeasonalEvent.SUMMER to "A woman with short, pink hair, sunlit highlights, warm summer tones",
        SeasonalEvent.AUTUMN to "A woman with short, pink hair, soft autumn lighting, warm and earthy tones",
        SeasonalEvent.HALLOWEEN to "A woman with short, pink hair, moody lighting, slightly spooky but cozy atmosphere",
        SeasonalEvent.WINTER to "A woman with short, pink hair, soft winter lighting, cool tones with warm highlights",
        SeasonalEvent.CHRISTMAS to "A woman with short, pink hair, warm festive lighting, subtle Christmas atmosphere"
    )

    // endregion

    // region Text to Video Prompts

    private val textToVideoPrompts = mapOf(
        SeasonalEvent.SPRING to "A woman sitting in a comfy chair near an open window, reading a book, gentle spring breeze moving the curtains, pages turning softly",
        SeasonalEvent.EASTER to "A woman sitting in a comfy chair, reading a book, calm Easter morning atmosphere, soft light and gentle page turns",
        SeasonalEvent.APRIL_FOOLS to "A woman sitting in a comfy chair, calmly reading an invisible book, gently turning nonexistent pages, playful April Fools atmosphere",
        SeasonalEvent.SUMMER to "A woman sitting in a comfy chair, reading a book, sunlight streaming in, slow and lazy summer atmosphere",
        SeasonalEvent.AUTUMN to "A woman sitting in a comfy chair, reading a book, cozy autumn atmosphere, soft light and calm page turns",
        SeasonalEvent.HALLOWEEN to "A woman sitting in a comfy chair, reading a book, soft candlelight flickering, cozy Halloween mood",
        SeasonalEvent.WINTER to "A woman sitting in a comfy chair, reading a book, quiet winter atmosphere, slow and gentle page turns",
        SeasonalEvent.CHRISTMAS to "A woman sitting in a comfy chair, reading a book, soft Christmas lights glowing, peaceful holiday mood"
    )

    // endregion

    // region Image to Video Prompts

    private val imageToVideoPrompts = mapOf(
        SeasonalEvent.SPRING to "The woman gently moves, her hair swaying slightly in a light spring breeze, calm and fresh atmosphere",
        SeasonalEvent.EASTER to "The woman gently moves, her hair swaying slightly in a soft spring breeze",
        SeasonalEvent.APRIL_FOOLS to "The woman gently moves, her hair swaying slightly, as if the animation briefly forgets what it was supposed to do",
        SeasonalEvent.SUMMER to "The woman gently moves, her hair swaying slightly in warm summer air, relaxed motion",
        SeasonalEvent.AUTUMN to "The woman gently moves, her hair swaying slightly as autumn air drifts through the room",
        SeasonalEvent.HALLOWEEN to "The woman gently moves, her hair swaying slightly, shadows dancing softly around her",
        SeasonalEvent.WINTER to "The woman gently moves, her hair swaying slightly, calm and cozy winter mood",
        SeasonalEvent.CHRISTMAS to "The woman gently moves, her hair swaying slightly in warm festive light"
    )

    // endregion

    // region Public Getters

    fun getTextToImagePrompt(): String =
        textToImagePrompts[getCurrentEvent()] ?: textToImagePrompts[SeasonalEvent.WINTER]!!

    fun getImageToImagePrompt(): String =
        imageToImagePrompts[getCurrentEvent()] ?: imageToImagePrompts[SeasonalEvent.WINTER]!!

    fun getTextToVideoPrompt(): String =
        textToVideoPrompts[getCurrentEvent()] ?: textToVideoPrompts[SeasonalEvent.WINTER]!!

    fun getImageToVideoPrompt(): String =
        imageToVideoPrompts[getCurrentEvent()] ?: imageToVideoPrompts[SeasonalEvent.WINTER]!!

    // endregion
}
