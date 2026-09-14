package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.Biome
import com.rork.hollowmarch.world.ChronicleEvent
import com.rork.hollowmarch.world.Power
import com.rork.hollowmarch.world.Rumor
import com.rork.hollowmarch.world.Site
import com.rork.hollowmarch.world.SiteKind
import com.rork.hollowmarch.world.StructureKind
import com.rork.hollowmarch.world.World
import com.rork.hollowmarch.world.isSettlement
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class LogLine(val text: String, var age: Float = 0f)

/** How many resolved exchanges the verification ledger keeps. */
private const val TRAIL_LENGTH = 24

/** What the nearest watcher has made of you, for the HUD's eye. */
enum class DetectionState { NONE, HIDDEN, SEARCHING, SEEN }

/** What the USE hand did: stole a pocket, took a thing up, opened spoils, or used the way. */
enum class Interact { NONE, PICKED, PICKPOCKET, LOOT, PORTAL, TRAVELER }

/** Word of a deed still on the road: it lands at a settlement on a later day. */
private data class Word(val arrivalDay: Int, val siteId: Int, val deed: Deed)

/** One entry of the Bearings sheet: a place, its bearing, and the walk it asks. */
data class Bearing(
    val site: Site,
    val compass: String,
    val leagues: Float,
    val hours: Float,
    val visited: Boolean,
    val source: String,
    /** The living folk of a settlement, or -1 for places that keep none. */
    val folk: Int = -1,
    /** The stage the place stands at — a camp, a town, a capital. */
    val stage: String = ""
)

/** Rain bends the pace of a walk: how the speed folds, and what each stride costs. */
internal fun rainPacing(rain: Float): Pair<Float, Float> =
    if (rain <= 0.25f) 1f to 1f else (1f - 0.25f * rain) to (1f + rain)

/** What an hour of rest pays: thin under the rain, honest under cover. */
internal fun restRecovery(wet: Boolean): Pair<Int, Int> = if (wet) 4 to 5 else 6 to 7

/** The chronicler's word for a moon's phase. */
internal fun moonPhaseWord(illum: Float, waxing: Boolean): String {
    val shape = when {
        illum < 0.12f -> "new"
        illum < 0.45f -> "crescent"
        illum < 0.62f -> "half"
        illum < 0.9f -> "gibbous"
        else -> "full"
    }
    return when {
        shape == "new" || shape == "full" -> shape
        waxing -> "waxing $shape"
        else -> "waning $shape"
    }
}

/** How the road's news comes to you, graded by the regard of the ground you stand on. */
internal enum class TravelerTone { RICH, PLAIN, GRUDGING }

/** The tone travelers take, set by the regard of the place you stand in. */
internal fun travelerTone(regard: Int): TravelerTone = when {
    regard >= 45 -> TravelerTone.RICH
    regard <= -25 -> TravelerTone.GRUDGING
    else -> TravelerTone.PLAIN
}

/**
 * The simulation behind the viewport: movement, torchlight, tap-to-strike melee,
 * enemy behaviour, the passage of days and the province's memory of what you did.
 */
class GameEngine(val world: World, startSlot: SaveSlot?, creation: DelverCreation? = null) {

    var map: GameMap
        private set
    val camera: Camera

    /** The province itself, built once from the seed and kept for the session. */
    private val overlandLazy = lazy { OverlandGen.build(world) }
    val overland: GameMap get() = overlandLazy.value

    /** The floor you stand on: 0 is the surface, 1 and deeper are the buried floors. */
    var depth: Int = 0
        private set
    var outdoor: Boolean = false
        private set

    /** True while you walk the open province itself, between all places. */
    var onOverland: Boolean = false
        private set

    var maxVitality: Int = Derived.maxVitality(StatBlock.balanced())
        private set
    var maxFatigue: Int = Derived.maxFatigue(StatBlock.balanced())
        private set
    var maxMagicka: Int = Derived.maxMagicka(StatBlock.balanced())
        private set
    var vitality: Int = maxVitality
    var fatigue: Int = maxFatigue
    var magicka: Int = maxMagicka
    var torch: Float = 1f
    var kills: Int = 0
    var brass: Int = 0
    var minutes: Float = 0f
    var currentSiteId: Int
    var wardTimer: Float = 0f
    var dead: Boolean = false
    var crouched: Boolean = false
        private set

    val reputation: Reputation = Reputation.fromSave(world, startSlot?.reputation)

    /** The living folk of every settlement, turned year by year and kept in the save. */
    val settlements: SettlementLedger = SettlementLedger.fromEncoded(startSlot?.settlements, world)

    /** The years' turns as they pass: growth, hardship, and the graves you left. */
    val chronicle: MutableList<ChronicleEvent> = mutableListOf()
    val roster = ClassRoster(world)
    val styleRoster = StyleRoster(world)
    val geography = MaterialGeography(world)
    val stats: StatBlock
    val klass: ActorClass?
    val growth: Growth
    /** The delver's hand with each family of arm: a second ledger beside Melee. */
    val proficiencies: Proficiencies
    /** The particular pieces this hand has come to know, by their smith's marks. */
    val masteries: Masteries
    val inventory: Inventory
    val equipment: Equipment
    val groundItems: MutableList<GroundItem> = mutableListOf()
    val deeds: MutableList<String> = mutableListOf()
    val visitedSites = mutableSetOf<Int>()

    /** The day you last stood in each place: the bearings sheet's "how long since". */
    private val visitedOn = mutableMapOf<Int, Int>()
    val log: MutableList<LogLine> = mutableListOf()
    /** The last blows resolved, attacker's arithmetic to armor's verdict: for verification, not display. */
    val combatTrail = ArrayDeque<CombatResolution>()
    val rumors: MutableList<Rumor> = mutableListOf()

    var moveInput: Float = 0f
    var strafeInput: Float = 0f
    var turnInput: Float = 0f
    var lookInput: Float = 0f

    var hurtFlash: Float = 0f
        private set
    var strikeArc: Float = 1f
        private set
    var swingPhase: Float = 0f
        private set
    private var swingTimer: Float = 0f
    var strikeCooldown: Float = 0f
    private var footstep: Float = 0f
    private val rng = Random(world.seed * 7919 + 17)

    /** The world's own heavens, rolled once from the seed. */
    val sky: Sky by lazy { SkyGen.build(world) }
    /** Animation clock in seconds: twinkling stars, drifting weather. */
    var animTime: Float = 0f
        private set

    // Declared before init: the first wake records a delve, and these must exist.
    private var lootedBurials = 0
    private val delvedSites = mutableSetOf<Int>()
    private val pendingWord = mutableListOf<Word>()
    private val lootedContainers = mutableSetOf<String>()
    private var ledgerYear: Int = 0

    /** The yard you left when you stepped through a door: restored when you step out. */
    private var surfaceMap: GameMap? = null

    /** The building you stand in, when you stand in one. */
    var inBuilding: BuildingFootprint? = null
        private set

    init {
        // Word you gathered rides the save; word from the forge rises again from the seed.
        rumors += startSlot?.rumors?.takeIf { it.isNotBlank() }
            ?.split('\u001E')?.mapNotNull { decodeRumor(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: world.rumors

        stats = StatBlock.fromEncoded(startSlot?.stats)
            ?: creation?.stats?.takeIf { it.isNotEmpty() }?.let { StatBlock(it) }
            ?: StatBlock.balanced()
        klass = roster.byKey(creation?.classKey?.takeIf { it.isNotBlank() } ?: startSlot?.classKey)
        growth = Growth.fromEncoded(startSlot?.growth, klass)
        // The smith's ledger continues where the save left it, before any piece is made.
        ItemUids.restore(startSlot?.nextUid ?: 1)
        proficiencies = Proficiencies.fromEncoded(startSlot?.proficiencies, klass)
        masteries = Masteries.fromEncoded(startSlot?.masteries)
        recomputePools()
        // A save from before items wakes with the starter kit; an itemed save keeps its satchel.
        inventory = startSlot?.inventory?.takeIf { it.isNotBlank() }
            ?.let { Inventory.fromEncoded(it) }
            ?: Inventory.starterKit()
        equipment = Equipment.fromEncoded(startSlot?.equipment)
        // An outfit that never rode the save (old expeditions, fresh wakes) takes up
        // its best blade once, then dresses as it pleases.
        if (equipment.isEmpty()) {
            inventory.all.filter { it.archetype.isWeapon }.maxByOrNull { it.damage() }?.let { weapon ->
                inventory.remove(weapon)
                equipment.equip(weapon)
                if (startSlot != null) {
                    pushLog("You take up the ${styleRoster.nameFor(weapon)} once more.")
                }
            }
        }

        val startSite = world.site(startSlot?.siteId?.takeIf { it >= 0 } ?: world.vaultSiteId)
        currentSiteId = startSite.id
        onOverland = startSlot?.onOverland ?: true
        outdoor = if (onOverland) true else (startSlot?.outdoor ?: true)
        minutes = startSlot?.minutes ?: (40 * 1440f + 8 * 60f)
        // An old save's depth was a dungeon level; it now names the floor you stood on.
        val savedDepth = startSlot?.depth ?: 1
        depth = if (onOverland || outdoor) {
            0
        } else if (savedDepth >= SiteGen.BUILDING_FLOOR_BASE) {
            savedDepth
        } else {
            savedDepth.coerceIn(1, SiteGen.floorCount(world, startSite).coerceAtLeast(1))
        }
        map = if (onOverland) {
            overland
        } else if (depth >= SiteGen.BUILDING_FLOOR_BASE) {
            // wake where you lay: the yard outside, then the room you rented
            val yard = SiteGen.map(
                world, startSite, 0, roster, geography, day, slainBeastIds(),
                folk = folkOf(startSite.id)
            )
            surfaceMap = yard
            val room = yard.buildings.getOrNull(depth - SiteGen.BUILDING_FLOOR_BASE)
            if (room != null) {
                inBuilding = room
                SiteGen.buildingInterior(
                    world, startSite, depth - SiteGen.BUILDING_FLOOR_BASE, room,
                    SiteGen.cultureId(world, startSite), day, geography
                )
            } else {
                depth = 0
                outdoor = true
                yard
            }
        } else {
            SiteGen.map(
                world, startSite, depth, roster, geography, day, slainBeastIds(),
                folk = folkOf(startSite.id)
            )
        }
        camera = Camera(map.spawnX, map.spawnY, map.spawnAngle)

        // A fresh expedition wakes at the bottom of the sealed vault, deep in the
        // dark; every site after this one is entered by its door, at the entrance.
        if (startSlot == null) wakeDeepIn(startSite)

        startSlot?.let { slot ->
            visitedSites += slot.visited.split('\u001F').mapNotNull { it.toIntOrNull() }
            visitedSites += startSite.id
            camera.x = slot.x.coerceIn(1.2f, map.width - 1.2f)
            camera.y = slot.y.coerceIn(1.2f, map.height - 1.2f)
            camera.angle = slot.angle
            vitality = slot.vitality.coerceIn(1, maxVitality)
            fatigue = slot.fatigue.coerceIn(0, maxFatigue)
            magicka = slot.magicka.coerceIn(0, maxMagicka)
            torch = slot.torch.coerceIn(0f, 1f)
            kills = slot.kills
            brass = slot.brass
            deeds += slot.deeds
            groundItems += slot.dropped.split('\u001E').mapNotNull { GroundItem.fromEncoded(it) }
                .map {
                    it.copy(
                        x = it.x.coerceIn(1.2f, map.width - 1.2f),
                        y = it.y.coerceIn(1.2f, map.height - 1.2f)
                    )
                }
            lootedContainers += slot.looted.split('\u001E').filter { it.isNotBlank() }
            if (map.isWall(camera.x, camera.y)) {
                camera.x = map.spawnX
                camera.y = map.spawnY
            }
        }
        // Containers remember being emptied, but only once the save has spoken.
        applyLootedState()
        removeSlainResidents()
        ledgerYear = settlements.currentYear

        if (startSlot == null) {
            pushLog("You wake at the bottom of ${siteName()}, deep in the dark.")
            pushLog("The brass nails that sealed this place lie far above. Only the climb out remains.")
            deeds += "Stood within ${siteName()}"
            recordDelving(startSite)
            SiteGen.bossFor(world, startSite).line?.let { pushLog(it) }
        } else if (onOverland) {
            pushLog("You take up the road again under the open sky.")
        } else {
            pushLog("You take up the torch again in ${map.title}.")
        }
    }

    /** A fresh expedition's waking: the lowest floor of the sealed vault, deep in the dark. */
    private fun wakeDeepIn(site: Site) {
        val floors = SiteGen.floorCount(world, site).coerceAtLeast(1)
        currentSiteId = site.id
        onOverland = false
        inBuilding = null
        depth = floors
        outdoor = false
        surfaceMap = null
        map = SiteGen.map(
            world, site, floors, roster, geography, day, slainBeastIds(),
            folk = folkOf(site.id)
        )
        applyLootedState()
        camera.x = map.spawnX
        camera.y = map.spawnY
        camera.angle = map.spawnAngle
        torch = (torch + 0.25f).coerceAtMost(1f)
        visitedSites += site.id
        visitedOn[site.id] = day
    }

    fun siteName(): String = world.sites.firstOrNull { it.id == currentSiteId }?.name ?: world.site(world.vaultSiteId).name

    /** The culture whose hands made this place's dead: the holder's people, or none. */
    private fun siteCultureId(siteId: Int): Int {
        val site = world.site(siteId)
        return world.power(site.holderPowerId)?.cultureId ?: -1
    }

    /** Pools follow the attributes and the skills beneath them; called again when points are spent. */
    private fun recomputePools() {
        maxVitality = Derived.maxVitality(stats, growth)
        maxFatigue = Derived.maxFatigue(stats, growth)
        maxMagicka = Derived.maxMagicka(stats, growth)
    }

    /** Deeds feed the skill they exercise; a filled bar raises the skill, and the skill hardens its attribute. */
    private fun learnSkill(skill: Skill, amount: Float) {
        val before = growth.level
        val rise = growth.feed(skill, stats, amount) ?: return
        recomputePools()
        pushLog("Your ${skill.label.lowercase()} improves — ${skill.label} ${growth.value(skill)}.")
        rise.attrRise?.let { attr -> pushLog(growthLine(attr)) }
        if (growth.level > before) {
            pushLog("You have grown — level ${growth.level}.")
        }
    }

    private fun growthLine(attr: Attr): String = when (attr) {
        Attr.MIGHT -> "Your blows land heavier. Might rises."
        Attr.VIGOR -> "The dark has made you harder to kill. Vigor rises."
        Attr.FINESSE -> "Your hands know the work. Finesse rises."
        Attr.SWIFTNESS -> "The road has quickened you. Swiftness rises."
        Attr.WISDOM -> "You see more in the same torchlight. Wisdom rises."
        Attr.INTELLECT -> "The old shapes come easier now. Intellect rises."
        Attr.PRESENCE -> "Doors open a little sooner for you. Presence rises."
        Attr.FORTUNE -> "Fate has begun to notice you. Fortune rises."
    }

    val day: Int get() = (minutes / 1440f).toInt() + 1

    /** The chronicle year you walk in: the world's present year, rolled forward by the days. */
    val gameYear: Int get() = world.currentYear + (day - 1) / 365
    val hour: Int get() = ((minutes % 1440f) / 60f).toInt()
    val minute: Int get() = (minutes % 60f).toInt()

    /** Fraction of the day passed, 0..1; 0.5 is noon. */
    val timeOfDay: Float get() = (minutes % 1440f) / 1440f

    /** What the sky is doing right now: fronts rolled from the seed and the calendar. */
    val weather: WeatherState
        get() {
            val inMarsh = onOverland && world.terrain.biomeAt(
                camera.x / OverlandGen.SIZE, camera.y / OverlandGen.SIZE
            ) == Biome.MARSH
            return Weather.at(world.seed, day, timeOfDay, inMarsh)
        }

    /** The light under the open sky once cloud has taken its share. */
    val skyLight: Float get() = outdoorLight * (1f - 0.30f * weather.cloud)

    /** Daylight from 0 (deep night) to 1 (noon); the wilds are lit by this alone. */
    val outdoorLight: Float
        get() {
            val t = (minutes % 1440f) / 1440f
            val curve = sin((t - 0.22f) * Math.PI.toFloat() * 2f)
            return ((curve + 0.55f) / 1.35f).coerceIn(0.08f, 1f)
        }

    val timeOfDayLabel: String
        get() = when (hour) {
            in 5..7 -> "dawn"
            in 8..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..19 -> "dusk"
            in 20..22 -> "night"
            else -> "small hours"
        }

    val heading: String
        get() {
            val deg = ((Math.toDegrees(camera.angle.toDouble()) + 450) % 360).toFloat()
            return when {
                deg < 22.5 || deg >= 337.5 -> "N"
                deg < 67.5 -> "NE"
                deg < 112.5 -> "E"
                deg < 157.5 -> "SE"
                deg < 202.5 -> "S"
                deg < 247.5 -> "SW"
                deg < 292.5 -> "W"
                else -> "NW"
            }
        }

    fun nearestTown(): Site =
        world.sites.filter { it.kind == SiteKind.TOWN }.minByOrNull { it.id } ?: world.sites.first()

    // ------------------------------------------------------------------ loop

    fun update(dt: Float) {
        if (dead) return
        val step = dt.coerceAtMost(0.05f)
        animTime += step
        // Twenty real minutes of daylight, fifteen of night — the same sun everywhere.
        minutes += clockStep(timeOfDay, step)
        strikeCooldown = (strikeCooldown - step).coerceAtLeast(0f)
        strikeArc = (strikeArc + step * 3.4f).coerceAtMost(1f)
        hurtFlash = (hurtFlash - step * 1.6f).coerceAtLeast(0f)
        wardTimer = (wardTimer - step).coerceAtLeast(0f)
        if (swingTimer > 0f) {
            swingTimer -= step
            swingPhase = (sin((1f - swingTimer / 0.28f) * Math.PI.toFloat())).coerceIn(0f, 1f)
        } else {
            swingPhase = 0f
        }

        updateMovement(step)
        // The wild keeps its own counsel: rolled by the league walked, from the seed.
        if (onOverland && walkedCells >= 20f) {
            walkedCells = 0f
            rollEncounter()
        }
        if (onOverland) {
            // the chronicler notes what the sky is doing, once a front has settled
            val w = weather
            if (timeOfDay in 0.25f..0.95f && w.kind != lastWeatherKind) {
                lastWeatherKind = w.kind
                pushLog(weatherLine(w.kind))
            }
            // the open country keeps the old clocks honest
            if (hour in 20..23 && nightWarnDay != day) {
                nightWarnDay = day
                pushLog("Night takes the open country. The road is no friend after dark.")
            }
            // the sky keeps its own annals, and you may be there when it writes
            if (day != skyEventDay && outdoorLight < 0.2f) {
                val cloud = weather.cloud
                if (SkyEvents.showerNight(world.seed, day) && cloud < 0.55f) {
                    skyEventDay = day
                    pushLog("The sky rains falling stars. You stand and watch until the cold drives you on.")
                } else if (auroraStrength() > 0.15f) {
                    skyEventDay = day
                    pushLog("Green light shivers over the northern sky, crowned in red.")
                }
            }
            // first sight of a landmark gets its own line, once
            map.portals.forEach { portal ->
                val siteId = portal.targetSiteId
                if (siteId >= 0 && siteId !in sighted &&
                    MapFactory.distance(camera.x, camera.y, portal.x, portal.y) < 26f
                ) {
                    sighted += siteId
                    pushLog("You catch sight of ${world.site(siteId).name} — ${portal.label}.")
                }
            }
        }
        // The eye sinks when you crouch, and rises again when you stand.
        val targetEye = if (crouched) 0.30f else 0.5f
        camera.eye += (targetEye - camera.eye) * (step * 7f).coerceIn(0f, 1f)
        updateTorch(step)
        // the chronicler's warnings: a torch guttering low, feet giving out
        if (holdsTorch && torch < 0.15f && torchWarnDay != day) {
            torchWarnDay = day
            pushLog("Your torch gutters low. It will not last the hour.")
        }
        if (fatigue <= 0 && fatigueWarnDay != day) {
            fatigueWarnDay = day
            pushLog("You are dead on your feet. Rest, or the road will have you.")
        }
        updateEntities(step)
        updateResidents(step)
        updatePatrols(step)
        if (pendingWord.isNotEmpty()) deliverWord()
        if (gameYear != ledgerYear) stepFolkYears()
        log.forEach { it.age += step }
        if (log.size > 12) log.subList(0, log.size - 12).clear()
        val seenBefore = map.exploredFraction()
        map.markExplored(camera.x.toInt(), camera.y.toInt())
        val seenNow = map.exploredFraction()
        if (seenNow > seenBefore) {
            learnSkill(Skill.NAVIGATION, (seenNow - seenBefore) * 140f)
            learnSkill(Skill.AWARENESS, (seenNow - seenBefore) * 60f)
        }

        if (vitality <= 0) {
            vitality = 0
            dead = true
            pushLog("Your legs fold. The dark closes politely over you.")
        }
    }

    private fun updateMovement(dt: Float) {
        val tired = fatigue <= 0
        val burden = Derived.encumbranceFactor(inventory.weight(), Derived.carryCapacity(stats, growth))
        val ground = terrainPacing()
        val sky = weatherPacing()
        val speed = (if (tired) 1.4f else 2.5f) * (if (outdoor) 1.15f else 1f) *
            Derived.moveSpeed(stats, growth) * burden * (if (crouched) 0.5f else 1f) *
            (ground?.first ?: 1f) * (sky?.first ?: 1f)
        camera.angle += turnInput * dt * 2.3f
        // Vertical look: the drag holds a pitch the renderer shears into the horizon.
        camera.pitch = (camera.pitch + lookInput * dt * 2.0f).coerceIn(-1.05f, 1.05f)

        val forward = moveInput * speed * dt
        val strafe = strafeInput * speed * 0.7f * dt
        if (abs(forward) > 0.0001f || abs(strafe) > 0.0001f) {
            val dx = camera.dirX * forward - camera.dirY * strafe
            val dy = camera.dirY * forward + camera.dirX * strafe
            val (nx, ny) = MapFactory.tryMove(map, camera.x, camera.y, dx, dy)
            camera.x = nx
            camera.y = ny
            footstep += dt * 9f
            camera.bob = sin(footstep) * 2.4f
            if (onOverland) walkedCells += sqrt(forward * forward + strafe * strafe)
            drainFatigue(dt * 0.7f * (ground?.second ?: 1f) * (sky?.second ?: 1f))
            if (burden < 1f) drainFatigue(dt * (1f - burden) * 3f)
            learnSkill(Skill.ATHLETICS, dt * 2f)
            if (tired) learnSkill(Skill.ENDURANCE, dt * 3f)
        } else {
            camera.bob *= 0.85f
            recoverFatigue(dt * 0.5f)
        }
    }

    /** What the ground asks of you: how fast you go, and how much it costs. */
    private fun terrainPacing(): Pair<Float, Float>? {
        if (!onOverland) return null
        val ford = map.floorAt(camera.x.toInt(), camera.y.toInt()) == Textures.FLOOR_FORD
        val biome = world.terrain.biomeAt(camera.x / map.width, camera.y / map.height)
        return OverlandGen.pacing(biome, ford)
    }

    /** The chronicler's name for the ground under your feet on the open road. */
    fun terrainLabel(): String {
        if (!onOverland) return ""
        val ford = map.floorAt(camera.x.toInt(), camera.y.toInt()) == Textures.FLOOR_FORD
        if (ford) return "the ford"
        return OverlandGen.biomeLabel(world.terrain.biomeAt(camera.x / map.width, camera.y / map.height))
    }

    /** What the sky asks of the walk: nothing under cover, rain's tax in the open. */
    fun weatherPacing(): Pair<Float, Float>? = if (!outdoor) null else rainPacing(weather.rain)

    /** The chronicler's note on what the sky will do to today's walk, for the bearings sheet. */
    fun weatherWalkNote(): String =
        if (outdoor && weather.rain > 0.25f) "Rain slows the road; the hours below ask more than the leagues admit."
        else ""

    /** The sky overhead in words: a weather word and the moon that is up, if any. */
    fun skyWord(): String {
        if (!outdoor) return ""
        val parts = mutableListOf(weather.kind.name.lowercase())
        moonWord()?.let { parts += it }
        return parts.joinToString(" \u00B7 ")
    }

    /** The first moon that is up, named as the chroniclers name it: "waxing bone moon". */
    fun moonWord(): String? {
        sky.moons.forEach { moon ->
            val mt = (timeOfDay + moon.offset) % 1f
            val el = sin(2f * Math.PI.toFloat() * (mt - 0.25f)) * 0.9f
            if (el < 0.05f) return@forEach
            val phase = 2f * Math.PI.toFloat() * (day + timeOfDay) / moon.periodDays + moon.phase0
            val illum = 0.5f + 0.5f * sin(phase)
            return "${moonPhaseWord(illum, cos(phase) > 0f)} ${moon.tintName} moon"
        }
        return null
    }

    private var fatigueFraction = 0f

    private fun drainFatigue(amount: Float) {
        fatigueFraction += amount
        while (fatigueFraction >= 1f) {
            fatigueFraction -= 1f
            fatigue = (fatigue - 1).coerceAtLeast(0)
        }
    }

    private fun recoverFatigue(amount: Float) {
        fatigueFraction -= amount
        while (fatigueFraction <= -1f) {
            fatigueFraction += 1f
            fatigue = (fatigue + 1).coerceAtMost(maxFatigue)
        }
    }

    /** True while a torch is held in one of your hands; the satchel bundle stays dark. */
    val holdsTorch: Boolean
        get() = equipment.worn(Hand.RIGHT.slot)?.archetype == ItemArchetype.TORCH ||
            equipment.worn(Hand.LEFT.slot)?.archetype == ItemArchetype.TORCH

    /** The light you carry: only a torch in hand gives its circle against the dark. */
    val litTorch: Float get() = if (holdsTorch) torch else 0f

    private fun updateTorch(dt: Float) {
        // A torch only burns in your hand; the packed bundle does not spend itself.
        if (!holdsTorch) return
        if (outdoor) {
            torch = (torch - dt * 0.0015f * Derived.torchDrain(stats, growth)).coerceAtLeast(0f)
            return
        }
        torch = (torch - dt * 0.0042f * Derived.torchDrain(stats, growth)).coerceAtLeast(0f)
        if (torch < 0.18f && rng.nextFloat() < dt * 0.12f) {
            pushLog("The torch gutters; the walls step closer.")
        }
    }

    private fun updateEntities(dt: Float) {
        map.entities.forEach { entity ->
            entity.hurtFlash = (entity.hurtFlash - dt * 2.2f).coerceAtLeast(0f)
            if (entity.kind != EntityKind.ENEMY || !entity.alive) return@forEach
            if (entity.resident) return@forEach
            entity.attackCooldown = (entity.attackCooldown - dt).coerceAtLeast(0f)
            val dx = camera.x - entity.x
            val dy = camera.y - entity.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 12f) return@forEach
            updateDetection(entity, dist, dt)
            // The blind do not chase, and the searching walk soft.
            if (entity.detection == Detection.UNAWARE) return@forEach
            val pace = if (entity.detection == Detection.SEARCHING) entity.speed * 0.6f else entity.speed

            if (dist > 1.05f) {
                val nx = entity.x + dx / dist * pace * dt
                val ny = entity.y + dy / dist * pace * dt
                if (!map.isWall(nx, entity.y)) entity.x = nx
                if (!map.isWall(entity.x, ny)) entity.y = ny
                // The chase itself is a teacher.
                trainNpc(entity, Skill.ATHLETICS, dt * 2f)
            } else if (entity.detection == Detection.AWARE && entity.attackCooldown <= 0f) {
                entity.attackCooldown = 1.5f + rng.nextFloat()
                // Its hand asks the same questions yours does: Finesse, Melee, the family, the piece.
                val theirWeapon = entity.equipment?.bestWeapon()
                val theirCategory = theirWeapon?.let { WeaponCategory.forWeapon(it.archetype) }
                    ?: WeaponCategory.UNARMED
                val theirProficiency = entity.proficiencies.value(theirCategory)
                val theirMastery = theirWeapon?.let { entity.masteries.value(it.uid) } ?: 0
                val theirStats = entity.stats ?: StatBlock.balanced()
                val hitChance = Derived.meleeAccuracy(theirStats, entity.skills, theirProficiency, theirMastery)
                val hitRoll = rng.nextInt(100)
                // A missed swing still teaches hand and piece a little.
                trainNpc(entity, Skill.MELEE, 1f)
                trainNpcWeapon(entity, 0.5f, 0.25f)
                val worn = equipment.items()
                val bodyPiece = worn.firstOrNull { it.archetype.slot == ItemSlot.BODY }
                val dodgePenalty = Derived.armorDodgePenalty(worn)
                val dodgeChance = Derived.dodgeChance(stats, growth, worn)
                val dodgeRoll = rng.nextInt(100)
                if (hitRoll >= hitChance) {
                    pushLog("The ${entity.name}'s blow finds only air.")
                    recordCombat(
                        CombatResolution(
                            entity.name, "you", theirStats[Attr.FINESSE], entity.skills.value(Skill.MELEE),
                            theirWeapon?.let { styleRoster.nameFor(it) } ?: "bare hands", theirCategory,
                            theirProficiency, theirMastery, hitChance, hitRoll, StrikeOutcome.MISSED,
                            stats[Attr.SWIFTNESS],
                            bodyPiece?.archetype?.armorTemper?.name ?: "NONE",
                            bodyPiece?.material?.label ?: "NONE",
                            dodgePenalty, dodgeChance, dodgeRoll, 0, 0
                        )
                    )
                    return@forEach
                }
                if (dodgeRoll < dodgeChance) {
                    // You move before the edge arrives; what you wear has no say in a blow that lands on nothing.
                    pushLog("You slip the ${entity.name}'s blow aside.")
                    learnSkill(Skill.DEFENSE, 1.5f)
                    trainNpc(entity, Skill.MELEE, 1.5f)
                    recordCombat(
                        CombatResolution(
                            entity.name, "you", theirStats[Attr.FINESSE], entity.skills.value(Skill.MELEE),
                            theirWeapon?.let { styleRoster.nameFor(it) } ?: "bare hands", theirCategory,
                            theirProficiency, theirMastery, hitChance, hitRoll, StrikeOutcome.DODGED,
                            stats[Attr.SWIFTNESS],
                            bodyPiece?.archetype?.armorTemper?.name ?: "NONE",
                            bodyPiece?.material?.label ?: "NONE",
                            dodgePenalty, dodgeChance, dodgeRoll, 0, 0
                        )
                    )
                    return@forEach
                }
                val warded = wardTimer > 0f
                val raw = entity.damage + rng.nextInt(4)
                // What you wear turns the blow, its temper against theirs.
                val theirHarm = theirWeapon?.archetype?.damageType ?: DamageType.SHARP
                val soak = Derived.armorSoak(worn, theirHarm)
                var dealt = (raw - soak).coerceAtLeast(1)
                if (warded) dealt = (dealt * 0.45f).roundToInt().coerceAtLeast(1)
                vitality -= dealt
                learnSkill(Skill.DEFENSE, dealt * 0.9f)
                trainNpc(entity, Skill.MELEE, 3f)
                trainNpcWeapon(entity, 3f, 2f)
                hurtFlash = 0.9f
                pushLog(
                    if (warded) "The ward takes most of the ${entity.name}'s blow. $dealt lost."
                    else "The ${entity.name} lands a blow. $dealt lost."
                )
                recordCombat(
                    CombatResolution(
                        entity.name, "you", theirStats[Attr.FINESSE], entity.skills.value(Skill.MELEE),
                        theirWeapon?.let { styleRoster.nameFor(it) } ?: "bare hands", theirCategory,
                        theirProficiency, theirMastery, hitChance, hitRoll, StrikeOutcome.HIT,
                        stats[Attr.SWIFTNESS],
                        bodyPiece?.archetype?.armorTemper?.name ?: "NONE",
                        bodyPiece?.material?.label ?: "NONE",
                        dodgePenalty, dodgeChance, dodgeRoll, dealt, soak
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private var strikeHand: Hand = Hand.RIGHT

    /** Tap anywhere: you swing at whatever stands in front of you. */
    fun strike() {
        if (dead || strikeCooldown > 0f) return
        // A weapon in each hand, the blows trade arms; one arm alone does all the work.
        val right = equipment.weaponIn(Hand.RIGHT)
        val left = if (equipment.leftClaimed) null else equipment.weaponIn(Hand.LEFT)
        val weapon = if (right != null && left != null) {
            if (strikeHand == Hand.RIGHT) right else left
        } else {
            right ?: left
        }
        strikeHand = if (strikeHand == Hand.RIGHT) Hand.LEFT else Hand.RIGHT
        val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
        val proficiency = proficiencies.value(category)
        val mastery = weapon?.let { masteries.value(it.uid) } ?: 0
        strikeCooldown = Derived.strikeCooldown(stats, growth, weapon, proficiency, mastery)
        strikeArc = 0f
        swingTimer = 0.28f
        drainFatigue(2f)

        val target = map.entities.filter { it.kind == EntityKind.ENEMY && it.alive }
            .filter { entity ->
                val dx = entity.x - camera.x
                val dy = entity.y - camera.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > 2.2f) return@filter false
                val dot = (dx / dist) * camera.dirX + (dy / dist) * camera.dirY
                dot > 0.55f
            }
            .minByOrNull { MapFactory.distance(it.x, it.y, camera.x, camera.y) }

        if (target == null) {
            if (rng.nextInt(3) == 0) pushLog("Your blade takes the wall. Sparks, then nothing.")
            return
        }

        if (fatigue <= 2 && rng.nextInt(2) == 0) {
            pushLog("Your arms are lead; the swing goes wide.")
            return
        }

        // The hand asks first: Finesse, Melee, the family, the piece. Even the
        // surest blade finds air; a swing that misses still teaches a little.
        val hitChance = Derived.meleeAccuracy(stats, growth, proficiency, mastery)
        val hitRoll = rng.nextInt(100)
        weaponPractice(weapon, 0.5f, 0.25f)
        val weaponName = weapon?.let { styleRoster.nameFor(it) } ?: "bare hands"
        val defenderStats = target.stats ?: StatBlock.balanced()
        val worn = target.equipment?.items() ?: emptyList()
        val bodyPiece = worn.firstOrNull { it.archetype.slot == ItemSlot.BODY }
        val dodgePenalty = Derived.armorDodgePenalty(worn)
        val dodgeChance = Derived.dodgeChance(defenderStats, target.skills, worn)
        val dodgeRoll = rng.nextInt(100)
        // A blow from where it isn't looking: the dark does half the work, a dagger more.
        val sneak = crouched && target.detection == Detection.UNAWARE
        if (hitRoll >= hitChance) {
            pushLog("Your swing finds only air.")
            recordCombat(
                CombatResolution(
                    "you", target.name, stats[Attr.FINESSE], growth.value(Skill.MELEE),
                    weaponName, category, proficiency, mastery, hitChance, hitRoll, StrikeOutcome.MISSED,
                    defenderStats[Attr.SWIFTNESS],
                    bodyPiece?.archetype?.armorTemper?.name ?: "NONE",
                    bodyPiece?.material?.label ?: "NONE",
                    dodgePenalty, dodgeChance, dodgeRoll, 0, 0
                )
            )
            return
        }
        if (!sneak && dodgeRoll < dodgeChance) {
            // The body moves before the edge arrives; armor has no say in a blow that lands on nothing.
            pushLog("The ${target.name} slips your blow aside.")
            trainNpc(target, Skill.DEFENSE, 2f)
            recordCombat(
                CombatResolution(
                    "you", target.name, stats[Attr.FINESSE], growth.value(Skill.MELEE),
                    weaponName, category, proficiency, mastery, hitChance, hitRoll, StrikeOutcome.DODGED,
                    defenderStats[Attr.SWIFTNESS],
                    bodyPiece?.archetype?.armorTemper?.name ?: "NONE",
                    bodyPiece?.material?.label ?: "NONE",
                    dodgePenalty, dodgeChance, dodgeRoll, 0, 0
                )
            )
            return
        }

        var damage = Derived.strikeDamage(stats, growth, weapon, rng, proficiency, mastery)
        val critical = rng.nextInt(100) < Derived.critChance(stats)
        if (critical) {
            damage = (damage * 1.5f).roundToInt() + 2
            learnSkill(Skill.MELEE, 3f)
        }
        if (sneak) {
            damage = (damage * Derived.sneakAttackMultiplier(weapon?.archetype)).roundToInt()
            learnSkill(Skill.STEALTH, 5f)
            target.detection = Detection.AWARE
        }
        // What the dead wear turns the blow, its temper against yours.
        val harm = weapon?.archetype?.damageType ?: DamageType.BLUNT
        val soak = Derived.armorSoak(target.equipment?.items() ?: emptyList(), harm)
        damage = (damage - soak).coerceAtLeast(1)
        recordCombat(
            CombatResolution(
                "you", target.name, stats[Attr.FINESSE], growth.value(Skill.MELEE),
                weaponName, category, proficiency, mastery, hitChance, hitRoll, StrikeOutcome.HIT,
                defenderStats[Attr.SWIFTNESS],
                bodyPiece?.archetype?.armorTemper?.name ?: "NONE",
                bodyPiece?.material?.label ?: "NONE",
                dodgePenalty, dodgeChance, dodgeRoll, damage, soak
            )
        )
        target.hp -= damage
        target.hurtFlash = 1f
        // The arm teaches its kind, and the piece teaches its keeper.
        weaponPractice(weapon, 2f)
        if (target.hp <= 0) {
            target.alive = false
            kills++
            brass += ((2 + rng.nextInt(6)) * Derived.lootFactor(stats)).roundToInt().coerceAtLeast(1)
            learnSkill(Skill.MELEE, 3f + target.level * 1.5f)
            weaponPractice(weapon, 3f, 2f)
            // Fortune is fate, not craft; no skill can train it.
            val lessons = if (target.risesThisLife > 0) {
                " It dies wiser by ${target.risesThisLife} lesson" +
                    (if (target.risesThisLife == 1) "." else "s.")
            } else ""
            pushLog(
                if (target.klass != null) {
                    "The ${target.name} — ${target.klass.name}, of level ${target.level} — comes apart and does not rise.$lessons"
                } else {
                    "The ${target.name} comes apart and does not rise.$lessons"
                }
            )
            recordKill(target)
        } else {
            if (target.resident) {
                world.sites.firstOrNull { it.id == currentSiteId }
                    ?.takeIf { it.isSettlement }
                    ?.let { site ->
                        reputation.adjust(Layer.SETTLEMENT, site.id, -3, "Struck ${target.name}")
                        alarmTheWatch(site)
                    }
            }
            pushLog(
                if (sneak) "Your blade goes in where it isn't looking. $damage."
                else strikeFlavour(target.name, damage)
            )
            if (critical) pushLog("A telling blow — Fortune was with you.")
            // Taking a blow teaches armor and grit.
            trainNpc(target, Skill.DEFENSE, 1.5f)
            trainNpc(target, Skill.ENDURANCE, 1f)
        }
    }

    /** The arm teaches its kind, and the piece teaches its keeper: practice for both ledgers. */
    private fun weaponPractice(weapon: Item?, profAmount: Float, masteryAmount: Float = profAmount) {
        val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
        proficiencies.feed(category, profAmount)?.let { new ->
            pushLog("Your ${category.label.lowercase()} grow surer — ${category.label} $new.")
        }
        weapon?.takeIf { it.uid > 0 }?.let { piece ->
            masteries.feed(piece.uid, masteryAmount)?.let { new ->
                pushLog("The ${styleRoster.nameFor(piece)} grows familiar in your hand — mastery $new.")
            }
        }
    }

    /** Keep one resolved exchange in the verification ledger: newest last, the ledger trimmed. */
    private fun recordCombat(resolution: CombatResolution) {
        combatTrail.addLast(resolution)
        while (combatTrail.size > TRAIL_LENGTH) combatTrail.removeFirst()
    }

    private fun strikeFlavour(name: String, damage: Int): String = when (rng.nextInt(4)) {
        0 -> "Your slash opens the $name's shoulder. $damage."
        1 -> "You chop into the $name; it keens. $damage."
        2 -> "The blade bites deep. The $name staggers. $damage."
        else -> "A clean cut across the $name's ribs. $damage."
    }

    /** The named souls: a slow stroll by day, home through their doors by night. */
    private fun updateResidents(dt: Float) {
        if (map.buildings.isEmpty()) return
        val night = hour >= 20 || hour < 6
        val gone = mutableListOf<Entity>()
        map.entities.forEach { entity ->
            if (!entity.resident || !entity.alive) return@forEach
            if (night && entity.homeBuilding >= 0) {
                val home = map.buildings.getOrNull(entity.homeBuilding) ?: return@forEach
                val dx = home.doorX + 0.5f - entity.x
                val dy = home.doorY + 0.5f - entity.y
                if (dx * dx + dy * dy < 5.3f) {
                    gone += entity
                    return@forEach
                }
                stepSoul(entity, home.doorX + 0.5f, home.doorY + 0.5f, dt, 0.7f)
            } else {
                if (entity.wanderPhase <= 0f) {
                    entity.wanderPhase = 4f + rng.nextFloat() * 6f
                    repeat(8) {
                        val ang = rng.nextFloat() * 6.28f
                        val r = 2f + rng.nextFloat() * 7f
                        val tx = map.width / 2f + cos(ang) * r
                        val ty = map.height / 2f + sin(ang) * r
                        if (!map.isWall(tx, ty)) {
                            entity.wanderX = tx
                            entity.wanderY = ty
                            return@repeat
                        }
                    }
                } else {
                    entity.wanderPhase -= dt
                }
                stepSoul(entity, entity.wanderX, entity.wanderY, dt, 0.45f)
            }
        }
        if (gone.isNotEmpty()) map.entities.removeAll(gone.toSet())
    }

    private fun stepSoul(entity: Entity, tx: Float, ty: Float, dt: Float, speed: Float) {
        val dx = tx - entity.x
        val dy = ty - entity.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 0.1f) return
        val nx = entity.x + dx / dist * speed * dt
        val ny = entity.y + dy / dist * speed * dt
        if (!map.isWall(nx, entity.y) && !map.doorwayAt(nx, entity.y)) entity.x = nx
        if (!map.isWall(entity.x, ny) && !map.doorwayAt(entity.x, ny)) entity.y = ny
    }

    /** The watch turns out for blood spilled among the folk; a warband needs no summons. */
    private fun alarmTheWatch(site: Site) {
        val warband = site.kind == SiteKind.CAMP
        repeat(1 + rng.nextInt(2) + site.garrison / 20) {
            val spot = openSpotNear() ?: return@repeat
            map.entities += spawnNpc(
                x = spot.first, y = spot.second,
                name = if (warband) "${site.name} warband" else "${site.name} watchman",
                spriteId = Sprites.HUSK,
                height = 1.0f, baseSpeed = 1.1f, level = 1 + depth + rng.nextInt(2),
                weights = OUTRIDER_WEIGHTS
            )
        }
        pushLog(
            if (warband) "The warband of ${site.name} comes running."
            else "The watch of ${site.name} comes running."
        )
    }

    /** A soul the world remembers slain does not stand here again. */
    private fun removeSlainResidents() {
        map.entities.removeAll { entity ->
            entity.resident && "slain:$currentSiteId:${entity.name}" in lootedContainers
        }
    }

    private fun recordKill(entity: Entity) {
        // A soul of a living place: the folk count drops, the watch is raised, the name is remembered.
        if (entity.resident) {
            val site = world.sites.firstOrNull { it.id == currentSiteId } ?: return
            settlements.recordDeath(site.id)
            lootedContainers += "slain:$currentSiteId:${entity.name}"
            reputation.adjust(Layer.SETTLEMENT, site.id, -8, "Slew ${entity.name} in the street")
            alarmTheWatch(site)
            addRumor(
                "\"${entity.name} of ${site.name} was slain in cold blood.\"",
                "the streets of ${site.name}",
                site.id
            )
            val deed = "Slew ${entity.name} at ${site.name}"
            if (!deeds.contains(deed)) deeds += deed
            pushLog("${entity.name} falls. The folk of ${site.name} will not forget this.")
            return
        }
        // A chronicle beast met in the open: the deed is the world's, and its lair stands empty.
        if (entity.beastId >= 0) {
            lootedContainers += "beast:${entity.beastId}"
            val beast = world.beast(entity.beastId)
            val lair = beast?.let { world.sites.firstOrNull { s -> s.id == it.lairSiteId } }
            val deed = "Slew ${entity.name}" +
                (lair?.let { " in the wilds near ${it.name}" } ?: " in the open country")
            if (!deeds.contains(deed)) deeds += deed
            lair?.let { s ->
                val holder = world.power(s.holderPowerId)
                if (holder != null && !holder.hostileByNature) {
                    reputation.adjust(Layer.POWER, holder.id, repGain(6), "Slew ${entity.name}, their terror")
                }
                reputation.logDeed(
                    Deed(day = day, domain = "battle", text = deed, siteId = s.id, powerId = holder?.id)
                        .also { spreadWord(it, s.id) }
                )
            }
            addRumor(
                "\"They say ${entity.name} is slain. The roads near ${lair?.name ?: "the wilds"} lie easier.\"",
                "the open road"
            )
            pushLog("${entity.name} falls, and the chronicle is one terror shorter.")
            return
        }
        if (entity.boss) {
            lootedContainers += "boss:$currentSiteId"
            val deed = "Slew ${entity.name} beneath ${map.title}"
            if (!deeds.contains(deed)) deeds += deed
            reputation.logDeed(
                Deed(day = day, domain = "battle", text = deed, siteId = currentSiteId)
            )
            addRumor(
                "\"They say ${entity.name} is slain. The ${map.title} stands empty of its terror.\"",
                "the brass road"
            )
            pushLog("${entity.name} falls, and does not rise. The ${map.title} is broken.")
        }
        // A settlement remembers every soul struck down on its ground.
        if (entity.klass != null) {
            world.sites.firstOrNull { it.id == currentSiteId }
                ?.takeIf { it.isSettlement }
                ?.let { recordSettlementDeath(it) }
        }
        // A fallen rider is worth more than a fallen husk, to everyone but its lord.
        world.powers.firstOrNull { entity.name.startsWith("${it.name} ") }?.let { patron ->
            reputation.adjust(Layer.POWER, patron.id, -2, "Their riders fell on the road")
            world.sites.firstOrNull { it.id == currentSiteId }?.let { site ->
                reputation.adjust(Layer.SETTLEMENT, site.id, 2, "Riders of ${patron.name} put down")
            }
        }
        val site = world.sites.firstOrNull { it.id == currentSiteId }
        val holder = site?.let { world.power(it.holderPowerId) }
        val underground = site?.kind in setOf(SiteKind.VAULT, SiteKind.RUIN, SiteKind.BARROW)

        // Cleansing the walking dead in a lord's lands earns thanks; the same
        // blade, swung where their claims reach, earns watchmen.
        if (holder != null && !holder.hostileByNature) {
            reputation.adjust(Layer.POWER, holder.id, repGain(3), "Cleansed the dead beneath ${site?.name}")
        }
        site?.let { s ->
            reputation.adjust(Layer.SETTLEMENT, s.id, repGain(4), "Put down the dead that walked near ${s.name}")
        }

        // Brass pried from the burials offends the grave-gods, one story at a time.
        if (underground) {
            lootedBurials++
            if (lootedBurials % 5 == 0) {
                world.deities.filter { it.domain == "graves" }.forEach { god ->
                    reputation.adjust(Layer.DEITY, god.id, -3, "Pried brass from the dead")
                }
                reputation.logDeed(
                    Deed(
                        day = day, domain = "loot", text = "Pried brass from the dead",
                        siteId = currentSiteId, powerId = holder?.id
                    )
                )
                pushLog("The dead were owed better. The grave-gods count.")
                learnSkill(Skill.LOCKPICKING, 4f)
            }
        }

        reputation.logDeed(
            Deed(
                day = day, domain = "battle",
                text = "Put down a ${entity.name} beneath ${map.title}",
                siteId = currentSiteId, powerId = holder?.id
            ).also { spreadWord(it, currentSiteId) }
        )

        if (kills % 3 == 0) {
            val deed = "Put down a ${entity.name} beneath ${map.title}"
            deeds += deed
            rumors.add(
                0,
                Rumor(
                    text = "\"Something is killing the ${entity.name}s under ${map.title}.\"",
                    source = "${world.sites.random(rng).name} road",
                    daysOld = 0,
                    aboutPlayer = true
                )
            )
        }
    }

    /** A commanded presence makes good deeds weigh more; ill repute needs no help. */
    private fun repGain(delta: Int): Int =
        if (delta > 0) (delta * Derived.rewardFactor(stats)).roundToInt() else delta

    /** Word of a deed travels the roads at a walker's pace and lands day by day. */
    private fun spreadWord(deed: Deed, originSiteId: Int?) {
        val origin = world.sites.firstOrNull { it.id == originSiteId } ?: return
        world.sites
            .filter { it.isSettlement && !it.ruined && it.id != origin.id }
            .forEach { site ->
                val leagues = MapFactory.distance(origin.x, origin.y, site.x, site.y) * 42f
                val days = (leagues / 2.1f / 24f).roundToInt().coerceIn(1, 8)
                pendingWord += Word(day + days, site.id, deed)
            }
        if (pendingWord.size > 80) {
            pendingWord.subList(0, pendingWord.size - 80).clear()
        }
    }

    /** Word arrives: the settlement's regard shifts by what the deed was worth. */
    private fun deliverWord() {
        val today = day
        val arrived = pendingWord.filter { it.arrivalDay <= today }
        if (arrived.isEmpty()) return
        pendingWord.removeAll(arrived.toSet())
        arrived.forEach { word ->
            val delta = when (word.deed.domain) {
                "battle" -> 2
                "offering" -> 3
                "delving" -> -4
                "loot" -> -3
                else -> 0
            }
            if (delta != 0) {
                reputation.adjust(Layer.SETTLEMENT, word.siteId, delta, "Word of it: ${word.deed.text}")
            }
        }
    }

    // ------------------------------------------------------- living settlements

    /** Turn the years: every settlement's folk rises and thins, and the chronicle remembers. */
    internal fun stepFolkYears() {
        val year = gameYear
        if (year <= ledgerYear) return
        settlements.stepYears(year, world.sites).forEach { turn ->
            chronicle += turn.events
            turn.roseTo?.let { rose ->
                pushLog("Word comes from ${turn.site.name}: it stands a ${rose.label} now.")
                rebuildLandmark(turn.site)
            }
        }
        ledgerYear = year
    }

    /** A place its folk raised to a new stage is redrawn on the open province, in place. */
    private fun rebuildLandmark(site: Site) {
        if (!overlandLazy.isInitialized()) return
        OverlandGen.redrawLandmark(overlandLazy.value, world, site, settlements.folkOf(site))
        // stood where the new walls rose? the gate takes you in
        if (onOverland && currentSiteId == site.id && map.isWall(camera.x, camera.y)) {
            overland.entrySpots[site.id]?.let { spot ->
                camera.x = spot.first
                camera.y = spot.second
            }
        }
    }

    /** A soul struck down on a settlement's ground is counted against its year. */
    internal fun recordSettlementDeath(site: Site) {
        if (!site.isSettlement) return
        settlements.recordDeath(site.id)
    }

    /** The living folk of a settlement, as the years have made them. */
    fun folkOf(siteId: Int): Int =
        world.sites.firstOrNull { it.id == siteId }?.let { settlements.folkOf(it) } ?: 0

    /** The stage a settlement stands at, from its founding rank and its living folk. */
    fun stageAt(site: Site): SettlementStage = settlements.stageAt(site)

    private fun addRumor(text: String, source: String, siteId: Int = -1, daysOld: Int = 0) {
        rumors.add(
            0,
            Rumor(text = text, source = source, daysOld = daysOld, aboutPlayer = true, siteId = siteId)
        )
        if (rumors.size > 14) rumors.removeAt(rumors.size - 1)
    }

    /** Opening the dead's doors is noticed — by the holder, and by whoever claims the ground. */
    private fun recordDelving(site: Site) {
        if (site.kind !in setOf(SiteKind.VAULT, SiteKind.RUIN, SiteKind.BARROW)) return
        if (site.id in delvedSites) return
        delvedSites += site.id
        val holder = world.power(site.holderPowerId)
        reputation.adjust(Layer.POWER, holder?.id, -5, "Delved where they keep the seal")
        world.claimsOn(site.id).forEach { claim ->
            if (claim.claimantPowerId != holder?.id) {
                reputation.adjust(Layer.POWER, claim.claimantPowerId, -3, "Delved on ground they claim")
            }
        }
        reputation.logDeed(
            Deed(
                day = day, domain = "delving", text = "Broke the seal of ${site.name}",
                siteId = site.id, powerId = holder?.id
            ).also { spreadWord(it, site.id) }
        )
        addRumor("\"Someone has broken the seal of ${site.name}.\"", "the brass road")
        pushLog("Word will get out that you opened ${site.name}.")
    }

    /** True when you stand at a living temple with brass enough for an offering. */
    fun canOffer(): Boolean {
        if (brass < 10) return false
        val site = world.sites.firstOrNull { it.id == currentSiteId } ?: return false
        return site.structures.any { it.kind == StructureKind.TEMPLE && !it.ruined }
    }

    /** Silver at the altar: the god takes note, the house remembers, the town softens. */
    fun offerAtTemple() {
        if (!canOffer()) return
        val site = world.sites.first { it.id == currentSiteId }
        brass -= 10
        val holder = world.power(site.holderPowerId)
        val deity = world.deities.firstOrNull { it.cultureId == holder?.cultureId }
            ?: world.deities.firstOrNull()
        val houseId = world.figures
            .lastOrNull { it.powerId == holder?.id && it.houseId != null && it.diedYear == null }
            ?.houseId
        val text = "Left silver at the temple of ${site.name}"
        deity?.let { reputation.adjust(Layer.DEITY, it.id, repGain(8), text) }
        houseId?.let { reputation.adjust(Layer.HOUSE, it, repGain(3), text) }
        reputation.adjust(Layer.SETTLEMENT, site.id, repGain(2), text)
        learnSkill(Skill.ETIQUETTE, 6f)
        reputation.logDeed(
            Deed(
                day = day, domain = "offering", text = text,
                siteId = site.id, powerId = holder?.id, houseId = houseId, deityId = deity?.id
            ).also { spreadWord(it, site.id) }
        )
        addRumor("\"A delver left silver at the temple of ${site.name}.\"", "temple steps")
        pushLog(
            if (deity != null) "You leave 10 brass at the temple. ${deity.name} takes note."
            else "You leave 10 brass at the temple. Someone takes note."
        )
    }

    /** True when you stand at a living shrine with a coin to spare. */
    fun canOfferAtShrine(): Boolean {
        if (brass < 5) return false
        val site = world.sites.firstOrNull { it.id == currentSiteId } ?: return false
        return site.kind == SiteKind.SHRINE
    }

    /** A coin in the bowl at a wayside shrine: the god's ear, and the locals' quiet approval. */
    fun offerAtShrine() {
        if (!canOfferAtShrine()) return
        val site = world.sites.first { it.id == currentSiteId }
        brass -= 5
        val cultureId = SiteGen.cultureId(world, site)
        val deity = world.deities.firstOrNull { it.cultureId == cultureId }
            ?: world.deities.firstOrNull()
        val text = "Left a coin at the shrine of ${site.name}"
        deity?.let { reputation.adjust(Layer.DEITY, it.id, repGain(5), text) }
        reputation.adjust(Layer.SETTLEMENT, site.id, repGain(2), text)
        learnSkill(Skill.ETIQUETTE, 4f)
        reputation.logDeed(
            Deed(
                day = day, domain = "offering", text = text,
                siteId = site.id, deityId = deity?.id
            ).also { spreadWord(it, site.id) }
        )
        addRumor("\"Someone left a coin at the stones of ${site.name}.\"", "the shrine road")
        pushLog(
            if (deity != null) "You leave 5 brass in the bowl. ${deity.name} takes note, and so do the locals."
            else "You leave 5 brass in the bowl. Someone takes note."
        )
    }

    fun castWard() {
        val cost = wardCost()
        if (magicka < cost) {
            pushLog("Not enough will left to shape a ward.")
            return
        }
        magicka -= cost
        wardTimer = 25f
        learnSkill(Skill.LORE, 3f)
        pushLog("A verdigris ward settles on your skin. It will hold a while.")
    }

    fun castMend() {
        val cost = mendCost()
        if (magicka < cost) {
            pushLog("The mending sigil will not take; your will is spent.")
            return
        }
        magicka -= cost
        val healed = ((10 + rng.nextInt(9)) * Derived.mendPotency(stats, growth)).roundToInt()
        vitality = (vitality + healed).coerceAtMost(maxVitality)
        learnSkill(Skill.MEDICINE, 4f)
        pushLog("Flesh knits, badly but enough. $healed restored.")
    }

    fun relightTorch() {
        // Nothing in hand: take up a torch and light it where you stand.
        if (!holdsTorch) {
            val spare = inventory.all.firstOrNull { it.archetype == ItemArchetype.TORCH }
            if (spare == null) {
                pushLog("No torch left to light.")
                return
            }
            val handsFull = equipment.leftClaimed ||
                (equipment.worn(Hand.RIGHT.slot) != null && equipment.worn(Hand.LEFT.slot) != null)
            if (handsFull) {
                pushLog("Your hands are full; take up the torch when one is free.")
                return
            }
            equipFromSatchel(spare)
            torch = 1f
            pushLog("You light the torch. The dark backs off.")
            return
        }
        // A torch from the bundle burns before your brass does.
        if (inventory.useTorch()) {
            torch = 1f
            pushLog("You light a fresh torch from the bundle. The dark backs off.")
            return
        }
        if (brass < 3) {
            pushLog("No pitch left to dress a torch. You need brass for that.")
            return
        }
        brass -= 3
        torch = 1f
        pushLog("You dress the torch with fresh pitch. The dark backs off.")
    }

    /** A grey remedy: bitter, effective, no skill in the drinking. */
    fun useRemedy(): Boolean {
        if (!inventory.useRemedy()) {
            pushLog("No remedies left in the satchel.")
            return false
        }
        val healed = 22 + rng.nextInt(9)
        vitality = (vitality + healed).coerceAtMost(maxVitality)
        pushLog("You drink a grey remedy. It tastes of cellar air. $healed restored.")
        return true
    }

    /** Lay a thing down where you stand; the USE hand takes it back up. */
    fun dropItem(item: Item) {
        if (!inventory.remove(item, item.count)) return
        groundItems += GroundItem(camera.x, camera.y, item)
        pushLog("You set the ${styleRoster.nameFor(item)} down on the ${if (outdoor) "ground" else "flags"}.")
    }

    /** What your back will bear right now, for the HUD's weight chip. */
    fun carryCapacity(): Float = Derived.carryCapacity(stats, growth)

    // ------------------------------------------------------------ the USE hand

    /** The nearest dropped thing within arm's reach, or null. */
    fun nearestDrop(): GroundItem? = groundItems
        .filter { MapFactory.distance(camera.x, camera.y, it.x, it.y) <= 1.6f }
        .minByOrNull { MapFactory.distance(camera.x, camera.y, it.x, it.y) }

    private fun pickUp(drop: GroundItem) {
        groundItems.remove(drop)
        inventory.add(drop.item)
        pushLog("You take up the ${styleRoster.nameFor(drop.item)}.")
    }

    /** A living, unwary pocket within fingers' reach — crouching required. */
    private fun pickpocketTarget(): Entity? {
        if (!crouched) return null
        return map.entities
            .filter { it.kind == EntityKind.ENEMY && it.alive && it.detection == Detection.UNAWARE }
            .filter { MapFactory.distance(camera.x, camera.y, it.x, it.y) <= 1.4f }
            .minByOrNull { MapFactory.distance(camera.x, camera.y, it.x, it.y) }
    }

    /** What the USE button would do right now, in the chronicler's words. */
    fun usePrompt(): String {
        if (dead) return ""
        pickpocketTarget()?.let { return "Pick the ${it.name}'s pocket" }
        nearestDrop()?.let { return "Take up the ${styleRoster.nameFor(it.item)}" }
        val candidates = mutableListOf<Pair<Float, String>>()
        lootableHere()?.let {
            candidates += Pair(
                MapFactory.distance(camera.x, camera.y, it.x, it.y), "Search the ${it.name}"
            )
        }
        soulHere()?.let {
            candidates += Pair(
                MapFactory.distance(camera.x, camera.y, it.x, it.y),
                if (it.resident) "Speak with ${it.name}" else "Speak with the ${it.name}"
            )
        }
        nearestPortal()?.let {
            candidates += Pair(MapFactory.distance(camera.x, camera.y, it.x, it.y), it.prompt)
        }
        return candidates.minByOrNull { it.first }?.second ?: ""
    }

    /** The USE hand: whatever stands nearest — pocket, dropped thing, spoils, or the way out. */
    fun interact(): Interact {
        if (dead) return Interact.NONE
        val options = mutableListOf<Triple<Float, Interact, () -> Unit>>()
        pickpocketTarget()?.let { target ->
            options += Triple(
                MapFactory.distance(camera.x, camera.y, target.x, target.y), Interact.PICKPOCKET
            ) { pickpocket() }
        }
        nearestDrop()?.let { drop ->
            options += Triple(
                MapFactory.distance(camera.x, camera.y, drop.x, drop.y), Interact.PICKED
            ) { pickUp(drop) }
        }
        lootableHere()?.let { spoils ->
            options += Triple(
                MapFactory.distance(camera.x, camera.y, spoils.x, spoils.y), Interact.LOOT
            ) { }
        }
        soulHere()?.let { soul ->
            options += Triple(
                MapFactory.distance(camera.x, camera.y, soul.x, soul.y), Interact.TRAVELER
            ) { meetTraveler(soul) }
        }
        nearestPortal()?.let { portal ->
            options += Triple(
                MapFactory.distance(camera.x, camera.y, portal.x, portal.y), Interact.PORTAL
            ) { usePortal() }
        }
        val nearest = options.minByOrNull { it.first } ?: return Interact.NONE
        nearest.third()
        return nearest.second
    }

    // ------------------------------------------------------------ equipment

    /** A one-handed piece asks which hand takes it, when both stand free. */
    fun canChooseHand(item: Item): Boolean =
        item.archetype.wear?.isHand == true && !item.archetype.twoHanded &&
            !equipment.leftClaimed &&
            equipment.worn(Hand.RIGHT.slot) == null &&
            equipment.worn(Hand.LEFT.slot) == null

    /** Don a thing from the satchel: it leaves the load, and what it displaced returns to it. */
    fun equipFromSatchel(item: Item, hand: Hand? = null) {
        // A bundle yields one of its kind to the hand; the rest stays shouldered.
        val taken = if (item.archetype.stacks && item.count > 1) {
            if (!inventory.remove(item, 1)) return
            item.copy(count = 1)
        } else {
            if (!inventory.remove(item)) return
            item
        }
        val result = equipment.equip(taken, hand)
        if (result == null) {
            inventory.add(taken)
            pushLog("That is not made to be worn.")
            return
        }
        val (slot, displaced) = result
        displaced.forEach { inventory.add(it) }
        val name = styleRoster.nameFor(taken)
        pushLog(
            when {
                slot == WearSlot.RIGHT_HAND && item.archetype.twoHanded -> "You take up the $name in both hands."
                slot.isHand -> "You take up the $name in your ${slot.label}."
                slot.isRing -> "You slide the $name onto your ${slot.label}."
                else -> "You buckle the $name on."
            }
        )
    }

    /** Strip a worn thing; it goes back to the satchel and the load. */
    fun unequipFromEquipment(slot: WearSlot) {
        val item = equipment.unequip(slot) ?: return
        inventory.add(item)
        pushLog("You take off the ${styleRoster.nameFor(item)}.")
    }

    // ------------------------------------------------------------ loot

    private fun containerKey(entity: Entity): String =
        if (depth >= SiteGen.BUILDING_FLOOR_BASE) {
            "$currentSiteId:b${depth - SiteGen.BUILDING_FLOOR_BASE}:${map.entities.indexOf(entity)}"
        } else {
            "$currentSiteId:${map.entities.indexOf(entity)}"
        }

    /** Containers remember being emptied: a looted grave stays looted when you return. */
    private fun applyLootedState() {
        map.entities.forEachIndexed { index, entity ->
            if (entity.container && "$currentSiteId:$index" in lootedContainers) {
                entity.loot.clear()
                entity.lootBrass = 0
            }
        }
        // A slain boss does not rise again: the world remembers the deed.
        if ("boss:$currentSiteId" in lootedContainers) {
            map.entities.removeAll { it.boss }
        }
    }

    private fun markLooted(entity: Entity) {
        if (entity.container && entity.loot.isEmpty() && entity.lootBrass == 0) {
            val key = containerKey(entity)
            if (key !in lootedContainers) {
                lootedContainers += key
                stolenFrom(entity)
            }
        }
    }

    /** Lifting a keeper's things costs the place's regard — once per chest. */
    private fun stolenFrom(entity: Entity) {
        val site = world.sites.firstOrNull { it.id == currentSiteId } ?: return
        if (!site.isSettlement || !entity.container) return
        reputation.adjust(Layer.SETTLEMENT, site.id, -2, "Stole from the ${entity.name}")
        pushLog("The ${entity.name} will be missed, and the loss remembered.")
    }

    /** The nearest thing with spoils: a fresh corpse or a container not yet emptied. */
    fun lootableHere(): Entity? {
        var best: Entity? = null
        var bestDist = 1.7f
        map.entities.forEach { entity ->
            val lootable = (entity.kind == EntityKind.ENEMY && !entity.alive || entity.container) &&
                (entity.loot.isNotEmpty() || entity.lootBrass > 0 || entity.equipment?.isEmpty() == false)
            if (!lootable) return@forEach
            val d = MapFactory.distance(camera.x, camera.y, entity.x, entity.y)
            if (d < bestDist) {
                bestDist = d
                best = entity
            }
        }
        return best
    }

    /** Strip one worn thing from a corpse; it goes straight to the satchel. */
    fun takeEquipped(entity: Entity, slot: WearSlot) {
        val item = entity.equipment?.unequip(slot) ?: return
        inventory.add(item)
        pushLog("You strip the ${styleRoster.nameFor(item)} from the ${entity.name}.")
    }

    /** Lift one thing from a corpse or container; brass rides along when it is the last. */
    fun takeItem(entity: Entity, item: Item) {
        if (!entity.loot.remove(item)) return
        inventory.add(item)
        pushLog("You lift the ${styleRoster.nameFor(item)}.")
        if (entity.loot.isEmpty() && entity.lootBrass > 0) {
            brass += entity.lootBrass
            pushLog("You pry ${entity.lootBrass} brass from beneath it.")
            entity.lootBrass = 0
        }
        markLooted(entity)
    }

    /** Empty a corpse or container of everything it holds — worn things first — coins included. */
    fun takeAll(entity: Entity): Int {
        val hasGear = entity.equipment?.isEmpty() == false
        if (entity.loot.isEmpty() && !hasGear && entity.lootBrass == 0) return 0
        val gained = entity.lootBrass
        var taken = 0
        // The dead are stripped before they are emptied.
        if (hasGear) {
            WearSlot.entries.forEach { slot ->
                entity.equipment?.unequip(slot)?.let {
                    inventory.add(it)
                    taken++
                }
            }
        }
        while (entity.loot.isNotEmpty()) {
            inventory.add(entity.loot.removeAt(entity.loot.size - 1))
            taken++
        }
        entity.lootBrass = 0
        brass += gained
        pushLog(
            "You empty the ${entity.name}: $taken ${if (taken == 1) "thing" else "things"}" +
                if (gained > 0) " and $gained brass." else "."
        )
        markLooted(entity)
        return taken
    }

    /** Rest: hours pass, wounds close, and something may find you first. */
    fun rest(hours: Int) {
        if (dead) return
        // Rest is only as safe as your name in the place you rest — and rain draws ears too.
        // A roof of your own beats the ditch: buildings sleep dry, barred, ambush-free.
        val sheltered = depth >= SiteGen.BUILDING_FLOOR_BASE
        val inn = sheltered && inBuilding?.kind == "tavern"
        if (inn) {
            if (brass < 5) {
                pushLog("The keeper of the ${inBuilding?.name} wants 5 brass for a bed. You haven't it.")
                return
            }
            brass -= 5
        }
        val wet = !sheltered && outdoor && weather.rain > 0.25f
        val ambush = when {
            sheltered -> false
            !outdoor -> rng.nextInt(3) == 0
            else -> (reputation.regardFor(currentSiteId) <= -25 && rng.nextInt(2) == 0) ||
                (wet && rng.nextInt(3) == 0)
        }
        val actual = if (ambush) (hours / 2).coerceAtLeast(1) else hours
        val (vitalityPerHour, fatiguePerHour) = restRecovery(wet)
        val woundsBefore = vitality
        val wearinessBefore = fatigue
        val willBefore = magicka
        minutes += actual * 60f
        vitality = (vitality + actual * vitalityPerHour).coerceAtMost(maxVitality)
        fatigue = (fatigue + actual * fatiguePerHour).coerceAtMost(maxFatigue)
        magicka = (magicka + actual * 5).coerceAtMost(maxMagicka)
        learnSkill(Skill.SURVIVAL, actual * 0.9f)
        ageRumors(actual / 24f)
        if (ambush) {
            pushLog("You are woken after $actual hours. Something is already in the room.")
            spawnAmbush()
        } else {
            torch = (torch + 0.35f).coerceAtMost(1f)
            pushLog(
                when {
                    inn -> "You take a bed at the ${inBuilding?.name}: dry, dark, and barred behind you."
                    sheltered -> "You sleep dry behind a barred door. The night passes without trouble."
                    wet -> "You rest $actual hours under the rain, cold and hardly dry. It buys little."
                    else -> "You rest $actual hours. The province gets on without you."
                }
            )
        }
        pushLog(
            "Wounds close some (vitality +${vitality - woundsBefore}); the tiredness lifts " +
                "(weariness -${wearinessBefore - fatigue}); will gathers (will +${magicka - willBefore})."
        )
    }

    /** Whole hours until first light, read from the clock as it stands. */
    fun hoursTillDawn(): Int = hoursTillDawn(hour, minute)

    /** Rest until first light, however long the clock says that is. */
    fun restTillDawn() = rest(hoursTillDawn())

    /** Powers that rate you Blood-owed send riders into the wilds near their holdings. */
    private fun updatePatrols(dt: Float) {
        if (!outdoor || onOverland || dead) return
        patrolTimer -= dt
        if (patrolTimer > 0f) return
        patrolTimer = 25f + rng.nextFloat() * 35f
        val here = world.sites.firstOrNull { it.id == currentSiteId } ?: return
        val hunters = world.powers.filter { power ->
            reputation.standingFor(power.id) <= -60 && world.sites.any { site ->
                site.holderPowerId == power.id && site.isSettlement &&
                    MapFactory.distance(site.x, site.y, here.x, here.y) <= 2.2f
            }
        }
        if (hunters.isEmpty() || rng.nextInt(3) != 0) return
        val power = hunters.random(rng)
        repeat(1 + rng.nextInt(2)) {
            val angle = rng.nextFloat() * 6.28f
            val ex = (camera.x + cos(angle) * 4f).coerceIn(1.5f, map.width - 1.5f)
            val ey = (camera.y + sin(angle) * 4f).coerceIn(1.5f, map.height - 1.5f)
            if (!map.isWall(ex, ey)) {
                map.entities += spawnNpc(
                    x = ex, y = ey, name = "${power.name} outrider", spriteId = Sprites.HUSK,
                    height = 1.0f, baseSpeed = 1.05f, level = 1 + depth + rng.nextInt(2),
                    weights = OUTRIDER_WEIGHTS
                )
            }
        }
        pushLog("Riders of ${power.name} have found you on the road.")
    }

    private val OUTRIDER_WEIGHTS = mapOf("bulwark" to 3, "warden" to 2, "wayfarer" to 1)

    /** Roll a named creature of the province with its class and level-earned stats. */
    private fun spawnNpc(
        x: Float,
        y: Float,
        name: String,
        spriteId: Int,
        height: Float,
        baseSpeed: Float,
        level: Int,
        weights: Map<String, Int>
    ): Entity {
        val klass = roster.byKey(MapFactory.weightedStringPick(weights, rng) ?: "")
        val stats = klass?.let { rollNpcStats(level, it.weights, rng) }
        val skills = Growth.forClass(klass).also { it.level = level }
        val entity = if (klass == null || stats == null) {
            Entity(
                x = x, y = y, spriteId = spriteId, kind = EntityKind.ENEMY, height = height,
                name = name, hp = 14 + level * 3, maxHp = 14 + level * 3,
                damage = 4 + level, speed = baseSpeed, level = level, skills = skills
            )
        } else {
            Entity(
                x = x, y = y, spriteId = spriteId, kind = EntityKind.ENEMY, height = height,
                name = name,
                hp = Derived.npcMaxHp(stats, skills, level), maxHp = Derived.npcMaxHp(stats, skills, level),
                damage = Derived.npcDamage(stats, skills, level), speed = Derived.npcSpeed(stats, skills, baseSpeed),
                level = level, klass = klass, stats = stats, skills = skills
            )
        }
        MapFactory.armLoot(entity, klass, level, siteCultureId(currentSiteId), rng, geography)
        return entity
    }

    /** A creature learns from every exchange with you, in the same grammar as you do. */
    fun trainNpc(entity: Entity, skill: Skill, amount: Float) {
        if (entity.kind != EntityKind.ENEMY || !entity.alive || amount <= 0f) return
        entity.skills.feed(skill, entity.stats ?: StatBlock.balanced(), amount) ?: return
        entity.risesThisLife++
        refreshNpc(entity)
        if (entity.skills.level > entity.level) {
            entity.level = entity.skills.level
            pushLog("The ${entity.name} fights like something older now — level ${entity.level}.")
        }
    }

    /** A creature's arm teaches its kind and its own piece, in the same grammar as the delver's. */
    private fun trainNpcWeapon(entity: Entity, profAmount: Float, masteryAmount: Float) {
        if (entity.kind != EntityKind.ENEMY || !entity.alive) return
        val weapon = entity.equipment?.bestWeapon()
        val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
        var learned = entity.proficiencies.feed(category, profAmount) != null
        if (weapon != null && entity.masteries.feed(weapon.uid, masteryAmount) != null) learned = true
        if (learned) refreshNpc(entity)
    }

    /** A creature's combat numbers follow its skills, exactly as the delver's do. */
    private fun refreshNpc(entity: Entity) {
        val npcStats = entity.stats
        if (npcStats != null) {
            val newMax = Derived.npcMaxHp(npcStats, entity.skills, entity.level)
            entity.hp += (newMax - entity.maxHp).coerceAtLeast(1)
            entity.maxHp = newMax
            val weapon = entity.equipment?.bestWeapon()
            val category = weapon?.let { WeaponCategory.forWeapon(it.archetype) } ?: WeaponCategory.UNARMED
            entity.damage = Derived.npcDamage(
                npcStats, entity.skills, entity.level, weapon,
                entity.proficiencies.value(category),
                weapon?.let { entity.masteries.value(it.uid) } ?: 0
            )
            entity.speed = Derived.npcSpeed(npcStats, entity.skills, entity.speed)
        } else {
            // The old hardcoded shapes learn only their level: a blunt arithmetic.
            entity.maxHp += 3
            entity.hp += 3
            entity.damage += 1
        }
    }

    // ------------------------------------------------------------ sneak

    /** How much light there is to be seen by: the sun's hour, or your torch. */
    private fun lightFactor(): Float = if (outdoor) {
        when (hour) {
            in 6..18 -> 1f
            in 5..6, in 19..20 -> 0.7f
            else -> 0.35f
        }
    } else {
        0.35f + torch * 0.65f
    }

    /** The standing contest: its Awareness against your Stealth, under this light. */
    private fun detectionRadius(entity: Entity): Float {
        val sight = lightFactor() * Derived.detectionScore(entity.stats ?: StatBlock.balanced(), entity.skills)
        return Derived.detectionRadius(sight, Derived.stealthScore(stats, growth, crouched))
    }

    private fun updateDetection(entity: Entity, dist: Float, dt: Float) {
        val radius = detectionRadius(entity)
        val was = entity.detection
        entity.detection = when {
            dist < radius -> Detection.AWARE
            dist < radius * 1.6f -> Detection.SEARCHING
            dist > 16f -> Detection.UNAWARE
            else -> entity.detection
        }
        if (entity.detection == Detection.AWARE && was != Detection.AWARE) {
            trainNpc(entity, Skill.AWARENESS, 6f)
        }
        // Remaining unseen within earshot is its own lesson.
        if (crouched && dist < 12f && entity.detection == Detection.UNAWARE) {
            learnSkill(Skill.STEALTH, dt * 1.5f)
        }
    }

    /** The eye for the HUD: the worst any nearby creature has made of you. */
    fun detectionState(): DetectionState {
        var worst = DetectionState.NONE
        map.entities.forEach { entity ->
            if (entity.kind != EntityKind.ENEMY || !entity.alive) return@forEach
            if (MapFactory.distance(camera.x, camera.y, entity.x, entity.y) > 14f) return@forEach
            val state = when (entity.detection) {
                Detection.AWARE -> DetectionState.SEEN
                Detection.SEARCHING -> DetectionState.SEARCHING
                Detection.UNAWARE -> DetectionState.HIDDEN
            }
            if (state.ordinal > worst.ordinal) worst = state
        }
        return worst
    }

    /** Weight onto the heels: slower, quieter, harder to see. */
    fun toggleCrouch() {
        crouched = !crouched
        pushLog(if (crouched) "You crouch, weight on your heels, breath small." else "You rise to your full height.")
    }

    /** Fingers into the pocket of something that cannot see you: Sleight against Awareness. */
    fun pickpocket(): Boolean {
        if (dead) return false
        if (!crouched) {
            pushLog("You cannot pick a pocket standing up.")
            return false
        }
        val target = pickpocketTarget()
        if (target == null) {
            pushLog("No unwary pocket within reach.")
            return false
        }
        learnSkill(Skill.SLEIGHT_OF_HAND, 4f)
        val chance = Derived.pickpocketChance(
            growth.value(Skill.SLEIGHT_OF_HAND),
            target.skills.value(Skill.AWARENESS)
        )
        return if (rng.nextInt(100) < chance) {
            val coins = 2 + rng.nextInt(5) + target.level
            brass += coins
            world.sites.firstOrNull { it.id == currentSiteId }
                ?.takeIf { it.isSettlement }
                ?.let { reputation.adjust(Layer.SETTLEMENT, it.id, -1, "Picked a pocket") }
            pushLog("Your fingers find $coins brass in the ${target.name}'s pocket. It never stirs.")
            target.loot.firstOrNull { it.archetype.slot == ItemSlot.TRINKET }?.let { trinket ->
                target.loot.remove(trinket)
                inventory.add(trinket)
                pushLog("And something else besides: the ${styleRoster.nameFor(trinket)}.")
            }
            learnSkill(Skill.SLEIGHT_OF_HAND, 6f)
            true
        } else {
            target.detection = Detection.AWARE
            trainNpc(target, Skill.AWARENESS, 8f)
            pushLog("The ${target.name} starts — it felt your fingers. It knows you now.")
            false
        }
    }

    private var patrolTimer = 15f

    /** The kind gods of a domain hear you; the angry ones turn away. */
    private fun domainPiety(domain: String): Int {
        val gods = world.deities.filter { it.domain == domain }
        if (gods.isEmpty()) return 0
        val best = gods.maxOf { reputation.pietyFor(it.id) }
        val worst = gods.minOf { reputation.pietyFor(it.id) }
        return if (best >= 60) best else if (worst <= -40) worst else 0
    }

    fun wardCost(): Int {
        val base = (8 - (stats[Attr.INTELLECT] - StatBlock.BASE) / 2).coerceAtLeast(5)
        return when {
            domainPiety("the pale moon") >= 60 -> (base * 0.6f).roundToInt()
            domainPiety("the pale moon") <= -40 -> base + 4
            else -> base
        }
    }

    fun mendCost(): Int {
        val base = (12 - (stats[Attr.INTELLECT] - StatBlock.BASE) / 2).coerceAtLeast(8)
        return when {
            domainPiety("quiet") >= 60 -> (base * 0.66f).roundToInt()
            domainPiety("quiet") <= -40 -> base + 4
            else -> base
        }
    }

    private fun spawnAmbush() {
        val angle = rng.nextFloat() * 6.28f
        val ex = (camera.x + cos(angle) * 3f).coerceIn(1.5f, map.width - 1.5f)
        val ey = (camera.y + sin(angle) * 3f).coerceIn(1.5f, map.height - 1.5f)
        if (map.isWall(ex, ey)) return
        map.entities += spawnNpc(
            x = ex, y = ey, name = "husk", spriteId = Sprites.HUSK,
            height = 1.0f, baseSpeed = 1.0f, level = 1 + depth + rng.nextInt(2),
            weights = MapFactory.CREATURE_WEIGHTS.getValue("husk")
        )
        hurtFlash = 0.5f
    }

    // ---------------------------------------------------------- the living wild

    private var walkedCells = 0f
    private var nightWarnDay = -1
    private var skyEventDay = -1
    private var lastWeatherKind: WeatherKind? = null
    private var torchWarnDay = -1
    private var fatigueWarnDay = -1

    /** The aurora's strength over this spot, when you stand in the far north. */
    fun auroraStrength(): Float =
        if (!onOverland) 0f else SkyEvents.auroraStrength(
            world.seed, day, 1f - camera.y / OverlandGen.SIZE, weather.cloud
        )

    private fun weatherLine(kind: WeatherKind): String = when (kind) {
        WeatherKind.CLEAR -> "The sky has swept clean."
        WeatherKind.HAZE -> "A high haze thins the light."
        WeatherKind.OVERCAST -> "Cloud has come up from the sea. The stars are lost."
        WeatherKind.RAIN -> "Rain comes rattling down. Even the crows keep under."
    }
    private val sighted = mutableSetOf<Int>()

    /** Beasts of the chronicle slain in the wild: their lairs stand empty. */
    private fun slainBeastIds(): Set<Int> = lootedContainers
        .filter { it.startsWith("beast:") }
        .mapNotNull { it.removePrefix("beast:").toIntOrNull() }
        .toSet()

    /** The nearest site matching a calling, within a reach of country. */
    private fun nearestSiteOf(match: (Site) -> Boolean, radius: Float): Site? {
        val cx = camera.x / overland.width
        val cy = camera.y / overland.height
        return world.sites.filter(match)
            .minByOrNull { MapFactory.distance(it.x, it.y, cx, cy) }
            ?.takeIf { MapFactory.distance(it.x, it.y, cx, cy) <= radius }
    }

    /** A spot in the open, a few paces out from where you stand, clear of every door. */
    private fun openSpotNear(): Pair<Float, Float>? {
        repeat(12) {
            val angle = rng.nextFloat() * 6.28f
            val dist = 5f + rng.nextFloat() * 4f
            val x = camera.x + cos(angle) * dist
            val y = camera.y + sin(angle) * dist
            val blocked = map.portals.any { MapFactory.distance(x, y, it.x, it.y) < 2.2f }
            if (!map.isWall(x, y) && !blocked) return Pair(x, y)
        }
        return null
    }

    /**
     * The wild keeps its own counsel: rolled as you walk, from the seed, and
     * shaped by the ground — whose lair, whose camp, how far from help.
     */
    internal fun rollEncounter(force: Int? = null) {
        val roll = force ?: rng.nextInt(100)
        val lair = nearestSiteOf({ site ->
            world.beasts.any { it.lairSiteId == site.id && it.alive && it.id !in slainBeastIds() }
        }, 0.16f)
        val camp = nearestSiteOf({ it.kind == SiteKind.CAMP }, 0.12f)
        val gates = nearestSiteOf({ it.isSettlement && !it.ruined }, 0.10f)
        when {
            lair != null && roll < 40 -> spawnRoamingBeast(lair)
            camp != null && roll < 60 -> spawnBanditAmbush(camp)
            gates != null && roll < 85 -> spawnRoadPatrol(gates, roll)
            else -> spawnTraveler()
        }
    }

    /** The chronicle's terror, met where it hunts rather than where it sleeps. */
    private fun spawnRoamingBeast(lair: Site) {
        val beast = world.beasts.firstOrNull {
            it.lairSiteId == lair.id && it.alive && it.id !in slainBeastIds()
        } ?: return
        val spot = openSpotNear() ?: return
        val entity = MapFactory.rollEnemy(
            rng, spot.first, spot.second, 4 + day / 12,
            roster, SiteGen.cultureId(world, lair), geography
        )
        entity.name = beast.name
        entity.beastId = beast.id
        entity.spriteId = Sprites.HOUND
        entity.height = 1.35f
        entity.maxHp = entity.maxHp * 3
        entity.hp = entity.maxHp
        entity.damage += (entity.damage * 0.4f).roundToInt().coerceAtLeast(1)
        map.entities += entity
        pushLog("${beast.name} breaks cover — the chronicle's own terror, out in the open.")
    }

    /** Reavers from the nearest warband, and the road gives you no ground. */
    private fun spawnBanditAmbush(camp: Site) {
        repeat(1 + rng.nextInt(2)) {
            val spot = openSpotNear() ?: return@repeat
            map.entities += spawnNpc(
                x = spot.first, y = spot.second,
                name = world.power(camp.holderPowerId)?.let { "${it.name} reaver" } ?: "brigand",
                spriteId = Sprites.HUSK, height = 1.0f, baseSpeed = 1.05f,
                level = 2 + day / 20, weights = OUTRIDER_WEIGHTS
            )
        }
        pushLog("Reavers step onto the road out of ${camp.name}'s country.")
    }

    /** Roads near living gates carry watchmen, and their welcome follows your name. */
    private fun spawnRoadPatrol(settlement: Site, roll: Int) {
        val holder = world.power(settlement.holderPowerId)
        when (reputation.standingFor(holder?.id)) {
            in Int.MIN_VALUE..-60 -> {
                if (roll % 2 == 0) {
                    repeat(1 + rng.nextInt(2)) {
                        val spot = openSpotNear() ?: return@repeat
                        map.entities += spawnNpc(
                            x = spot.first, y = spot.second,
                            name = "${holder?.name ?: "the hold"} outrider",
                            spriteId = Sprites.HUSK, height = 1.0f, baseSpeed = 1.05f,
                            level = 1 + depth + rng.nextInt(2), weights = OUTRIDER_WEIGHTS
                        )
                    }
                    pushLog("Riders of ${holder?.name ?: "the hold"} have found you on the road.")
                } else {
                    pushLog("You keep off the road's crown, and riders of ${holder?.name ?: "the hold"} pass by.")
                }
            }
            in 45..Int.MAX_VALUE -> {
                pushLog("A patrol of ${settlement.name} falls in beside you a while and shares the road's news.")
                addRumor(
                    "\"${settlement.name} keeps its roads honest, and its patrols sharp.\"",
                    "a patrol of ${settlement.name}"
                )
                learnSkill(Skill.ETIQUETTE, 4f)
            }
            else -> pushLog("Watchmen of ${settlement.name} mark you from the road and pass on.")
        }
    }

    /** Peddlers and pilgrims: always harmless, always worth the hearing. */
    private fun spawnTraveler() {
        val spot = openSpotNear() ?: return
        val entity = Entity(
            x = spot.first, y = spot.second,
            spriteId = Sprites.PILGRIM, kind = EntityKind.PROP, height = 1.05f,
            name = if (rng.nextBoolean()) "peddler" else "pilgrim",
            traveler = true
        )
        map.entities += entity
        pushLog(
            if (weather.kind == WeatherKind.RAIN) "A ${entity.name} plods past through the rain, hood low and hurrying."
            else "A ${entity.name} walks the road ahead, unhurried."
        )
    }

    /** A soul within a word's reach: residents at home and the road's travelers. */
    private fun soulHere(): Entity? = map.entities
        .filter { it.traveler || (it.resident && it.alive) }
        .filter { MapFactory.distance(camera.x, camera.y, it.x, it.y) <= 1.7f }
        .minByOrNull { MapFactory.distance(camera.x, camera.y, it.x, it.y) }

    /** The road's small courtesy: news for news, and something for the walk. */
    private fun meetTraveler(traveler: Entity) {
        if (traveler.resident) {
            meetResident(traveler)
            return
        }
        map.entities.remove(traveler)
        learnSkill(Skill.ETIQUETTE, 3f)
        learnSkill(Skill.SURVIVAL, 2f)
        // The road has heard your name already, and it grades its news accordingly.
        when (travelerTone(reputation.regardFor(currentSiteId))) {
            TravelerTone.GRUDGING -> {
                addRumor(
                    "\"${world.sites.random(rng).name} wants no talk with the likes of you.\"",
                    "a wary ${traveler.name}",
                    daysOld = 4 + rng.nextInt(10)
                )
                pushLog("The ${traveler.name} marks your face, says little, and keeps a hand near a knife.")
                return
            }
            TravelerTone.RICH -> {
                val unvisited = world.sites.filter { it.id !in visitedSites }
                if (unvisited.isNotEmpty() && rng.nextInt(3) < 2) {
                    val place = unvisited.random(rng)
                    addRumor(
                        "\"${travelerLine(place)} ${travelerFlourish(place, rng)}\"",
                        "a ${traveler.name} on the road",
                        place.id
                    )
                    pushLog("The ${traveler.name} talks of ${place.name} at length, and the bearing of it.")
                } else if (sky.constellations.isNotEmpty()) {
                    val con = sky.constellations.random(rng)
                    addRumor("\"${con.lore}\"", "a ${traveler.name} on the road")
                    pushLog("The ${traveler.name} points out ${con.name} where it stands, and tells its story well.")
                } else {
                    addRumor(
                        "\"${world.sites.random(rng).name} saw a hard year. Everyone says so.\"",
                        "a ${traveler.name} on the road"
                    )
                    pushLog("The ${traveler.name} swaps the road's news and walks on.")
                }
            }
            TravelerTone.PLAIN -> {
                val unvisited = world.sites.filter { it.id !in visitedSites }
                if (unvisited.isNotEmpty() && rng.nextInt(3) < 2) {
                    val place = unvisited.random(rng)
                    addRumor("\"${travelerLine(place)}\"", "a ${traveler.name} on the road", place.id)
                    pushLog("The ${traveler.name} names ${place.name} and the bearing of it.")
                } else if (rng.nextInt(4) == 0 && sky.constellations.isNotEmpty()) {
                    // the road reads the sky too: lore of the constellations rides with it
                    val con = sky.constellations.random(rng)
                    addRumor("\"${con.lore}\"", "a ${traveler.name} on the road")
                    pushLog("The ${traveler.name} points out ${con.name} where it stands.")
                } else {
                    addRumor(
                        "\"${world.sites.random(rng).name} saw a hard year. Everyone says so.\"",
                        "a ${traveler.name} on the road"
                    )
                    pushLog("The ${traveler.name} swaps the road's news and walks on.")
                }
            }
        }
        // the road talks about the weather as much as anything
        if (weather.kind == WeatherKind.RAIN && rng.nextInt(2) == 0) {
            addRumor(
                "\"No one camps in this. Every fire tonight is a wet story.\"",
                "a soaked ${traveler.name} on the road"
            )
        }
        if (rng.nextBoolean()) {
            inventory.add(Item(ItemArchetype.REMEDY, Material.VERDIGRIS))
            pushLog("A remedy is pressed into your hand for the sharing.")
        } else {
            brass += 2 + rng.nextInt(4)
            pushLog("A few brass change hands for the news you carry.")
        }
    }

    /** The keeper's word: the folk of the place, and a rumor for the road. */
    private fun meetResident(soul: Entity) {
        learnSkill(Skill.ETIQUETTE, 3f)
        val site = world.sites.firstOrNull { it.id == currentSiteId }
        val folk = site?.let { settlements.folkOf(it) } ?: 0
        val stage = site?.let { stageAt(it).label } ?: "stead"
        pushLog(
            "${soul.name} gives you the time of day. \"" +
                "We were $folk at the last turn of the year. A $stage keeps its doors shut after dark.\""
        )
        val place = world.sites.filter { !it.ruined }.randomOrNull(rng) ?: return
        addRumor("\"${travelerLine(place)}\"", "${soul.name} of ${site?.name}", place.id)
        if (rng.nextInt(3) == 0) {
            brass += 1 + rng.nextInt(3)
            pushLog("A little brass for the talk.")
        }
    }

    /** The extra word a welcome traveler spends on a place: richer news for the fondly regarded. */
    private fun travelerFlourish(site: Site, rng: Random): String = when (rng.nextInt(3)) {
        0 -> "They keep a good fire there, and better talk."
        1 -> "Word is the roads to it are watched and safely walked this season."
        else -> "The wells are sweet and the lofts are warm, for now."
    }

    private fun travelerLine(site: Site): String = when (site.kind) {
        SiteKind.VAULT -> "the sealed door of ${site.name} keeps its brass nails yet, if the hills have not swallowed it"
        SiteKind.BARROW -> "a cairn marks ${site.name}, and the dead beneath keep their own counsel"
        SiteKind.RUIN -> "${site.name} is fallen walls and cellars now; folk say the old keepers never left"
        SiteKind.CAMP -> "a warband keeps its fires at ${site.name}; walk wide or walk armed"
        SiteKind.SHRINE -> "the stones still stand at ${site.name}; leave a coin and pass quietly"
        else -> "${site.name} stands on the road still, and its wells are sweet this year"
    }

    // ------------------------------------------------------------------ portals & travel

    private fun nearestPortal(): Portal? {
        var best: Portal? = null
        var bestDist = 2.6f
        map.portals.forEach { portal ->
            val d = MapFactory.distance(camera.x, camera.y, portal.x, portal.y)
            // a door answers from a pace or two off; the road waits until you stand on it
            if (d < bestDist && (portal.door || d < 1.9f)) {
                bestDist = d
                best = portal
            }
        }
        return best
    }

    fun nearPortal(): Boolean = nearestPortal() != null

    fun portalPrompt(): String = nearestPortal()?.prompt ?: ""

    fun usePortal() {
        nearestPortal()?.let { enter(it) }
    }

    /** Take the nearest way: down into the dark, up a stair, into a place, or out under the sky. */
    private fun enter(portal: Portal) {
        if (portal.targetFloor <= OverlandGen.OVERLAND_FLOOR) {
            leaveToOverland()
            return
        }
        if (portal.targetFloor >= SiteGen.BUILDING_FLOOR_BASE) {
            enterBuilding(portal)
            return
        }
        if (portal.targetFloor == 0 && depth >= SiteGen.BUILDING_FLOOR_BASE) {
            leaveBuilding(portal)
            return
        }
        portal.targetSiteId.takeIf { it >= 0 }?.let { id ->
            enterSite(world.site(id))
            return
        }
        val site = world.site(currentSiteId)
        val goingUnder = portal.targetFloor > 0
        depth = portal.targetFloor
        outdoor = !goingUnder
        minutes += 15f
        map = SiteGen.map(
            world, site, depth, roster, geography, day, slainBeastIds(),
            folk = folkOf(site.id)
        )
        applyLootedState()
        if (!goingUnder) surfaceMap = map
        val spot = map.arrivalSpots.getOrNull(portal.arrivalIndex)
        if (spot != null && !map.isWall(spot.first, spot.second)) {
            camera.x = spot.first
            camera.y = spot.second
        } else {
            camera.x = map.spawnX
            camera.y = map.spawnY
        }
        camera.angle = map.spawnAngle
        if (goingUnder) {
            world.sites.firstOrNull { it.id == currentSiteId }?.let { recordDelving(it) }
            val skin = SiteGen.skinFor(SiteGen.cultureId(world, site))
            pushLog(
                if (!portal.down) "You climb the stair up. ${skin.darkLine}"
                else if (depth == 1) "You go down into ${map.title}. ${skin.darkLine}"
                else "You take the stair down. ${skin.darkLine}"
            )
            if (portal.down && depth == SiteGen.floorCount(world, site)) {
                SiteGen.bossFor(world, site).line?.let { pushLog(it) }
            }
        } else {
            pushLog("You come up into the grey light beside ${map.title}.")
            if (!deeds.contains("Climbed out of ${siteName()}")) deeds += "Climbed out of ${siteName()}"
        }
    }

    /** Through the door: an interior of exactly the footprint its walls keep. */
    private fun enterBuilding(portal: Portal) {
        val siteId = portal.targetSiteId.takeIf { it >= 0 } ?: currentSiteId
        val site = world.sites.firstOrNull { it.id == siteId } ?: return
        val yard = surfaceMap ?: map.takeIf { it.buildings.isNotEmpty() }
        val index = portal.targetFloor - SiteGen.BUILDING_FLOOR_BASE
        val building = yard?.buildings?.getOrNull(index) ?: return
        surfaceMap = yard
        inBuilding = building
        depth = portal.targetFloor
        outdoor = false
        minutes += 2f
        map = SiteGen.buildingInterior(
            world, site, index, building, SiteGen.cultureId(world, site), day, geography
        )
        applyLootedState()
        removeSlainResidents()
        map.arrivalSpots.firstOrNull()?.let { spot ->
            camera.x = spot.first
            camera.y = spot.second
        } ?: run {
            camera.x = map.spawnX
            camera.y = map.spawnY
        }
        camera.angle = map.spawnAngle
        pushLog("You step into ${building.name}.")
    }

    /** Back out through the door, into the yard you left, by the door you used. */
    private fun leaveBuilding(portal: Portal) {
        val yard = surfaceMap
        inBuilding = null
        depth = 0
        outdoor = true
        minutes += 2f
        if (yard == null) {
            enterSite(world.site(portal.targetSiteId.takeIf { it >= 0 } ?: currentSiteId))
            return
        }
        map = yard
        val spot = yard.arrivalSpots.getOrNull(portal.arrivalIndex)
        if (spot != null && !yard.isWall(spot.first, spot.second)) {
            camera.x = spot.first
            camera.y = spot.second
        } else {
            camera.x = yard.spawnX
            camera.y = yard.spawnY
        }
        pushLog("You step out into the yard of ${yard.title}.")
    }

    /** A landmark takes you in: the yard, the gate, the doorstep of the place itself. */
    private fun enterSite(site: Site) {
        val firstVisit = site.id !in visitedSites
        currentSiteId = site.id
        onOverland = false
        inBuilding = null
        depth = 0
        outdoor = true
        minutes += 15f
        map = SiteGen.map(
            world, site, 0, roster, geography, day, slainBeastIds(),
            folk = folkOf(site.id)
        )
        surfaceMap = map
        applyLootedState()
        removeSlainResidents()
        camera.x = map.spawnX
        camera.y = map.spawnY
        camera.angle = map.spawnAngle
        torch = (torch + 0.25f).coerceAtMost(1f)
        visitedSites += site.id
        visitedOn[site.id] = day
        pushLog("You come to ${site.name}.")
        if (site.kind == SiteKind.CAMP && map.entities.any { it.resident }) {
            pushLog("Folk keep the tents of ${site.name}; their watchfire burns at the heart.")
        }
        if (site.kind == SiteKind.SHRINE && weather.rain > 0.25f) {
            pushLog("The offering bowls brim with rainwater. No one kneels at the stones today.")
        }
        if (firstVisit) {
            deeds += "Stood within ${site.name}"
            if (site.kind in setOf(SiteKind.VAULT, SiteKind.RUIN, SiteKind.BARROW)) {
                recordDelving(site)
            }
        }
        if (site.isSettlement) {
            val regard = reputation.regardFor(site.id)
            val why = reputation.reasonFor(Layer.SETTLEMENT, site.id)
            when {
                regard <= -60 ->
                    pushLog("The gates of ${site.name} are barred against you. ${why ?: ""}".trim())
                regard <= -25 ->
                    pushLog("The gate-watch of ${site.name} knows your face and names it poorly.")
                regard >= 45 ->
                    pushLog(
                        "At the gates of ${site.name} they name you ${regardLabel(regard).lowercase()}. " +
                            (why ?: "")
                    )
            }
        }
    }

    /** Out of a place and into the open country again, beside the way you came. */
    private fun leaveToOverland() {
        val site = world.site(currentSiteId)
        onOverland = true
        outdoor = true
        depth = 0
        surfaceMap = null
        inBuilding = null
        map = overland
        val spot = overland.entrySpots[site.id]
        camera.x = spot?.first ?: map.spawnX
        camera.y = spot?.second ?: map.spawnY
        camera.angle = -1.5708f
        pushLog("You take the open ground before ${site.name}.")
    }

    // ------------------------------------------------------------------ bearings

    /** Pins: rumor-named places and every living settlement, plus where you have stood. */
    fun bearings(): List<Bearing> {
        val rumorPins = rumors.mapNotNull { rumor ->
            rumor.siteId.takeIf { it >= 0 }
        }.toSet()
        val knownPins = world.sites.filter { it.isSettlement && !it.ruined }.map { it.id }.toSet()
        val pins = mutableListOf<Bearing>()
        val stood = mutableListOf<Bearing>()
        world.sites.forEach { site ->
            when {
                site.id in visitedSites -> stood += bearingTo(site).copy(
                    visited = true,
                    source = visitedOn[site.id]?.let { walkedAgoLabel(day - it) } ?: "walked"
                )
                site.id in rumorPins -> pins += bearingTo(site).copy(source = "rumor")
                site.id in knownPins -> pins += bearingTo(site).copy(source = "the roads")
            }
        }
        return pins.sortedBy { it.leagues } + stood.sortedBy { it.leagues }
    }

    private fun bearingTo(site: Site): Bearing {
        val dx = site.x * (overland.width - 1) + 0.5f - camera.x
        val dy = site.y * (overland.height - 1) + 0.5f - camera.y
        val leagues = sqrt(dx * dx + dy * dy) * OverlandGen.LEAGUES_PER_CELL
        // rain slows the leagues: the hours a walk asks swell with the weather
        val walkFactor = if (outdoor) rainPacing(weather.rain).first else 1f
        val settlement = site.isSettlement && !site.ruined
        return Bearing(
            site = site,
            compass = OverlandGen.windOf(dx, dy),
            leagues = leagues,
            hours = leagues / (2.1f * walkFactor),
            visited = site.id in visitedSites,
            source = "",
            folk = if (settlement) folkOf(site.id) else -1,
            stage = if (settlement) stageAt(site).label else ""
        )
    }

    /** Where the smoke of the nearest living settlement sits on the sky, on the open road. */
    fun skylineBearing(): Float? {
        if (!onOverland) return null
        val nearest = world.sites
            .filter { it.isSettlement && !it.ruined }
            .minByOrNull {
                MapFactory.distance(
                    it.x * (overland.width - 1), it.y * (overland.height - 1), camera.x, camera.y
                )
            } ?: return null
        val dx = nearest.x * (overland.width - 1) + 0.5f - camera.x
        val dy = nearest.y * (overland.height - 1) + 0.5f - camera.y
        return atan2(dy, dx)
    }

    private fun ageRumors(days: Float) {
        val whole = days.roundToInt()
        if (whole <= 0) return
        for (i in rumors.indices) {
            rumors[i] = rumors[i].copy(daysOld = rumors[i].daysOld + whole)
        }
        rumors.removeAll { it.daysOld > 60 }
    }

    fun revive() {
        dead = false
        vitality = (maxVitality * 0.45f).roundToInt()
        fatigue = (maxFatigue * 0.5f).roundToInt()
        minutes += 8 * 60f
        if (onOverland) {
            // Die in the open and the nearest living gates take you in.
            val nearest = world.sites
                .filter { it.isSettlement && !it.ruined }
                .minByOrNull {
                    MapFactory.distance(
                        it.x * (overland.width - 1), it.y * (overland.height - 1), camera.x, camera.y
                    )
                } ?: world.site(world.vaultSiteId)
            currentSiteId = nearest.id
            visitedSites += nearest.id
            visitedOn[nearest.id] = day
            camera.x = overland.entrySpots[nearest.id]?.first ?: overland.spawnX
            camera.y = overland.entrySpots[nearest.id]?.second ?: overland.spawnY
            camera.angle = -1.5708f
            torch = 0.6f
            brass = (brass / 2)
            deeds += "Was dragged out of the wild half-dead"
            pushLog("Bearers found you and carried you to ${nearest.name}. Half your brass paid for it.")
            return
        }
        depth = 0
        outdoor = true
        map = SiteGen.map(
            world, world.site(currentSiteId), 0, roster, geography, day, slainBeastIds(),
            folk = folkOf(currentSiteId)
        )
        surfaceMap = map
        inBuilding = null
        removeSlainResidents()
        camera.x = map.spawnX
        camera.y = map.spawnY
        camera.angle = map.spawnAngle
        torch = 0.6f
        brass = (brass / 2)
        deeds += "Was dragged out of ${siteName()} half-dead"
        pushLog("Someone dragged you out and took half your brass for the trouble.")
    }

    fun standingFor(power: Power): Int = reputation.standingFor(power.id)

    fun pushLog(text: String) {
        log += LogLine(text)
    }

    private fun encodeRumor(rumor: Rumor): String =
        listOf(rumor.text, rumor.source, "${rumor.daysOld}", "${rumor.siteId}", "${rumor.aboutPlayer}")
            .joinToString("\u001F")

    private fun decodeRumor(raw: String): Rumor? {
        val fields = raw.split("\u001F")
        if (fields.size < 5) return null
        return Rumor(
            text = fields[0],
            source = fields[1],
            daysOld = fields[2].toIntOrNull() ?: 0,
            aboutPlayer = fields[4] == "true",
            siteId = fields[3].toIntOrNull() ?: -1
        )
    }

    fun toSaveSlot(): SaveSlot = SaveSlot(
        seed = world.seed,
        day = day,
        minutes = minutes,
        outdoor = outdoor,
        depth = depth,
        x = camera.x,
        y = camera.y,
        angle = camera.angle,
        vitality = vitality,
        fatigue = fatigue,
        magicka = magicka,
        torch = torch,
        kills = kills,
        brass = brass,
        siteId = currentSiteId,
        siteName = siteName(),
        deeds = deeds,
        reputation = reputation.encode(),
        stats = stats.encode(),
        classKey = klass?.key ?: "",
        growth = growth.encode(),
        proficiencies = proficiencies.encode(),
        masteries = masteries.encode(),
        nextUid = ItemUids.current(),
        inventory = inventory.encode(),
        equipment = equipment.encode(),
        dropped = groundItems.joinToString("\u001E") { it.encode() },
        looted = lootedContainers.joinToString("\u001E"),
        onOverland = onOverland,
        settlements = settlements.encode(),
        visited = visitedSites.joinToString("\u001F"),
        rumors = rumors.joinToString("\u001E") { encodeRumor(it) }
    )
}
