package com.example.kotlin.venue.dto

import com.example.kotlin.venue.Venue

data class VenueResponse(
    val id: Long,

    val name: String,

    val location: String
) {
    companion object {
        fun from(venue: Venue): VenueResponse {
            return VenueResponse(
                venue.id!!,
                venue.name,
                venue.location
            )
        }
    }
}