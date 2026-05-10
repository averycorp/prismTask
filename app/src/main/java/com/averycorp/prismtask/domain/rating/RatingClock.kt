package com.averycorp.prismtask.domain.rating

fun interface RatingClock {
    fun now(): Long
}
