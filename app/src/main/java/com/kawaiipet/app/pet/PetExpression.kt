package com.kawaiipet.app.pet

import androidx.annotation.DrawableRes
import com.kawaiipet.app.R

enum class PetExpression(
    val lottieAsset: String,
    @DrawableRes val drawableRes: Int
) {
    IDLE("lottie/pet_idle.json", R.drawable.slime_idle),
    HAPPY("lottie/pet_happy.json", R.drawable.slime_happy),
    SAD("lottie/pet_sad.json", R.drawable.slime_sad),
    ANGRY("lottie/pet_angry.json", R.drawable.slime_sad),
    THINKING("lottie/pet_thinking.json", R.drawable.slime_thinking),
    TALKING("lottie/pet_talking.json", R.drawable.slime_talking),
    SLEEPING("lottie/pet_sleeping.json", R.drawable.slime_sleeping),
    LISTENING("lottie/pet_listening.json", R.drawable.slime_listening);

    companion object {
        fun fromTag(tag: String): PetExpression = when (tag.lowercase()) {
            "happy" -> HAPPY
            "sad" -> SAD
            "angry" -> ANGRY
            "thinking" -> THINKING
            "curious" -> THINKING
            "talking" -> TALKING
            "sleeping" -> SLEEPING
            "listening" -> LISTENING
            else -> IDLE
        }
    }
}
