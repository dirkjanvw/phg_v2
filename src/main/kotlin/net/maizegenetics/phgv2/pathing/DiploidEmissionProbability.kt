package net.maizegenetics.phgv2.pathing

import net.maizegenetics.phgv2.api.HaplotypeGraph
import net.maizegenetics.phgv2.api.ReferenceRange
import net.maizegenetics.phgv2.api.SampleGamete
import org.apache.commons.math3.distribution.BinomialDistribution
import kotlin.math.ln
import kotlin.math.pow

/**
 * Calculates the natural log (ln) of the emission probability for a pair of haplotypes.
 */
class DiploidEmissionProbability(val readMap: Map<ReferenceRange, Map<List<String>, Int>>, val graph: HaplotypeGraph, val probabilityCorrect: Double) {
    private var myCurrentRange = ReferenceRange("NA", 0, 0)
    //a map of a pair of haplotypes -> ln probability
    private var rangeLnProbabilities = mapOf<UnorderedHaplotypePair, Double>()
    //a map of SampleGamete -> haplotype for myCurrentRange
    private var sampleToHaplotype = mapOf<SampleGamete, String>()
    private var defaultProbability = ln(1e-10)
    private val sampleGametesInGraph = graph.sampleGametesInGraph()
    private val noReadCountProbability = 0.0
    private var rangeHasReads = true

    /**
     * Returns the natural log of the emission probability for a given state and [ReferenceRange].
     * The emission probability is the probability of observing the read counts given that the generating sample carries
     * the haplotypes of the SampleGametes. The state is a pair of SampleGametes.
     *
     * If the range has no reads that mapped to it, then all SampleGamete pairs have the same probability. So,
     * return noReadCountProbability when there are no reads. Since the emission probability only needs to be
     * proportional to the probability of the observations (read counts) given the state, returning 0.0 for all pairs works fine.
     */
    fun lnProbObsGivenState(state: Pair<SampleGamete, SampleGamete>, refrange: ReferenceRange): Double {

        if (myCurrentRange != refrange) {
            myCurrentRange = refrange
            val readsForRange = readMap[refrange]
            rangeHasReads = if (readsForRange == null) false else readsForRange.values.sum() > 0
            if (rangeHasReads) assignRangeProbabilities()
        }

        if (!rangeHasReads) return noReadCountProbability

        val haplotypePair = Pair(sampleToHaplotype[state.first], sampleToHaplotype[state.second])

        //convert the pair of sample gametes to an UnorderedHaplotypePair
        val unorderedHaplotypes = UnorderedHaplotypePair(haplotypePair)
        return rangeLnProbabilities[unorderedHaplotypes] ?: defaultProbability
    }

    /**
     * Generates a map of all possible SampleGamete order pairs to their emission probabilities. This is done
     * once per reference range to avoid repeating the calculations.
     */
    private fun assignRangeProbabilities() {
        sampleToHaplotype = graph.sampleGameteToHaplotypeId(myCurrentRange)

        val haplotypesInRefrange = sampleToHaplotype.values.toSet().toList()

        val readCounts = readMap[myCurrentRange] ?: mapOf()

        require(readCounts.isNotEmpty()) {"No haplotypes in $myCurrentRange"}
        val readSetCounts = readCounts.mapKeys { (haplist, _) -> haplist.toSet() }

        val probabilityMap = mutableMapOf<UnorderedHaplotypePair, Double>()
        for (ndx1 in haplotypesInRefrange.indices) {
            for (ndx2 in ndx1 until haplotypesInRefrange.size) {
                val haplotypePair = UnorderedHaplotypePair(Pair(haplotypesInRefrange[ndx1], haplotypesInRefrange[ndx2]))
                probabilityMap[haplotypePair] = ln(haplotypePairProbability(haplotypePair, readSetCounts))
            }
        }

        //if there are any null haplotypes in this reference range add pairs for each (haplotype,null) and (null,null)
        val anyNullHaplotypes = sampleGametesInGraph.any { sampleToHaplotype[it] == null }
        if (anyNullHaplotypes) {
            for (haplotype in haplotypesInRefrange) {
                val haplotypePair = UnorderedHaplotypePair(Pair(haplotype, null))
                probabilityMap[haplotypePair] = ln(haplotypePairProbability(haplotypePair, readSetCounts))
            }
            val haplotypePair = UnorderedHaplotypePair(Pair(null, null))
            probabilityMap[haplotypePair] = ln(haplotypePairProbability(haplotypePair, readSetCounts))
        }

        //take the natural log of the probabilities
        rangeLnProbabilities = probabilityMap
    }

    /**
     * Calculates the emission probability for a pair of haplotypes. The emission probability is the probability of
     * observing this particular set of read counts given that they were generated by this haplotype pair.
     * UnorderedHaplotypePair is used because the order of the haplotypes does not matter.
     */
    private fun haplotypePairProbability(haplotypes: UnorderedHaplotypePair, readCounts: Map<Set<String>, Int>): Double {

        val halfProb = probabilityCorrect / 2
        val pErr = 1 - probabilityCorrect

        //readCounts keys are a Set rather than a list for faster contains method
        val hapPair = haplotypes.haplotypePair
        return if ( hapPair.first != null && (hapPair.second == hapPair.first || hapPair.second == null)) {
            //If the genotype is homozygous or one of the haplotypes is a null haplotype, then the probability of
            // observing these read counts is the same as the haploid genotype case.
            // That is P(obs|homozygous genotype) = Binom(totalCount, # of reads with this haplotype, probabilityCorrect).
            // In words, it is the probability of observing m reads that map to this haplotype out of total of n reads
            // when the probability of a success equals probabilityCorrect
            val totalCount = readCounts.values.sum()
            val firstCount = readCounts.filter { (hapset, _) -> hapset.contains(hapPair.first) }.values.sum()
            BinomialDistribution(totalCount, probabilityCorrect).probability(firstCount)
        } else if (hapPair.first == null && hapPair.second != null) {
            //the same case as the first if. This could have been included in that condition but this is
            // easier to understand. (and code correctly)
            val totalCount = readCounts.values.sum()
            val firstCount = readCounts.filter { (hapset, _) -> hapset.contains(hapPair.second) }.values.sum()
            BinomialDistribution(totalCount, probabilityCorrect).probability(firstCount)
        } else {
            //firstAndSecondCount is the number of reads mapping to both haplotype. firstNotSecondCount is
            // the number of reads mapping to the first but not the second, etc. Note that these classes are mutually
            // exclusive and that they sum to the total counts.
            val firstNotSecondCount = readCounts.filter { (hapset, _) -> hapset.contains(hapPair.first) && !hapset.contains(hapPair.second)}
                .values.sum()
            val secondNotFirstCount = readCounts.filter { (hapset, _) -> !hapset.contains(hapPair.first) && hapset.contains(hapPair.second)}
                .values.sum()
            val firstAndSecondCount = readCounts.filter { (hapset, _) -> hapset.contains(hapPair.first) && hapset.contains(hapPair.second)}
                .values.sum()
            val neitherFirstNorSecondCount = readCounts.filter { (hapset, _) -> !hapset.contains(hapPair.first) && !hapset.contains(hapPair.second)}
                .values.sum()

            //Explanation of the following algorithm:
            // From the definition of emission probability, it is given that the reads came from this haplotype pair.
            // Then all of the reads should map to either or both of the haplotypes and P(the read mapping to neither) = pErr,
            // that is the probability that a read is miss-mapped. A single read that maps to both haplotypes could
            // have been generated by either. If we knew that exactly m1 reads came from the first haplotype,
            // m2 reads came from the second, and m3 reads mapped to neither, then the emission probability equals
            // multinomial([m1, m2, m3], [halfProb, halfProb, pErr]).  But when some of the reads map to both,
            // then we have to enumerate all the possible combinations of m1, and m2 and calculate and sum
            // the resulting multinomial probabilities.

            (0..firstAndSecondCount).sumOf {
                multinomialProbability(
                    intArrayOf(
                        firstNotSecondCount + it,
                        secondNotFirstCount + firstAndSecondCount - it,
                        neitherFirstNorSecondCount
                    ),
                    doubleArrayOf(halfProb, halfProb, pErr)
                )
            }
        }

    }

    /**
     * @param [counts] The counts of each of set of classes
     * @param [probabilities] The probability of each class
     * @return The probability of an array of counts of some classes given the probability of each class,
     * The size of the counts array and the probabilities array are expected to be equal.
     */
    private fun multinomialProbability(counts: IntArray, probabilities: DoubleArray): Double {
        if (counts.size != probabilities.size) throw java.lang.IllegalArgumentException("multinomialProbability error: counts and probabilities arrays do not have the same size.")
        val totalCount = counts.sum()

        val logprod = counts.indices.sumOf { counts[it] * ln(probabilities[it]) }
        val numerator = logFactorial(totalCount)
        val denom = counts.sumOf { logFactorial(it) }
        val logprob = numerator - denom + logprod
        return Math.E.pow(logprob)
    }

    /**
     *  Calculates the log factorial of any positive integer using the exact value for 0 to 10 and
     *  Stirlings approximation for integers greater than 10. The formula is taken from the
     *  Wikipedia article for Stirlings approximation
     *  @param [intval] an integer
     *  @return   the natural log of the factorial of intval
     */
    private fun logFactorial(intval: Int): Double {
        return if (intval <= 10) smallFactorials[intval] else {
            val n = intval.toDouble()
            n * ln(n) + 0.5 * ln(2.0 * Math.PI * n) - n
        }
    }

    //factorials of 0 to 10
    private val smallFactorials: DoubleArray = doubleArrayOf(1.0, 1.0, 2.0, 6.0, 24.0, 120.0, 720.0, 5040.0, 40320.0, 362880.0, 3628800.0)
        .map { ln(it) }.toDoubleArray()


    data class UnorderedHaplotypePair(val haplotypePair: Pair<String?, String?>) {
        /**
         * Kotlin's equality: null == null is true. So, equal works with null haplotypes as intended here.
         */
        override fun equals(other: Any?): Boolean {
            return if (other is UnorderedHaplotypePair) {
                if (other.haplotypePair == haplotypePair) true
                else if (other.haplotypePair.first == haplotypePair.second && other.haplotypePair.second == haplotypePair.first) true
                else false
            }
            else false
        }

        override fun hashCode(): Int {
            return haplotypePair.first.hashCode() + haplotypePair.second.hashCode()
        }
    }
}

