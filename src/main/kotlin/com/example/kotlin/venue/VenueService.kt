package com.example.kotlin.venue

import com.example.kotlin.config.Loggable
import com.example.kotlin.venue.dto.VenueRequest
import com.example.kotlin.venue.dto.VenueResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class VenueService(
    private val venueRepository: VenueRepository
) {

    @Transactional
    fun createVenue(venueRequest: VenueRequest) {
        venueRepository.save(venueRequest.toEntity())
    }

    fun getVenueList(): List<VenueResponse> {
        return venueRepository.findAll()
            .map(VenueResponse::from)
    }
}