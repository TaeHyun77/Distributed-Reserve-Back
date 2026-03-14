package com.example.reserve.venue

import com.example.reserve.venue.dto.VenueRequest
import com.example.reserve.venue.dto.VenueResponse
import org.springframework.web.bind.annotation.*

@RequestMapping("/api/venue")
@RestController
class VenueController(
    private val venueService: VenueService
) {
    @PostMapping("/create")
    fun registerVenue(@RequestBody venueRequest: VenueRequest) {
        return venueService.createVenue(venueRequest)
    }

    @GetMapping("/get/list")
    fun getVenueList(): List<VenueResponse> {
        return venueService.getVenueList()
    }
}