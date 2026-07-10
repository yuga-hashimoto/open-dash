package com.opendash.app.tool.entertainment

/**
 * A single trivia prompt with its answer, for [BundledFunContent.TRIVIA].
 */
data class TriviaQuestion(
    val question: String,
    val answer: String
)

/**
 * Curated, fixed content for the entertainment tools (`tell_joke`,
 * `get_trivia`, `fun_fact`). Bundled in-app rather than fetched from an
 * external joke/trivia API — no key, no network call, no per-request
 * latency, matching this app's local-first bias (same rationale as
 * `BundledNewsFeeds`). Kept as plain Kotlin lists rather than a JSON
 * asset since there's no user-facing picker UI to justify the extra
 * asset-loading machinery — just a fixed catalog [FunToolExecutor]
 * samples from.
 */
object BundledFunContent {

    val JOKES: List<String> = listOf(
        "Why don't scientists trust atoms? Because they make up everything.",
        "I told my computer I needed a break, and it said no problem — it would go to sleep.",
        "Why did the scarecrow win an award? He was outstanding in his field.",
        "I'm reading a book about anti-gravity. It's impossible to put down.",
        "Why don't skeletons fight each other? They don't have the guts.",
        "What do you call fake spaghetti? An impasta.",
        "Why did the bicycle fall over? It was two tired.",
        "I used to be a banker, but I lost interest.",
        "Why don't programmers like nature? It has too many bugs.",
        "What do you call a fish with no eyes? A fsh.",
        "How does a penguin build its house? Igloos it together.",
        "Why did the coffee file a police report? It got mugged.",
        "I would tell you a joke about pizza, but it's a bit cheesy.",
        "What do you call a bear with no teeth? A gummy bear.",
        "Why can't your nose be 12 inches long? Because then it would be a foot.",
        "What did one wall say to the other wall? I'll meet you at the corner.",
        "Why did the math book look sad? It had too many problems.",
        "What do you call a factory that makes okay products? A satisfactory.",
        "Why did the golfer bring two pairs of pants? In case he got a hole in one.",
        "What's orange and sounds like a parrot? A carrot.",
        "私は木こりの友達です。彼、木ばっかりです。",
        "パン屋さんが焼いたパンを土に埋めました。なぜでしょう? 地味(地面)にしたかったから。",
        "布団が吹っ飛んだ。",
        "アルミ缶の上にあるみかん。"
    )

    val TRIVIA: List<TriviaQuestion> = listOf(
        TriviaQuestion("What is the largest planet in our solar system?", "Jupiter"),
        TriviaQuestion("How many continents are there on Earth?", "Seven"),
        TriviaQuestion("What is the chemical symbol for gold?", "Au"),
        TriviaQuestion("Which animal is known as the ship of the desert?", "The camel"),
        TriviaQuestion("What is the tallest mountain in the world?", "Mount Everest"),
        TriviaQuestion("How many strings does a standard guitar have?", "Six"),
        TriviaQuestion("What is the freezing point of water in Celsius?", "Zero degrees"),
        TriviaQuestion("Which country gifted the Statue of Liberty to the United States?", "France"),
        TriviaQuestion("What is the smallest prime number?", "Two"),
        TriviaQuestion("How many hearts does an octopus have?", "Three"),
        TriviaQuestion("What is the capital city of Japan?", "Tokyo"),
        TriviaQuestion("Which planet is known as the Red Planet?", "Mars"),
        TriviaQuestion("What is the largest ocean on Earth?", "The Pacific Ocean"),
        TriviaQuestion("How many bones are in the adult human body?", "206"),
        TriviaQuestion("What gas do plants absorb from the atmosphere?", "Carbon dioxide"),
        TriviaQuestion("Who wrote the play Romeo and Juliet?", "William Shakespeare"),
        TriviaQuestion("What is the speed of light approximately, in kilometers per second?", "About 300,000 kilometers per second"),
        TriviaQuestion("Which element has the atomic number 1?", "Hydrogen"),
        TriviaQuestion("What is the longest river in the world?", "The Nile"),
        TriviaQuestion("How many minutes are there in a full day?", "1,440 minutes")
    )

    val FACTS: List<String> = listOf(
        "Honey never spoils — archaeologists have found 3,000-year-old honey in Egyptian tombs that's still edible.",
        "Octopuses have three hearts and blue blood.",
        "A group of flamingos is called a flamboyance.",
        "Bananas are berries, but strawberries aren't.",
        "The Eiffel Tower can be up to 15 centimeters taller in summer due to heat expansion.",
        "A single cloud can weigh more than a million pounds.",
        "Sea otters hold hands while sleeping so they don't drift apart.",
        "There are more possible chess games than atoms in the observable universe.",
        "Sharks existed before trees.",
        "A day on Venus is longer than a year on Venus.",
        "Wombat droppings are cube-shaped.",
        "The shortest war in recorded history lasted about 38 minutes.",
        "Butterflies taste with their feet.",
        "It's raining diamonds on Neptune and Uranus, according to some atmospheric models.",
        "The human nose can detect over a trillion distinct scents.",
        "Cows have best friends and get stressed when separated from them.",
        "富士山の高さは3776メートルです。",
        "タコには心臓が3つあります。",
        "はちみつは腐らないと言われています。",
        "1日は正確には24時間ではなく、約23時間56分です。"
    )
}
