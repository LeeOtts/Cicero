package com.leeotts.cicero.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last place the assistant was asked about.
 *
 * This is what gives the Map screen something to show. Without it the map is
 * just a map, and the phone already has one of those; with it, "take me to the
 * nearest petrol station" leaves a pin behind that you can look at afterwards.
 *
 * Deliberately in memory only. A destination is interesting for the length of
 * one errand, and outliving the process would make the map show yesterday.
 */
class DestinationLog {

    private val _last = MutableStateFlow<Place?>(null)
    val last: StateFlow<Place?> = _last.asStateFlow()

    fun record(place: Place) {
        _last.value = place
    }

    fun clear() {
        _last.value = null
    }
}
