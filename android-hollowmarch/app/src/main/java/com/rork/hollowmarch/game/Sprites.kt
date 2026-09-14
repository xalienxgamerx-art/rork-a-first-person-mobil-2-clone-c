package com.rork.hollowmarch.game

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** A flat, camera-facing billboard, painted once at startup like a 1996 sprite sheet. */
class Sprite(val w: Int, val h: Int, val px: IntArray)

object Sprites {
    const val HUSK = 0
    const val HOUND = 1
    const val WRAITH = 2
    const val REEDS = 3
    const val DEAD_TREE = 4
    const val STANDING_STONE = 5
    const val GRAVE = 6
    const val BRAZIER = 7
    const val HELD_SWORD = 8
    const val HELD_TORCH = 9
    const val URN = 10
    const val ITEM = 11
    const val CORPSE = 12
    const val TENT = 13
    const val PINE = 14
    const val CAIRN = 15
    const val HILLDOOR = 16
    const val WATCHFIRE = 17
    const val PILGRIM = 18
    const val COUNT = 19

    private lateinit var sheet: Array<Sprite>
    private var built = false

    fun ensureBuilt() {
        if (built) return
        val rng = Random(770411L)
        sheet = Array(COUNT) { id ->
            when (id) {
                HUSK -> husk(rng)
                HOUND -> hound(rng)
                WRAITH -> wraith(rng)
                REEDS -> reeds(rng)
                DEAD_TREE -> deadTree(rng)
                STANDING_STONE -> standingStone(rng)
                GRAVE -> grave(rng)
                BRAZIER -> brazier(rng)
                HELD_SWORD -> heldSword(rng)
                URN -> urn(rng)
                ITEM -> itemBundle(rng)
                CORPSE -> corpse(rng)
                TENT -> tent(rng)
                PINE -> pine(rng)
                CAIRN -> cairn(rng)
                HILLDOOR -> hilldoor(rng)
                WATCHFIRE -> watchfire(rng)
                PILGRIM -> pilgrim(rng)
                else -> heldTorch(rng)
            }
        }
        built = true
    }

    operator fun get(id: Int): Sprite = sheet[id.coerceIn(0, COUNT - 1)]

    private class Painter(val w: Int, val h: Int) {
        val px = IntArray(w * h)

        fun put(x: Int, y: Int, color: Int) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = color or (0xFF shl 24)
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, rng: Random, grain: Int = 22) {
            for (y in y0..y1) for (x in x0..x1) {
                put(x, y, jitter(color, rng, grain))
            }
        }

        fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, color: Int, rng: Random, grain: Int = 22) {
            for (y in (cy - ry)..(cy + ry)) {
                for (x in (cx - rx)..(cx + rx)) {
                    val dx = (x - cx).toFloat() / rx
                    val dy = (y - cy).toFloat() / ry
                    if (dx * dx + dy * dy <= 1f) {
                        val lit = 1f - 0.35f * ((dx + 0.4f).coerceIn(-1f, 1f))
                        put(x, y, jitter(scale(color, lit), rng, grain))
                    }
                }
            }
        }

        fun limb(x0: Int, y0: Int, x1: Int, y1: Int, thick: Int, color: Int, rng: Random) {
            val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
            for (i in 0..steps) {
                val x = x0 + (x1 - x0) * i / steps
                val y = y0 + (y1 - y0) * i / steps
                for (ty in -thick / 2..thick / 2) for (tx in -thick / 2..thick / 2) {
                    put(x + tx, y + ty, jitter(color, rng, 18))
                }
            }
        }

        fun build(): Sprite = Sprite(w, h, px)
    }

    private fun jitter(color: Int, rng: Random, amount: Int): Int {
        val d = rng.nextInt(amount) - amount / 2
        val r = (((color shr 16) and 0xFF) + d).coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) + d).coerceIn(0, 255)
        val b = ((color and 0xFF) + d).coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun scale(color: Int, f: Float): Int {
        val r = ((((color shr 16) and 0xFF) * f).roundToInt()).coerceIn(0, 255)
        val g = ((((color shr 8) and 0xFF) * f).roundToInt()).coerceIn(0, 255)
        val b = (((color and 0xFF) * f).roundToInt()).coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun husk(rng: Random): Sprite {
        val p = Painter(48, 72)
        val skin = 0x8D7C61
        p.limb(19, 44, 16, 70, 7, scale(skin, 0.75f), rng)
        p.limb(29, 44, 33, 70, 7, scale(skin, 0.7f), rng)
        p.ellipse(24, 34, 11, 15, skin, rng)
        p.limb(14, 26, 6, 46, 5, scale(skin, 0.7f), rng)
        p.limb(34, 26, 43, 42, 5, scale(skin, 0.8f), rng)
        p.ellipse(24, 13, 8, 9, scale(skin, 1.05f), rng)
        // sunken sockets and a slack jaw
        p.rect(20, 11, 22, 13, 0x140F0B, rng, 6)
        p.rect(26, 11, 28, 13, 0x140F0B, rng, 6)
        p.rect(23, 17, 26, 20, 0x1A120D, rng, 6)
        // tar-marks of the cult that raised it
        p.rect(19, 6, 29, 8, 0x241A12, rng, 10)
        // old wounds
        for (i in 0 until 14) {
            val x = 14 + rng.nextInt(20)
            val y = 24 + rng.nextInt(24)
            p.rect(x, y, x + 1 + rng.nextInt(2), y + 1, 0x6B2A1E, rng, 12)
        }
        return p.build()
    }

    private fun hound(rng: Random): Sprite {
        val p = Painter(56, 44)
        val hide = 0x4A3B2A
        p.ellipse(28, 22, 17, 9, hide, rng)
        p.limb(16, 28, 13, 42, 5, scale(hide, 0.8f), rng)
        p.limb(24, 28, 24, 42, 5, scale(hide, 0.75f), rng)
        p.limb(36, 28, 37, 42, 5, scale(hide, 0.8f), rng)
        p.limb(43, 28, 46, 42, 5, scale(hide, 0.75f), rng)
        p.ellipse(11, 17, 8, 6, scale(hide, 1.1f), rng)
        p.rect(3, 16, 9, 19, scale(hide, 0.9f), rng)
        p.rect(5, 15, 6, 16, 0xA33B28, rng, 4)
        p.limb(45, 18, 54, 12, 3, scale(hide, 0.7f), rng)
        return p.build()
    }

    private fun wraith(rng: Random): Sprite {
        val p = Painter(48, 76)
        val cloth = 0x2B2C33
        for (y in 8 until 76) {
            val halfWidth = (6 + (y - 8) * 0.22f).toInt()
            val fade = if (y > 62) (76 - y) / 14f else 1f
            for (x in (24 - halfWidth)..(24 + halfWidth)) {
                if (rng.nextFloat() > fade) continue
                val wave = Textures.valueNoise(x * 0.3f, y * 0.12f, 11)
                p.put(x, y, jitter(scale(cloth, 0.7f + wave * 0.6f), rng, 14))
            }
        }
        p.ellipse(24, 16, 9, 10, 0x191A1F, rng, 8)
        p.rect(20, 14, 22, 16, 0x4E7A6B, rng, 8)
        p.rect(26, 14, 28, 16, 0x4E7A6B, rng, 8)
        return p.build()
    }

    private fun reeds(rng: Random): Sprite {
        val p = Painter(44, 56)
        for (i in 0 until 26) {
            val baseX = 4 + rng.nextInt(36)
            val height = 22 + rng.nextInt(30)
            val lean = rng.nextInt(7) - 3
            val color = if (rng.nextInt(3) == 0) 0x6A6440 else 0x3E4A2E
            p.limb(baseX, 55, baseX + lean, 55 - height, 2, color, rng)
        }
        return p.build()
    }

    private fun deadTree(rng: Random): Sprite {
        val p = Painter(64, 96)
        val bark = 0x2E271D
        p.limb(32, 95, 30, 46, 9, bark, rng)
        p.limb(30, 60, 14, 40, 5, bark, rng)
        p.limb(30, 54, 48, 32, 5, bark, rng)
        p.limb(14, 40, 6, 28, 3, bark, rng)
        p.limb(48, 32, 58, 20, 3, bark, rng)
        p.limb(30, 46, 33, 18, 4, bark, rng)
        p.limb(33, 24, 22, 12, 3, bark, rng)
        return p.build()
    }

    private fun standingStone(rng: Random): Sprite {
        val p = Painter(40, 72)
        for (y in 10 until 72) {
            val halfWidth = (13 - (y - 10) * 0.02f).toInt()
            for (x in (20 - halfWidth)..(20 + halfWidth)) {
                val n = Textures.valueNoise(x * 0.2f, y * 0.2f, 5)
                p.put(x, y, jitter(scale(0x5B5648, 0.7f + n * 0.5f), rng, 16))
            }
        }
        p.rect(15, 26, 25, 28, 0x2A2620, rng, 8)
        p.rect(19, 26, 21, 44, 0x2A2620, rng, 8)
        return p.build()
    }

    private fun grave(rng: Random): Sprite {
        val p = Painter(36, 44)
        p.ellipse(18, 16, 12, 14, 0x4C4638, rng)
        p.rect(6, 16, 30, 42, 0x4C4638, rng)
        p.rect(11, 12, 25, 14, 0x24211B, rng, 8)
        p.rect(11, 20, 25, 22, 0x24211B, rng, 8)
        p.rect(11, 28, 22, 30, 0x24211B, rng, 8)
        return p.build()
    }

    /** The blade in your right hand, seen down its own length. */
    private fun heldSword(rng: Random): Sprite {
        val p = Painter(96, 128)
        val steel = 0x9AA1A6
        // blade running up and to the left, foreshortened
        for (i in 0 until 120) {
            val t = i / 120f
            val x = (74 - t * 58).toInt()
            val y = (108 - t * 104).toInt()
            val halfWidth = (7 - t * 4).toInt().coerceAtLeast(2)
            for (dx in -halfWidth..halfWidth) {
                val edge = abs(dx).toFloat() / halfWidth
                val shade = if (dx < 0) 1.15f - edge * 0.3f else 0.62f + edge * 0.15f
                p.put(x + dx, y, jitter(scale(steel, shade), rng, 12))
            }
        }
        // crossguard, grip and gauntlet
        p.limb(62, 112, 92, 100, 7, 0x6B5A34, rng)
        p.limb(74, 116, 88, 126, 9, 0x3E3225, rng)
        p.ellipse(80, 122, 14, 11, 0x4A3B2A, rng)
        p.ellipse(84, 118, 5, 4, 0x5A4833, rng)
        return p.build()
    }

    /** The torch in your left hand: the only honest light in the vault. */
    private fun heldTorch(rng: Random): Sprite {
        val p = Painter(72, 128)
        p.limb(30, 127, 38, 62, 11, 0x3A2C1D, rng)
        p.rect(24, 54, 46, 66, 0x4A3722, rng)
        for (y in 4 until 58) {
            val spread = ((58 - y) * 0.30f).toInt() + 3
            for (x in (35 - spread)..(35 + spread)) {
                val n = Textures.valueNoise(x * 0.35f, y * 0.28f, 13)
                if (n < 0.38f) continue
                val hot = ((58 - y) / 54f).coerceIn(0f, 1f)
                val color = when {
                    hot > 0.72f -> 0xF6DA96.toInt()
                    hot > 0.45f -> 0xE8A93C.toInt()
                    hot > 0.22f -> 0xC8702A.toInt()
                    else -> 0x8C3A20
                }
                p.put(x, y, jitter(color, rng, 30))
            }
        }
        return p.build()
    }

    private fun brazier(rng: Random): Sprite {
        val p = Painter(32, 64)
        p.rect(14, 30, 18, 63, 0x3A2E1F, rng)
        p.ellipse(16, 28, 10, 5, 0x5A4A2E, rng)
        for (y in 4 until 28) {
            val spread = ((28 - y) * 0.35f).toInt() + 2
            for (x in (16 - spread)..(16 + spread)) {
                val n = Textures.valueNoise(x * 0.4f, y * 0.3f, 3)
                if (n < 0.35f) continue
                val hot = ((28 - y) / 24f).coerceIn(0f, 1f)
                val color = if (hot > 0.6f) 0xF2C55A.toInt() else if (hot > 0.3f) 0xD98A2B.toInt() else 0xA33B28
                p.put(x, y, jitter(color, rng, 26))
            }
        }
        return p.build()
    }

    /** A burial urn: clay, lidded, holding what the living left with the dead. */
    private fun urn(rng: Random): Sprite {
        val p = Painter(28, 36)
        val clay = 0x6B5A3E
        p.ellipse(14, 24, 9, 10, clay, rng)
        p.rect(9, 10, 19, 16, clay, rng)
        p.ellipse(14, 10, 5, 3, 0x2A231A, rng)
        p.limb(4, 20, 9, 17, 2, clay, rng)
        p.limb(24, 20, 19, 17, 2, clay, rng)
        p.rect(8, 18, 20, 20, 0x4C3E28, rng, 8)
        return p.build()
    }

    /** A dropped thing: one humble bundle looks much like another on the floor. */
    private fun itemBundle(rng: Random): Sprite {
        val p = Painter(26, 18)
        val cloth = 0x5C4A34
        p.ellipse(13, 11, 9, 6, cloth, rng)
        p.rect(11, 4, 15, 8, scale(cloth, 0.85f), rng)
        p.limb(4, 12, 22, 9, 2, 0x3E3225, rng)
        p.rect(12, 2, 14, 5, 0x8C6F3A, rng, 10)
        return p.build()
    }

    /** A warband's shelter: stained canvas over a dark mouth. */
    private fun tent(rng: Random): Sprite {
        val p = Painter(56, 52)
        val canvas = 0x5C5140
        // two sloping faces meeting at a ridge
        for (i in 0 until 40) {
            val t = i / 40f
            val halfWidth = (3 + t * 23).toInt()
            for (dx in -halfWidth..halfWidth) {
                val shade = 0.6f + 0.55f * (1f - abs(dx).toFloat() / (halfWidth + 1))
                p.put(28 + dx, 8 + (40 * t).toInt(), jitter(scale(canvas, shade), rng, 16))
            }
        }
        // the dark mouth, and the poles that hold the cloth up
        p.rect(22, 36, 34, 50, 0x1A150F, rng, 6)
        p.limb(6, 50, 28, 8, 2, scale(canvas, 0.8f), rng)
        p.limb(50, 50, 28, 8, 2, scale(canvas, 0.85f), rng)
        p.limb(28, 8, 28, 2, 2, 0x3E3225, rng)
        return p.build()
    }

    /** A living pine: dark boughs stacked to a point, the forest's own roof. */
    private fun pine(rng: Random): Sprite {
        val p = Painter(56, 110)
        val bark = 0x33291C
        p.limb(28, 109, 28, 58, 7, bark, rng)
        // stacked cones of boughs, darker toward the heartwood
        for (ring in 0 until 5) {
            val topY = 6 + ring * 18
            val halfWidth = (4 + ring * 7).toInt().coerceAtMost(24)
            val tone = 0.72f + ring * 0.09f
            for (y in topY until topY + 26) {
                val t = (y - topY) / 26f
                val half = (6 + halfWidth * t).toInt().coerceAtMost(26)
                for (x in (28 - half)..(28 + half)) {
                    val n = Textures.valueNoise(x * 0.3f, y * 0.3f, ring + 61)
                    val shade = if (x < 28) 0.75f else 1f
                    p.put(x, y, jitter(scale(0x24351E, tone * shade * (0.8f + n * 0.4f)), rng, 14))
                }
            }
        }
        return p.build()
    }

    /** A pile of stones raised over the dead: the barrow's marker in the open country. */
    private fun cairn(rng: Random): Sprite {
        val p = Painter(44, 54)
        val stone = 0x55503F
        p.ellipse(22, 44, 20, 9, scale(stone, 0.8f), rng)
        for (tier in 0 until 4) {
            val halfWidth = 16 - tier * 4
            val y0 = 38 - tier * 9
            for (y in (y0 - 9)..y0) {
                for (x in (22 - halfWidth)..(22 + halfWidth)) {
                    val n = Textures.valueNoise(x * 0.4f, y * 0.4f, 67)
                    p.put(x, y, jitter(scale(stone, 0.7f + n * 0.55f), rng, 16))
                }
            }
        }
        return p.build()
    }

    /** A door cut into a hillside: linteled, dark, and shut on the living. */
    private fun hilldoor(rng: Random): Sprite {
        val p = Painter(52, 74)
        val turf = 0x3E3A28
        // the mound the door is cut into
        for (y in 0 until 74) {
            val halfWidth = ((y.toFloat() / 74f) * 26).toInt().coerceAtLeast(10)
            for (x in (26 - halfWidth)..(26 + halfWidth)) {
                val n = Textures.valueNoise(x * 0.2f, y * 0.2f, 71)
                p.put(x, y, jitter(scale(turf, 0.7f + n * 0.5f), rng, 14))
            }
        }
        // the door itself: a dark trapezoid with a stone frame
        for (y in 18 until 72) {
            val t = (y - 18) / 54f
            val half = (4 + t * 9).toInt()
            for (x in (26 - half)..(26 + half)) {
                p.put(x, y, jitter(0x120E0A, rng, 6))
            }
        }
        for (y in 14 until 72) {
            val t = (y - 14) / 58f
            val half = (5 + t * 10).toInt()
            p.put(26 - half, y, jitter(0x4C4638, rng, 10))
            p.put(26 + half, y, jitter(0x4C4638, rng, 10))
        }
        for (x in (26 - 16)..(26 + 16)) p.put(x, 15, jitter(0x5A5442, rng, 10))
        // brass studs of the sealing, long since sprung
        p.put(22, 30, 0xC8952F)
        p.put(30, 30, 0x8C6F3A)
        p.put(26, 40, 0xA3812F)
        return p.build()
    }

    /** A warband's fire in the open: stones, logs, and a lean of flame. */
    private fun watchfire(rng: Random): Sprite {
        val p = Painter(40, 48)
        val ring = 0x4C4438
        p.ellipse(20, 42, 16, 5, ring, rng)
        for (i in 0 until 9) {
            val a = i * 6.28f / 9
            p.put(20 + (kotlin.math.cos(a) * 14).toInt(), 42 + (kotlin.math.sin(a) * 4).toInt(), jitter(0x5C5444, rng, 12))
        }
        p.limb(12, 42, 28, 38, 4, 0x2E261A, rng)
        p.limb(12, 38, 28, 42, 4, 0x332A1D, rng)
        for (y in 4 until 40) {
            val spread = ((40 - y) * 0.28f).toInt() + 2
            for (x in (20 - spread)..(20 + spread)) {
                val n = Textures.valueNoise(x * 0.4f, y * 0.3f, 73)
                if (n < 0.34f) continue
                val hot = ((40 - y) / 36f).coerceIn(0f, 1f)
                val color = when {
                    hot > 0.7f -> 0xF6DA96.toInt()
                    hot > 0.42f -> 0xE8A93C.toInt()
                    hot > 0.2f -> 0xC8702A.toInt()
                    else -> 0x8C3A20
                }
                p.put(x, y, jitter(color, rng, 26))
            }
        }
        return p.build()
    }

    /** A soul on the road: hooded, robed, staff in hand, asking nothing. */
    private fun pilgrim(rng: Random): Sprite {
        val p = Painter(44, 84)
        val robe = 0x5C5140
        // a hooded robe, hem swaying
        for (y in 20 until 84) {
            val t = (y - 20) / 64f
            val half = (7 + t * 9).toInt()
            for (x in (22 - half)..(22 + half)) {
                val n = Textures.valueNoise(x * 0.3f, y * 0.2f, 79)
                val shade = if (x < 22) 0.72f else 1f
                p.put(x, y, jitter(scale(robe, shade * (0.8f + n * 0.35f)), rng, 14))
            }
        }
        // hood and the shadow within it
        p.ellipse(22, 16, 8, 9, scale(robe, 1.08f), rng)
        p.ellipse(22, 18, 5, 6, 0x181410, rng, 8)
        // belt and satchel
        p.rect(15, 38, 29, 41, 0x3E3225, rng, 10)
        p.ellipse(30, 46, 5, 6, 0x4C3E28, rng)
        // the staff, and a hand on it
        p.limb(34, 10, 36, 82, 3, 0x33291C, rng)
        p.limb(27, 44, 34, 42, 4, scale(robe, 1.05f), rng)
        return p.build()
    }

    /** What is left of anyone: the same sprawled shape whatever they were. */
    private fun corpse(rng: Random): Sprite {
        val p = Painter(60, 26)
        val flesh = 0x7A6A52
        p.ellipse(30, 16, 20, 7, flesh, rng)
        p.ellipse(52, 12, 5, 4, scale(flesh, 1.05f), rng)
        p.limb(14, 18, 4, 22, 4, scale(flesh, 0.9f), rng)
        p.limb(30, 20, 34, 25, 4, scale(flesh, 0.9f), rng)
        p.limb(40, 20, 48, 24, 4, scale(flesh, 0.85f), rng)
        for (i in 0 until 10) {
            val x = 12 + rng.nextInt(36)
            val y = 12 + rng.nextInt(10)
            p.rect(x, y, x + 2, y + 1, 0x4A231A, rng, 10)
        }
        return p.build()
    }
}
