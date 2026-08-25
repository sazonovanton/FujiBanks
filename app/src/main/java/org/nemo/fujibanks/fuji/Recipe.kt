package org.nemo.fujibanks.fuji

import kotlinx.serialization.Serializable

/**
 * A recipe in the values a photographer reads in the camera menu — +2 sharpness,
 * DR400, WB shift -4 blue. Everything camera-facing lives in [Codec]; nothing
 * here carries a wire encoding.
 */
@Serializable
data class Recipe(
    val name: String = "",
    val filmSimulation: Int = FilmSim.PROVIA,
    /** Raw percentage: 100, 200 or 400. */
    val dynamicRange: Int = DynamicRange.DR100,
    val grain: Grain = Grain.OFF,
    val colorChrome: Effect = Effect.OFF,
    val colorChromeFxBlue: Effect = Effect.OFF,
    val smoothSkin: Effect = Effect.OFF,
    val whiteBalance: Int = WhiteBalance.AUTO,
    val wbShiftR: Int = 0,
    val wbShiftB: Int = 0,
    val wbColorTemp: Int = 6500,
    /** Tone/colour adjustments in menu units — half steps allowed (e.g. -1.5). */
    val highlightTone: Double = 0.0,
    val shadowTone: Double = 0.0,
    val color: Double = 0.0,
    val sharpness: Double = 0.0,
    val clarity: Double = 0.0,
    /** High ISO NR, -4..+4. */
    val noiseReduction: Int = 0,
    /** Monochromatic warm/cool and magenta/green, B&W simulations only. */
    val monoWC: Double = 0.0,
    val monoMG: Double = 0.0,
    /** Free-form note — ISO ranges and exposure compensation from a recipe
     *  source live here, since neither can be stored in a bank. */
    val notes: String = "",
    /**
     * A sample frame shot with this recipe, kept on the phone only.
     *
     * The id names a file in `PhotoStore`; the bytes never go in here, and none
     * of this reaches the camera — a bank has no room for a photograph. It is a
     * reference, so duplicating a recipe shares the file rather than copying it.
     */
    val photoId: String? = null,
) {
    val isMono: Boolean get() = FilmSim.isMono(filmSimulation)

    /** Short human summary for a collapsed card. */
    fun summary(): String = buildString {
        append(FilmSim.label(filmSimulation))
        append(" · ")
        append(DynamicRange.label(dynamicRange))
        if (grain != Grain.OFF) append(" · Grain ${grain.label}")
    }
}

/** One bank as it currently stands in the camera. */
@Serializable
data class Bank(
    val slot: Int,
    val recipe: Recipe,
)

/** A named set of up to seven recipes, ready to be written to the camera. */
@Serializable
data class RecipePack(
    val name: String,
    val recipes: List<Recipe>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(recipes.size <= FujiProps.SLOT_COUNT) {
            "A pack holds at most ${FujiProps.SLOT_COUNT} recipes, got ${recipes.size}"
        }
    }
}

/** Everything the camera held at one moment, kept so a write can be undone. */
@Serializable
data class BankSnapshot(
    val cameraModel: String,
    val serialNumber: String,
    val takenAt: Long,
    val banks: List<Bank>,
)
