package com.opendash.app.tool.info

/**
 * Small original, versioned offline starter corpus.
 *
 * These are concise facts written for OpenDash, not copied Wikipedia prose.
 * The corpus is intentionally bounded; user-authored knowledge remains in
 * the Room-backed store and takes precedence over bundled facts.
 */
object OfflineKnowledgeCorpus {
    const val VERSION = "starter-2026-07-15"

    val entries: List<KnowledgeEntry> = listOf(
        fact("builtin-japan-capital", "What is the capital of Japan?", "Tokyo is the capital of Japan.", "japan,geography"),
        fact("builtin-earth-moon", "How long does the Moon take to orbit Earth?", "The Moon completes one orbit around Earth in about 27.3 days relative to the stars.", "space,astronomy"),
        fact("builtin-earth-day", "How long is an Earth day?", "Earth rotates once in about 24 hours; a solar day is approximately 24 hours.", "space,earth"),
        fact("builtin-water-boiling", "At what temperature does water boil?", "At standard sea-level pressure, water boils at 100 degrees Celsius or 212 degrees Fahrenheit.", "science,water"),
        fact("builtin-water-freezing", "At what temperature does water freeze?", "At standard atmospheric pressure, water freezes at 0 degrees Celsius or 32 degrees Fahrenheit.", "science,water"),
        fact("builtin-light-speed", "How fast does light travel in vacuum?", "Light travels through vacuum at approximately 299792458 meters per second.", "science,physics"),
        fact("builtin-solar-system", "How many planets are in the Solar System?", "The Solar System has eight recognized planets: Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus, and Neptune.", "space,astronomy"),
        fact("builtin-photosynthesis", "What is photosynthesis?", "Photosynthesis is the process plants use to turn light, water, and carbon dioxide into chemical energy and oxygen.", "biology,plants"),
        fact("builtin-http", "What does HTTP mean?", "HTTP means Hypertext Transfer Protocol, a protocol used to transfer resources on the web.", "technology,web"),
        fact("builtin-https", "What does HTTPS add to HTTP?", "HTTPS uses TLS to authenticate a server and encrypt HTTP traffic in transit.", "technology,security"),
        fact("builtin-dns", "What does DNS do?", "The Domain Name System maps human-readable domain names to network addresses such as IP addresses.", "technology,networking"),
        fact("builtin-sqlite", "What is SQLite?", "SQLite is a small embedded relational database engine that stores a database in a file.", "technology,database"),
        fact("builtin-android", "What is Android?", "Android is a mobile operating system and application platform maintained by Google and the open-source community.", "technology,mobile"),
        fact("builtin-kelvin", "What is absolute zero?", "Absolute zero is 0 kelvin, equal to -273.15 degrees Celsius; it is the lower limit of thermodynamic temperature.", "science,temperature"),
        fact("builtin-sound", "Does sound travel through vacuum?", "No. Sound needs a medium such as air, water, or a solid to propagate.", "science,physics"),
        fact("builtin-tokyo-time", "What time zone is Tokyo in?", "Tokyo uses Japan Standard Time, UTC+9, and Japan does not currently use daylight saving time.", "japan,time"),
        fact("builtin-metric", "What is the metric system?", "The metric system is a decimal measurement system built around units such as the meter, kilogram, and second.", "science,measurement"),
        fact("builtin-robotics", "What is a robot?", "A robot is a machine that senses or receives instructions and performs actions in the physical or digital world.", "technology,robotics"),
        fact("builtin-machine-learning", "What is machine learning?", "Machine learning is a method in which software learns patterns from data to make predictions or decisions.", "technology,ai"),
    )

    private fun fact(id: String, question: String, answer: String, tags: String) = KnowledgeEntry(
        id = id,
        question = question,
        answer = answer,
        tags = tags.split(',')
    )
}
