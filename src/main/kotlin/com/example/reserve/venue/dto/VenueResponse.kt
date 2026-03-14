package com.example.reserve.venue.dto

import com.example.reserve.venue.Venue

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