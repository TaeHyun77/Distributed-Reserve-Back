package com.example.reserve.venue

import com.example.reserve.venue.dto.VenueRequest
import com.example.reserve.venue.dto.VenueResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RequestMapping("/reserve/venue")
@RestController
class VenueController(
    private val venueService: VenueService
) {
    @PostMapping("/create")
    fun registerVenue(@RequestBody venueRequest: VenueRequest): ResponseEntity<Unit> {
        venueService.createVenue(venueRequest)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @GetMapping("/get/list")
    fun getVenueList(): List<VenueResponse> {
        return venueService.getVenueList()
    }
}