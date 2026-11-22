"""The made-up demo library: albums, podcasts and audiobooks for store screenshots and demos.

Everything here is invented. Names, covers and texts must not resemble real artists, shows or books.
Durations are in seconds. Cover styles: see covers.py (`palette` = two or three hex colours, `motif` = drawing).
"""

ALBUMS = [
    dict(
        artist="The Buffer Underruns", title="EarLoft - Greatest Hits", year=2026, genre="Pop",
        palette=("#ff6a3d", "#ffd23f", "#2b1b3d"), motif="rings",
        tracks=[
            ("Gapless (Finally)", 214), ("Waiting for Wi-Fi", 238), ("Sync Conflict", 197),
            ("Offline Again", 251), ("404 Love Not Found", 186), ("Shake to Skip", 172),
            ("ReplayGain Romance", 263), ("The Watch Is Downloading", 305), ("Sleep Timer Lullaby", 284),
        ],
    ),
    dict(
        artist="Velvet Static", title="Neon Harbor", year=2024, genre="Synthpop",
        palette=("#ff2e88", "#3a0ca3", "#0b0b2b"), motif="sun",
        tracks=[
            ("Harbor Lights", 236), ("Chrome Tide", 221), ("Afterglow Avenue", 247), ("Night Ferry", 268),
            ("Pulse of the City", 203), ("Signal Fire", 229), ("Soft Machines", 255), ("Last Train to Dawn", 312),
        ],
    ),
    dict(
        artist="Velvet Static", title="Sunday Static", year=2021, genre="Synthpop",
        palette=("#06d6a0", "#118ab2", "#073b4c"), motif="grid",
        tracks=[
            ("Sunday Static", 228), ("Polaroid Summer", 211), ("Cassette Hearts", 244), ("Glow", 189),
            ("Holding Pattern", 262), ("Weekend Satellite", 237), ("Rewind", 276),
        ],
    ),
    dict(
        artist="Juniper Fields", title="Paper Lanterns", year=2023, genre="Indie Folk",
        palette=("#f4a261", "#e9c46a", "#264653"), motif="lanterns",
        tracks=[
            ("Paper Lanterns", 243), ("Little Wooden Boat", 198), ("Orchard Road", 226), ("Smoke and Clover", 257),
            ("The Long Way Home", 284), ("Fireflies in June", 209), ("Kettle Song", 175), ("Mountain Letters", 301),
        ],
    ),
    dict(
        artist="The Copper Kites", title="Midnight Freight", year=2022, genre="Indie Rock",
        palette=("#d62828", "#f77f00", "#1d1d1d"), motif="stripes",
        tracks=[
            ("Midnight Freight", 232), ("Rust Belt Radio", 218), ("Motel Neon", 245), ("Gravel Roads", 207),
            ("Copper Sky", 263), ("Highway Psalm", 289), ("Tail Lights", 224), ("Engine Heart", 251),
            ("Station Seven", 318),
        ],
    ),
    dict(
        artist="The Copper Kites", title="Clockwork Summer", year=2025, genre="Indie Rock",
        palette=("#ffb703", "#fb8500", "#023047"), motif="gears",
        tracks=[
            ("Clockwork Summer", 227), ("Paper Planes", 203), ("Wildfire Season", 249), ("Tin Can Phones", 194),
            ("Golden Hour Again", 266), ("The Long Weekend", 238), ("August Static", 281),
        ],
    ),
    dict(
        artist="Marisol Vane", title="Low Tide Sessions", year=2021, genre="Jazz",
        palette=("#2a9d8f", "#a8dadc", "#1d3557"), motif="waves",
        tracks=[
            ("Low Tide", 312), ("Blue Hour Waltz", 287), ("Driftwood", 341), ("Salt and Brass", 266),
            ("Moonlit Pier", 358), ("Seagull Bossa", 244), ("Undertow", 397),
        ],
    ),
    dict(
        artist="Orbit & Oak", title="Satellite Gardens", year=2025, genre="Electronic",
        palette=("#80ffdb", "#7400b8", "#10002b"), motif="planets",
        tracks=[
            ("Satellite Gardens", 274), ("Photosynthesis", 302), ("Low Orbit", 255), ("Moss Protocol", 289),
            ("Solar Bloom", 316), ("Root System", 243), ("Greenhouse at Night", 334), ("Seed Vault", 268),
        ],
    ),
    dict(
        artist="Northbound Choir", title="Glass Mountains", year=2020, genre="Post-Rock",
        palette=("#caf0f8", "#48cae4", "#03045e"), motif="mountains",
        tracks=[
            ("Glass Mountains", 412), ("Frostline", 356), ("The Quiet Avalanche", 489), ("Aurora Ascending", 377),
            ("Thin Air", 331), ("Summit", 528),
        ],
    ),
    dict(
        artist="Ruby Calloway", title="Honey & Gravel", year=2023, genre="Soul",
        palette=("#ffbe0b", "#8338ec", "#3a0e2e"), motif="circles",
        tracks=[
            ("Honey & Gravel", 223), ("Sweet Trouble", 241), ("Porch Light Blues", 268), ("Hold Me Steady", 236),
            ("Velvet Morning", 212), ("Cornerstone", 254), ("One More Sunday", 289), ("Gravel Road Gospel", 277),
        ],
    ),
    dict(
        artist="Ada Linden", title="Quiet Hours", year=2024, genre="Classical",
        palette=("#f1faee", "#bfc0c0", "#22223b"), motif="lines",
        tracks=[
            ("Prelude in Morning Light", 186), ("Rain on the Conservatory", 243), ("Nocturne for an Empty Room", 298),
            ("Etude in Blue", 167), ("The Slow Walk Home", 276), ("Candle Waltz", 221), ("Quiet Hours", 334),
        ],
    ),
]

# Episodes: (title, duration in seconds, description). The newest episode comes first and gets the newest date;
# `every_days` spaces the release dates back from the build date.
PODCASTS = [
    dict(
        slug="earloft-mystery", title="EarLoft - the mystery app", author="Nobody Knows Productions",
        category="Technology", every_days=7, palette=("#ffd23f", "#ff6a3d", "#1b1b2f"), motif="question",
        description="Nobody asked for another audio app, so we made one anyway. Every week we investigate a new "
                    "mystery of EarLoft: why it exists, what the watch does all night, and whether it is gapless yet.",
        episodes=[
            ("Is it gapless yet?", 1924, "The question that has haunted us since the first commit. Spoiler: yes."),
            ("What does the watch do all night?", 2311, "We followed a smartwatch through a full night of downloads "
                                                        "and found out why it waits for Wi-Fi."),
            ("The case of the missing cover", 1688, "An album without artwork, a cache without mercy, and the "
                                                    "detective work it took to bring the picture back."),
            ("Three iterations, then ask", 2045, "A conversation about knowing when to stop debugging and "
                                                 "when to ask for help."),
            ("Why another audio app?", 2756, "Music server, podcasts, audiobooks, all in one place. We explain "
                                             "the idea, and then try to explain it again."),
            ("Episode zero: hello, world", 1243, "The pilot. Mostly us testing the microphone."),
        ],
    ),
    dict(
        slug="curious-kitchen", title="The Curious Kitchen", author="Mara Ellis",
        category="Arts", every_days=4, palette=("#e76f51", "#f4a261", "#2a2a2a"), motif="bowl",
        description="Food science for home cooks. Mara Ellis asks chefs, chemists and grandmothers why recipes "
                    "work and what happens when they don't.",
        episodes=[
            ("Why bread rises (and sometimes doesn't)", 2468, "Yeast, gluten and patience with a baker who has "
                                                              "been up since four."),
            ("The secret life of salt", 2134, "From sea to shaker: why a pinch changes everything."),
            ("Fermentation for beginners", 2876, "Sauerkraut, kimchi and a jar that nearly exploded."),
            ("Perfect rice, every time", 1795, "Three methods, one rice cooker and a heated debate."),
            ("Chocolate tempering explained", 2302, "Crystals, temperatures and the snap of a good bar."),
            ("Soup weather", 1987, "Comfort food and the physics of a slow simmer."),
            ("Knife skills with Chef Bruno", 2655, "Why a sharp knife is the safest knife."),
        ],
    ),
    dict(
        slug="orbit-weekly", title="Orbit Weekly", author="Starlight Audio",
        category="Science", every_days=7, palette=("#4cc9f0", "#4361ee", "#03071e"), motif="orbit",
        description="Space news without the jargon. Every week: launches, landings, telescopes and the "
                    "questions you were afraid to ask.",
        episodes=[
            ("A telescope the size of a city", 2215, "How astronomers combine dishes around the world into one "
                                                     "giant eye."),
            ("Living on the Moon: the plumbing", 2541, "Water, air and the unglamorous engineering of lunar bases."),
            ("Comets are dirty snowballs", 1876, "What a comet is made of and why it grows a tail."),
            ("How to name a star", 1632, "Catalogues, numbers and why you can't really buy one."),
            ("The quietest place in the universe", 2398, "Voids between galaxies and what lives there: nothing."),
            ("Rockets 101", 2764, "Thrust, stages and why launches get scrubbed."),
        ],
    ),
    dict(
        slug="small-talk-big-ideas", title="Small Talk, Big Ideas", author="Jonah Reyes",
        category="Business", every_days=10, palette=("#9d4edd", "#ff9e00", "#240046"), motif="bubbles",
        description="Jonah Reyes talks to makers, founders and tinkerers about the small ideas that grew "
                    "into something big.",
        episodes=[
            ("The bike repair café", 2987, "A neighbourhood workshop that fixes bikes and friendships."),
            ("From hobby to bakery", 3215, "How a weekend sourdough habit became a shop with a queue."),
            ("Open source, open doors", 2843, "A maintainer on burnout, kindness and saying no."),
            ("The board game designer", 2678, "Prototypes made of cardboard and a lot of playtesting."),
            ("Repairing old radios", 2456, "Valves, solder and the sound of the past."),
        ],
    ),
    dict(
        slug="trail-notes", title="Trail Notes", author="Hannah & Theo",
        category="Leisure", every_days=14, palette=("#95d5b2", "#2d6a4f", "#081c15"), motif="peaks",
        description="Two friends, one tent and many wrong turns. Stories from hiking trails, huts and "
                    "the occasional rainy campsite.",
        episodes=[
            ("Above the clouds", 2187, "An early start, a steep ridge and a sunrise worth every step."),
            ("The hut with the best pancakes", 1954, "We rank mountain huts by breakfast. Scientifically."),
            ("Packing light (we failed)", 2311, "Twelve kilograms of things we did not need."),
            ("Lost and found", 2076, "A missing trail marker and a very helpful goat."),
            ("Winter hiking basics", 2534, "Layers, snowshoes and hot tea in a flask."),
            ("Our first trail", 1689, "Where it all started: blisters and a beautiful lake."),
        ],
    ),
]

# Books: `layout` "m4b" = one file with chapter marks, "mp3" = one file per chapter in the book's folder.
# The folder follows the Audiobookshelf layout the app reads: Audiobooks/Author/[Series/N – ]Title {Narrator}/.
BOOKS = [
    dict(
        author="A. Patient Developer", title="How to build the EarLoft app", narrator="The Emulator", year=2026,
        layout="m4b", genre="Technology", palette=("#ffd23f", "#ff6a3d", "#14213d"), motif="phone",
        description="A practical guide in twelve iterations, each one a little more gapless than the last. "
                    "Learn how to sync four kinds of servers, survive process death, teach a watch to wait for "
                    "Wi-Fi and write every log message in English. Includes a chapter on knowing when to stop.",
        chapters=[
            ("Introduction: nobody asked for this", 1420), ("Choosing a name (three times)", 1985),
            ("The first song plays", 2210), ("Servers, servers, servers", 2640), ("Podcasts and their feeds", 2380),
            ("Audiobooks remember where you were", 2515), ("The watch learns to wait", 2790),
            ("Process death and other surprises", 2155), ("Gapless at last", 1860), ("The review", 2470),
            ("Store screenshots without cats", 1530), ("Epilogue: one more feature", 1210),
        ],
    ),
    dict(
        author="Eleanor Whitby", title="The Lighthouse Keeper's Daughter", narrator="Grace Holloway", year=2021,
        layout="m4b", genre="Fiction", palette=("#ffb4a2", "#6d6875", "#14213d"), motif="lighthouse",
        description="On a windswept island at the edge of the map, seventeen-year-old Wren keeps the light "
                    "burning after her father disappears. When a stranger washes ashore with a letter in his "
                    "pocket, she begins to unravel a secret the island has kept for fifty years.",
        chapters=[(f"Chapter {i}", d) for i, d in enumerate(
            [1880, 2140, 1765, 2310, 1995, 2450, 2075, 1840, 2260, 2390, 1930, 2185, 2520, 1710, 2605], start=1)],
    ),
    dict(
        author="R. K. Thorne", title="Ashes of the Ninth Moon", series="The Ember Cycle", sequence="1",
        narrator="Marcus Bell", year=2022, layout="mp3", genre="Fantasy",
        palette=("#ff7b00", "#9d0208", "#03071e"), motif="moon",
        description="The ninth moon has fallen, and with it the old magic. Kael, a forge apprentice with "
                    "burned hands and a stubborn heart, sets out across the Ashlands to find the last keeper "
                    "of the flame. The first book of The Ember Cycle.",
        chapters=[(t, d) for t, d in [
            ("Prologue: The Falling", 1245), ("The Forge at Hollowmere", 2380), ("Ash and Iron", 2155),
            ("The Road North", 2610), ("A Keeper's Map", 1985), ("The Glass Desert", 2725), ("Embers", 2290),
            ("The Silent Watch", 2465), ("Fire Beneath the Snow", 2840), ("The Ninth Moon", 2530),
            ("Epilogue: Sparks", 1180),
        ]],
    ),
    dict(
        author="R. K. Thorne", title="The Silent Forge", series="The Ember Cycle", sequence="2",
        narrator="Marcus Bell", year=2024, layout="mp3", genre="Fantasy",
        palette=("#ffd000", "#e85d04", "#1b0a00"), motif="anvil",
        description="The flame is found, but the forge has gone quiet. Kael and his companions must cross the "
                    "frozen sea to wake an ancient smith who does not wish to be woken. The second book of "
                    "The Ember Cycle.",
        chapters=[(t, d) for t, d in [
            ("Prologue: Cold Iron", 1310), ("The Frozen Sea", 2495), ("Harbour of Bells", 2210),
            ("The Smith Who Sleeps", 2670), ("Hammer and Oath", 2385), ("Under the Ice", 2815),
            ("The Broken Anvil", 2140), ("A Song of Sparks", 2560), ("The Forge Awakens", 2935),
            ("Epilogue: Heat", 1245),
        ]],
    ),
    dict(
        author="Clara Penwick", title="Death at Willowmere", narrator="Oliver Grant", year=2023,
        layout="mp3", genre="Mystery", palette=("#b7e4c7", "#40916c", "#1b2a24"), motif="willow",
        description="When the vicar is found in the rose garden of Willowmere Hall, retired schoolteacher "
                    "Margaret Hale suspects that the village fête was not the only thing planned to the minute. "
                    "A cosy mystery with tea, gossip and one very observant cat.",
        chapters=[(f"Chapter {i}", d) for i, d in enumerate(
            [1640, 1875, 2030, 1790, 2215, 1960, 2105, 1850, 2290, 1720, 2380, 1995], start=1)],
    ),
]

# Synchronized lyrics (LRC) embedded in the track of the same title. Written for the demo library.
LYRICS = {
    "Gapless (Finally)": """\
[00:09.00] I waited for the silence in between
[00:16.50] A tiny gap where nothing should have been
[00:24.00] Track one fades out, track two is late
[00:31.50] Half a second felt like eight
[00:39.00] Now it's gapless, finally
[00:45.00] Every song flows into me
[00:51.00] No more breaks, no more pause
[00:57.00] Just the music, just because
[01:05.00] I tried the buffers, tried the queue
[01:12.50] I read the logs the whole night through
[01:20.00] Three iterations, then I asked
[01:27.50] And now the silence is the past
[01:35.00] Now it's gapless, finally
[01:41.00] Every song flows into me
[01:47.00] No more breaks, no more pause
[01:53.00] Just the music, just because
[02:03.00] Waiting for Wi-Fi, waiting for you
[02:10.50] Syncing my heart like servers do
[02:18.00] Offline again, but I don't mind
[02:25.50] The downloads left the gaps behind
[02:35.00] Now it's gapless, finally
[02:41.00] Every song flows into me
[02:47.00] No more breaks, no more pause
[02:53.00] Just the music, just because
[03:05.00] Gapless... finally
""",
}
