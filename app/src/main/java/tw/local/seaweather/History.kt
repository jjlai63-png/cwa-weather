package tw.local.seaweather

const val HISTORY_LIMIT = 20

/** Newest first; looking a place up again moves it to the front instead of repeating it. */
fun withVisit(history: List<String>, place: String): List<String> =
    (listOf(place) + history.filter { it != place }).take(HISTORY_LIMIT)
