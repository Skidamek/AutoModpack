import org.gradle.api.GradleException

/** A manifest cover: an exact version (`1.20.1`, matching only it) or a tilde patch line (`~26.3`, matching everything from the base below its bumped minor - the author's claim that the whole line rides one impl). */
data class Cover(val tilde: Boolean, val parts: List<Long>) {
    fun intersects(other: Cover): Boolean = when {
        !tilde && !other.tilde -> compare(parts, other.parts) == 0
        !tilde -> other.contains(parts)
        !other.tilde -> contains(other.parts)
        else -> compare(parts, other.upperBound()) < 0 && compare(other.parts, upperBound()) < 0
    }

    private fun contains(candidate: List<Long>): Boolean = compare(candidate, parts) >= 0 && compare(candidate, upperBound()) < 0

    /** The tilde's exclusive end - the second-to-last component bumped and the last dropped (`~26.3.1` ends at `26.4`), the base padded to three components first. */
    fun upperBound(): List<Long> {
        val padded = if (parts.size < 3) parts + List(3 - parts.size) { 0L } else parts
        return padded.dropLast(1).toMutableList().also { it[it.lastIndex] += 1 }
    }

    private fun compare(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0L }
            val right = b.getOrElse(i) { 0L }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        private val SHAPE = Regex("~?\\d+(\\.\\d+){1,3}")

        fun parse(cover: String): Cover {
            if (!SHAPE.matches(cover)) throw GradleException("Cover $cover is neither an exact Minecraft version nor a ~ patch line")
            return Cover(cover.startsWith("~"), cover.removePrefix("~").split('.').map { it.toLong() })
        }
    }
}
