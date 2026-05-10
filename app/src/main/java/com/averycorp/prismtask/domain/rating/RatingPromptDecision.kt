package com.averycorp.prismtask.domain.rating

/** Outcome of a [RatingPromptTriggerHelper] gate evaluation. */
sealed class RatingPromptDecision {
    object None : RatingPromptDecision()
    object PlayReview : RatingPromptDecision()
    object CustomPrompt : RatingPromptDecision()
}
