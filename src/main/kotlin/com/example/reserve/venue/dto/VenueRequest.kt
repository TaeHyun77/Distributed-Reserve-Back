package com.example.reserve.venue.dto

import com.example.reserve.venue.Venue

data class VenueRequest(
    val name: String,

    val location: String
) {
    fun toEntity(): Venue {
        return Venue(
            name = this.name,
            location = this.location
        )
    }
}