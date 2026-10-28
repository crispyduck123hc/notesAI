package com.example.notesai.update

/**
 * A version number ordered the way semantic versioning orders them.
 *
 * Only what the app and its release tags actually contain is supported: `MAJOR.MINOR.PATCH` with an
 * optional pre-release suffix. The point of the type is one decision — *is the release newer than
 * what we are running* — and getting that wrong either nags someone about the version they already
 * have or stays silent when a fix exists, so the comparison is spelled out and tested rather than
 * done with string equality.
 *
 * Precedence follows semver: `1.2.1-beta` < `1.2.1` < `1.2.2` < `1.3.0-beta`.
 */
internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Identifiers after the `-`, empty for a final release. */
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
            .takeIf { difference -> difference != 0 }
            ?: comparePreRelease(this, other)

    /** True when this version is genuinely newer than [other]. */
    fun isNewerThan(other: SemanticVersion): Boolean = compareTo(other) > 0

    companion object {
        /**
         * Parses `1.2.1`, `v1.2.1`, `1.2.1-beta.2` or `1.2.1-beta.2+build.5`. Returns null for
         * anything else, so an unparseable tag is ignored rather than treated as newer.
         */
        fun parseOrNull(text: String): SemanticVersion? {
            val trimmed = text.trim().removePrefix("v").removePrefix("V")

            // Build metadata carries no precedence, so it is dropped before the suffix is read.
            val withoutBuild = trimmed.substringBefore('+')
            val numbers = withoutBuild.substringBefore('-').split('.').map { it.toIntOrNull() }
            val parsed = numbers.takeIf { trimmed.isNotEmpty() && it.size == 3 && it.all { n -> n != null && n >= 0 } }
            val preRelease = withoutBuild.substringAfter('-', "").split('.').filter { it.isNotEmpty() }

            return parsed?.let {
                // Non-null by the check above; spelled out rather than defaulted so a hole in that
                // check could never silently become version 0.0.0.
                SemanticVersion(
                    major = requireNotNull(it[0]),
                    minor = requireNotNull(it[1]),
                    patch = requireNotNull(it[2]),
                    preRelease = preRelease,
                )
            }
        }

        /** A final release outranks every pre-release of the same version. */
        private fun comparePreRelease(
            left: SemanticVersion,
            right: SemanticVersion,
        ): Int =
            when {
                left.preRelease.isEmpty() && right.preRelease.isEmpty() -> 0
                left.preRelease.isEmpty() -> 1
                right.preRelease.isEmpty() -> -1
                else -> compareIdentifiers(left.preRelease, right.preRelease)
            }

        private fun compareIdentifiers(
            left: List<String>,
            right: List<String>,
        ): Int {
            for (index in 0 until minOf(left.size, right.size)) {
                val difference = compareIdentifier(left[index], right[index])
                if (difference != 0) return difference
            }
            return left.size - right.size
        }

        /** Numeric identifiers compare numerically and rank below words: `beta.2` < `beta.10`. */
        private fun compareIdentifier(
            left: String,
            right: String,
        ): Int {
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            return when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
    }
}
