package eu.kanade.tachiyomi.animeextension.all.pikpak

/**
 * Picks a single stream variant for the configured resolution.
 *
 * A preferred resolution is matched exactly first, then the closest higher resolution, then higher
 * ones in ascending order, then lower ones starting from the closest. Variants without a numeric
 * label (such as an unlabelled original) rank above every named resolution.
 */
internal object QualitySelector {
    const val AUTO = "auto"
    const val ORIGIN = "origin"

    /** Resolutions PikPak commonly transcodes to, best first. */
    val COMMON_RESOLUTIONS = listOf("1080p", "720p", "480p", "360p")

    private const val ORIGIN_RANK = 10_000

    /**
     * Returns the variants to expose. [AUTO] keeps every stream, any other value returns only the
     * best match so playback and downloads use that resolution.
     */
    fun select(
        variants: List<PikPakClient.StreamVariant>,
        preference: String?,
    ): List<PikPakClient.StreamVariant> {
        val value = preference?.trim().orEmpty()
        if (value.isEmpty() || value.equals(AUTO, ignoreCase = true)) return variants

        val ordered = order(variants, value)
        return if (ordered.isEmpty()) variants else listOf(ordered.first())
    }

    fun order(
        variants: List<PikPakClient.StreamVariant>,
        preference: String,
    ): List<PikPakClient.StreamVariant> {
        if (preference.equals(ORIGIN, ignoreCase = true)) {
            val origin = variants.filter { it.isOrigin }
            val rest = variants.filterNot { it.isOrigin }.sortedByDescending(::rank)
            return origin + rest
        }

        val target = preference.removeSuffix("p").removeSuffix("P").toIntOrNull() ?: return variants
        if (target <= 0) return variants

        val exact = variants.filter { rank(it) == target }
        val higher = variants.filter { rank(it) > target }.sortedBy(::rank)
        val lower = variants.filter { rank(it) in 1 until target }.sortedByDescending(::rank)
        val unknown = variants.filter { rank(it) <= 0 }
        return exact + higher + lower + unknown
    }

    /** Numeric height of a variant; unlabelled originals rank above all named resolutions. */
    fun rank(variant: PikPakClient.StreamVariant): Int {
        val numeric = PikPakClient.streamQualityRank(variant.title)
        if (numeric > 0) return numeric
        return if (variant.isOrigin) ORIGIN_RANK else 0
    }
}
