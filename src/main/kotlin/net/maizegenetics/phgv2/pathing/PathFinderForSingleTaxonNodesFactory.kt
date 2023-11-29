package net.maizegenetics.phgv2.pathing

import com.google.common.collect.HashMultiset
import com.google.common.collect.Multimap
import org.apache.logging.log4j.LogManager
import kotlin.math.E
import kotlin.math.log

class PathFinderForSingleTaxonNodesFactory  (graph: HaplotypeGraph,
                                             val sameGameteProbability: Double = 0.99,
                                             val probCorrect: Double = 0.99,
                                             val minTaxaPerRange: Int = 2,
                                             val minReadsPerRange: Int = 1,
                                             val maxReadsPerKB: Int = 1000,
                                             val removeEqual: Boolean = false,
                                             val inbreedCoef: Double = 0.0,
                                             val maxParents: Int = Int.MAX_VALUE,
                                             val minCoverage: Double = 1.0) {
    private val myLogger = LogManager.getLogger(PathFinderForSingleTaxonNodesFactory::class.java)
    val myGraph: HaplotypeGraph
    val useLikelyParents: Boolean
    val parentFinder: MostLikelyParents?

    init {
        //remove ranges that have fewer than minTaxaPerRange taxa
        //the input graph may contain missing taxa nodes
        //filtering on node.id() > 0 keeps these from being added to the taxa count used for filtering
        //TODO consider whether this filtering step is necessary

        var hapgraph = graph
        val rangesToRemove = graph.referenceRangeList().filter { refrange ->
            val taxaSet = graph.nodes(refrange).filter { node -> node.id() > 0 }.flatMap { it.taxaList() }.toSet()
            taxaSet.size < minTaxaPerRange
        }.toList()
        if (rangesToRemove.isNotEmpty()) hapgraph = FilterGraphPlugin(null, false).refRanges(rangesToRemove.toList())
            .filter(graph)

        myGraph = hapgraph
        myLogger.info("After filtering PathFinderForSingleTaxonNodesFactory.myGraph has ${myGraph.numberOfRanges()} ranges and ${myGraph.totalNumberTaxa()} taxa")

        //should the graph be filtered on likely parents?
        useLikelyParents = maxParents < myGraph.totalNumberTaxa() || minCoverage < 1.0
        parentFinder = if (useLikelyParents) MostLikelyParents(myGraph) else null
    }

    fun build(
        readMap: Multimap<ReferenceRange, HapIdSetCount>,
        parentList: String = ""
    ): PathFinderForSingleTaxonNodes {
        return PathFinderForSingleTaxonNodes(
            inputGraph = myGraph, readMap, probCorrect, sameGameteProbability,
            minReadsPerRange, maxReadsPerKB, removeEqual, inbreedCoef, parentList,
            useLikelyParents, parentFinder, maxParents, minCoverage
        )
    }

    class PathFinderForSingleTaxonNodes(
        inputGraph: HaplotypeGraph,
        val readMap: Multimap<ReferenceRange, HapIdSetCount>,
        val probCorrect: Double,
        val sameGameteProbability: Double,
        val minReadsPerRange: Int,
        val maxReadsPerKB: Int,
        val removeEqual: Boolean,
        val inbreedCoef: Double,
        parentList: String,
        useLikelyParents: Boolean,
        parentFinder: MostLikelyParents?,
        maxParents: Int,
        minCoverage: Double
    ) {


        private val myLogger = LogManager.getLogger(PathFinderForSingleTaxonNodes::class.java)

        private data class PathNode(
            val parent: PathNode?,
            val hapnode: HaplotypeNode?,
            val gamete: String,
            val totalProbability: Double
        )

        private data class DiploidPathNode(
            val parent: DiploidPathNode?,
            val hapnode: Pair<HaplotypeNode, HaplotypeNode>,
            val state: Int,
            val totalProbability: Double
        )

        private data class HapNode(val hapnode: HaplotypeNode, val emissionP: Double)

        private val graph: HaplotypeGraph
        private val totalNumberOfTaxa: Int
        val likelyParents: List<Pair<String, Int>>

        init {
            graph = if (useLikelyParents && parentFinder != null) {
                likelyParents = parentFinder.findMostLikelyParents(readMap, maxParents, minCoverage)
                val graphTaxa = TaxaListBuilder().addAll(likelyParents.map { it.first }).build()
                FilterGraphPlugin(null, false).taxaList(graphTaxa).filter(inputGraph)
            } else if (parentList.trim().length > 0) {
                likelyParents = listOf()
                FilterGraphPlugin(null, false).taxaList(parentList).filter(inputGraph)
            } else {
                likelyParents = listOf()
                inputGraph
            }

            totalNumberOfTaxa = graph.totalNumberTaxa()
        }

        fun findBestPath(): List<HaplotypeNode> {
            val haplotypeList = ArrayList<HaplotypeNode>(graph.numberOfRanges())
            graph.chromosomes().forEach { chr ->
                haplotypeList.addAll(viterbiOneGametePerNode(chr, readMap))
            }
            return haplotypeList
        }

        fun findBestDiploidPath(): List<List<HaplotypeNode>> {
            val haplotypeList = listOf(mutableListOf<HaplotypeNode>(), mutableListOf<HaplotypeNode>())
            graph.chromosomes().forEach { chr ->
                val start = System.nanoTime()
                val chrLists = diploidViterbi(chr, readMap)
                myLogger.info("Elapsed time for chr $chr: ${(System.nanoTime() - start) / 1e9} sec.")
                haplotypeList[0].addAll(chrLists[0])
                haplotypeList[1].addAll(chrLists[1])
            }
            return haplotypeList
        }

        /**
         * This method takes a graph and  a single chromosome and read mappings and returns a list of HaplotypeNodes that
         * represents the most likely path through the graph for that chromosome given the read mappings. The graph must have been constructed so that
         * there is only a single taxon for each node and all nodes have the same taxa. This condition is not checked because
         * the same graph may be used to process multiple samples. It is the responsibility of the calling code to ensure the condition
         * is satisfied.
         * @param chrom a Chromosome
         * @param readMap   a Multimap of read mappings for graph
         * @return  a list of HaplotypeNodes representing the most likely path
         */
        private fun viterbiOneGametePerNode(
            chrom: Chromosome,
            readMap: Multimap<ReferenceRange, HapIdSetCount>
        ): List<HaplotypeNode> {
            myLogger.info("Finding path for chromosome ${chrom.name} using ViterbiOneGametePerNode")
            val switchProbability = 1 - sameGameteProbability
            val rangeToNodesMap = graph.tree(chrom)
            val taxaSet = graph.taxaInGraph().map { it.name }.toSortedSet()
            val numberOfTaxa = taxaSet.size
            val nodesPerRange = numberOfTaxa
            val logSwitch = log(switchProbability / (nodesPerRange.toDouble() - 1.0), E)
            val logNoSwitch = log(1.0 - switchProbability, E)

            //diagnostic counters for discarded ranges:
            //countTooFewReads, countTooManyReadsPerKB, countReadsEqual, countDiscardedRanges
            val counters = IntArray(4) { 0 }

            //create emission probability
            val emissionProb = HaplotypeEmissionProbability(rangeToNodesMap, readMap, probCorrect)

            val rangeToNodesMapIter = rangeToNodesMap.entries.iterator()
            var rangeIndex = 0

            var initialEntry =
                rangeToNodesMapIter.next()  //entry key is ReferenceRange, entry value is List<HaplotypeNode>
            while (!useRange(
                    readMap[initialEntry.key],
                    counters,
                    initialEntry.key,
                    initialEntry.value
                ) && rangeToNodesMapIter.hasNext()
            ) {
                initialEntry = rangeToNodesMapIter.next()
                rangeIndex++
            }

            var paths = ArrayList<PathNode>()

            //make the initial PathNode for each node in the first range
            //the path nodes keep track of all the nodes on a path by holding a link to the parent node of each node
            //first need a map of taxon -> haplotype node, index

            val taxaMap = initialEntry.value.mapIndexed { index, haplotypeNode ->
                val probEmission = emissionProb.getLnProbObsGivenState(index, rangeIndex)
                haplotypeNode.taxaList().map { taxon -> Pair(taxon.name, HapNode(haplotypeNode, probEmission)) }
            }.flatten().toMap()

            //then for each taxon/gamete create a new path, handling taxa without haplotypes
            taxaSet.forEach { taxonName ->
                val myTaxonNode = taxaMap[taxonName]
                if (myTaxonNode == null) { //this taxon has no haplotype in this range
                    paths.add(PathNode(null, null, taxonName, emissionProb.getLnProbObsGivenState(-1, rangeIndex)))
                } else {
                    paths.add(PathNode(null, myTaxonNode.hapnode, taxonName, myTaxonNode.emissionP))
                }
            }

            //for each reference range update paths with probabilities and new nodes
            while (rangeToNodesMapIter.hasNext()) {
                //for each node in the next range find the maximum value of path probability * transition
                //actually only two to consider (1-r) * same taxon (switchProbability) and r/(n-1) * all other taxa
                //where n is number of taxa. Since the transition likelihood is the same for all n of the recombinant taxa,
                //only the most likely of those needs to be considered.
                val nextEntry = rangeToNodesMapIter.next()
                rangeIndex++
                if (!useRange(readMap[nextEntry.key], counters, nextEntry.key, nextEntry.value)) {
                    continue
                }
                val newPaths = ArrayList<PathNode>()

                //choose the most probable path from the previous range. If more than one, any one will do.
                //the most likely path in the previous range
                val bestPath = paths.maxByOrNull { it.totalProbability }
                check(bestPath != null) { "no most likely path before range at ${nextEntry.key.chromosome().name}:${nextEntry.key.start()}" }

                //need a map of taxon -> haplotype node, index
                val taxaMap = nextEntry.value.mapIndexed { index, haplotypeNode ->
                    val probEmission = emissionProb.getLnProbObsGivenState(index, rangeIndex)
                    haplotypeNode.taxaList().map { taxon -> Pair(taxon.name, HapNode(haplotypeNode, probEmission)) }
                }.flatten().toMap()

                val missingHaplotypeEmissionProbability = emissionProb.getLnProbObsGivenState(-1, rangeIndex)

                //iterate over gametes for the new range
                val probSwitch = bestPath.totalProbability + logSwitch
                val pathMap = paths.associateBy { it.gamete }
                taxaSet.forEach { taxonName ->
                    val myTaxonNode = taxaMap[taxonName]
                    if (bestPath.gamete == taxonName) {
                        //this is the best path for this node since is also the no switch path (same gamete)
                        if (myTaxonNode == null) {
                            newPaths.add(
                                PathNode(
                                    bestPath,
                                    null,
                                    taxonName,
                                    bestPath.totalProbability + logNoSwitch + missingHaplotypeEmissionProbability
                                )
                            )
                        } else {
                            newPaths.add(
                                PathNode(
                                    bestPath,
                                    myTaxonNode.hapnode,
                                    taxonName,
                                    bestPath.totalProbability + logNoSwitch + myTaxonNode.emissionP
                                )
                            )
                        }
                    } else {
                        //since the best path is a switch path (switching gametes), must compare it to the no switch path
                        val samePath = pathMap[taxonName]
                        val probNoSwitch = samePath!!.totalProbability + logNoSwitch
                        if (myTaxonNode == null) {
                            if (probSwitch > probNoSwitch) newPaths.add(
                                PathNode(
                                    bestPath,
                                    null,
                                    taxonName,
                                    probSwitch + missingHaplotypeEmissionProbability
                                )
                            )
                            else newPaths.add(
                                PathNode(
                                    samePath,
                                    null,
                                    taxonName,
                                    probNoSwitch + missingHaplotypeEmissionProbability
                                )
                            )
                        } else {
                            if (probSwitch > probNoSwitch) newPaths.add(
                                PathNode(
                                    bestPath,
                                    myTaxonNode.hapnode,
                                    taxonName,
                                    probSwitch + myTaxonNode.emissionP
                                )
                            )
                            else newPaths.add(
                                PathNode(
                                    samePath,
                                    myTaxonNode.hapnode,
                                    taxonName,
                                    probNoSwitch + myTaxonNode.emissionP
                                )
                            )
                        }

                    }

                }

                paths = newPaths

            }

            //countTooFewReads, countTooManyReadsPerKB, countReadsEqual, countDiscardedRanges
            myLogger.info("Finished processing reads for a sample: ${counters[3]} ranges discarded out of ${rangeToNodesMap.size}.")
            myLogger.info("${counters[0]} ranges had too few reads; ${counters[1]} ranges had too many reads; ${counters[2]} ranges had all reads equal")

            //terminate
            //back track the most likely path to get a HaplotypeNode List
            var currentPathnode = paths.maxByOrNull { it.totalProbability }
            val nodeList = ArrayList<HaplotypeNode>()
            while (currentPathnode != null) {
                val node = currentPathnode.hapnode
                if (node != null) nodeList.add(node)
                currentPathnode = currentPathnode.parent
            }

            //the resulting node list is last to first, so reverse it
            nodeList.reverse()
            return nodeList
        }

        private fun diploidViterbi(
            chrom: Chromosome,
            readMap: Multimap<ReferenceRange, HapIdSetCount>
        ): List<List<HaplotypeNode>> {
            myLogger.info("Finding path for chromosome ${chrom.name} using diploidViterbi")
            val rangeToNodesMap = graph.tree(chrom)
            val taxaSet = graph.taxaInGraph().map { it.name }.toSortedSet()
            val taxaNamePairs =
                taxaSet.map { firstName -> taxaSet.map { secondName -> Pair(firstName, secondName) } }.flatten()
            val lnSameGameteProb = log(sameGameteProbability, E)
            val numberOfTaxa = taxaSet.size
            val numberOfStates = numberOfTaxa * numberOfTaxa
            var elapsedTimeForPathLoop = 0L

            //diagnostic counters for discarded ranges:
            //countTooFewReads, countTooManyReadsPerKB, countReadsEqual, countDiscardedRanges
            val counters = IntArray(4) { 0 }

            //create emission and transition probability
            val emissionProb = DiploidEmissionProbability(rangeToNodesMap, readMap, probCorrect)
            val transitionProb =
                DiploidTransitionProbabilityWithInbreeding(taxaSet.size, sameGameteProbability, inbreedCoef)

            val rangeToNodesMapIter = rangeToNodesMap.entries.iterator()
            var rangeIndex = 0

            var initialEntry =
                rangeToNodesMapIter.next()  //entry key is ReferenceRange, entry value is List<HaplotypeNode>
            while (!useRange(
                    readMap[initialEntry.key],
                    counters,
                    initialEntry.key,
                    initialEntry.value
                ) && rangeToNodesMapIter.hasNext()
            ) {
                initialEntry = rangeToNodesMapIter.next()
                rangeIndex++
            }

            //for each ordered pair of taxa create a new path
            //For that need a map of taxa pair to node pair index in order to get emission probabilities
            //Creating the taxa pair to node pair index makes it unnecessary to split the graph by taxa first
            //However, the missing taxa node has to be added so that all taxa are in all reference ranges
            //nodes may have a taxaList with multiple taxa. In that case, all possible taxa pairs should map to same index
            val currentNodePairs = initialEntry.value.map { firstNode ->
                initialEntry.value.map { secondNode ->
                    Pair(
                        firstNode,
                        secondNode
                    )
                }
            }.flatten()
            val taxaPairToIndexMap = taxaPairToIndexMapFromNodePairs(currentNodePairs)

            //map of trellis states to node pair index. The taxa Int pair index is the state
            val nodePairIndexByState = taxaNamePairs.map { taxaPairToIndexMap[it] }

            //create a path for every state
            var paths = (0 until numberOfStates).map { index ->
                val nodePairIndex = nodePairIndexByState[index]
                check(nodePairIndex != null) { "node pair index is missing for " }
                val logEmissionP = emissionProb.getLnProbObsGivenState(nodePairIndex, rangeIndex)
                DiploidPathNode(null, currentNodePairs[nodePairIndex], index, logEmissionP)
            }

            while (rangeToNodesMapIter.hasNext()) {
                //for each node in the next range find the maximum value of path probability * transition
                //actually only two to consider (1-r) * same taxon (switchProbability) and r/(n-1) * all other taxa
                //where n is number of taxa. Since the transition likelihood is the same for all n of the recombinant taxa,
                //only the most likely of those needs to be considered.
                val nextEntry = rangeToNodesMapIter.next()
                rangeIndex++
                if (!useRange(readMap[nextEntry.key], counters, nextEntry.key, nextEntry.value)) {
                    continue
                }
                val newPaths = ArrayList<DiploidPathNode>()

                //choose the most probable path from the previous range. If more than one, any one will do.
                val bestPath = paths.maxByOrNull { it.totalProbability }  //the most likely path in the previous range
                check(bestPath != null) { "no most likely path before range at ${nextEntry.key.chromosome().name}:${nextEntry.key.start()}" }

                val currentNodePairs = nextEntry.value.map { firstNode ->
                    nextEntry.value.map { secondNode ->
                        Pair(
                            firstNode,
                            secondNode
                        )
                    }
                }.flatten()
                val taxaPairToIndexMap = taxaPairToIndexMapFromNodePairs(currentNodePairs)
                val nodePairIndexByState = taxaNamePairs.map { taxaPairToIndexMap[it] }
                val totalProbabilityArray = paths.map { it.totalProbability }.toDoubleArray()

                val start = System.nanoTime()  //to time path finding loop
                for (state in 0 until numberOfStates) {
                    //find the most likely path leading to this node
                    //if the bestPath ends in the same taxa pair as this node, then that is the most likely path.
                    // create the new path and continue
                    val nodePairIndex = nodePairIndexByState[state]
                    check(nodePairIndex != null) { "node pair index is null" }
                    val pairEmissionProbability = emissionProb.getLnProbObsGivenState(nodePairIndex, rangeIndex)

                    if (bestPath.state == state) {
                        newPaths.add(
                            DiploidPathNode(
                                bestPath,
                                currentNodePairs[nodePairIndex],
                                state,
                                bestPath.totalProbability + pairEmissionProbability + lnSameGameteProb
                            )
                        )
                    } else {
                        val (parentState, tmpProbability) = transitionProb.maxIndexAndProbabilityForTarget(
                            totalProbabilityArray,
                            state
                        )

                        //for the best path, add emission probability to total probability then add to new paths
                        newPaths.add(
                            DiploidPathNode(
                                paths[parentState],
                                currentNodePairs[nodePairIndex],
                                state,
                                tmpProbability + pairEmissionProbability
                            )
                        )
                    }
                }
                elapsedTimeForPathLoop += System.nanoTime() - start
                paths = newPaths
            }

            myLogger.info("Elapsed time for path loop = ${elapsedTimeForPathLoop / 1e9} seconds.")

            //terminate
            //back track the most likely path to get two HaplotypeNode Lists
            var currentPathnode = paths.maxByOrNull { it.totalProbability }
            val nodeList1 = mutableListOf<HaplotypeNode>()
            val nodeList2 = mutableListOf<HaplotypeNode>()
            while (currentPathnode != null) {
                nodeList1.add(currentPathnode.hapnode.first)
                nodeList2.add(currentPathnode.hapnode.second)
                currentPathnode = currentPathnode.parent
            }

            //the resulting node lists are last to first, so reverse it
            nodeList1.reverse()
            nodeList2.reverse()
            return listOf(nodeList1, nodeList2)

        }

        private fun taxaPairToIndexMapFromNodePairs(nodePairs: List<Pair<HaplotypeNode, HaplotypeNode>>): Map<Pair<String, String>, Int> {
            val taxaPairToIndexMap = mutableMapOf<Pair<String, String>, Int>()
            nodePairs.forEachIndexed { index, pair ->
                pair.first.taxaList().map { it.name }.forEach { firstName ->
                    pair.second.taxaList().map { it.name }.forEach { secondName ->
                        taxaPairToIndexMap.put(Pair(firstName, secondName), index)
                    }
                }
            }
            return taxaPairToIndexMap
        }

        private fun printHapCountDiagnostic(
            paths: List<PathNode>,
            setCounts: Collection<HapIdSetCount>,
            nodes: Map.Entry<ReferenceRange, List<HaplotypeNode>>
        ) {
            println("---------------------\ndiagnostics for ${nodes.key}")
            for (path in paths) println("${path.gamete} has p = ${path.totalProbability}")

            //which is best?
            val bestProb = paths.map { it.totalProbability }.maxOrNull()
            paths.filter { it.totalProbability == bestProb }.forEach { println("BEST PATH = ${it.gamete}") }

            //taxon -> hapid map
            val haptaxa = HashMap<String, Int>()
            for (node in nodes.value) {
                for (taxon in node.taxaList()) haptaxa.put(taxon.name, node.id())
            }

            //print out count per taxon
            val hapCounts = HashMultiset.create<Int>()
            for (idset in setCounts) {
                for (hid in idset.hapIdSet) hapCounts.add(hid, idset.count)
            }

            for (taxon in haptaxa.keys) println("$taxon hapcount = ${hapCounts.count(haptaxa[taxon])}")

        }

        private fun useRange(
            setCounts: Collection<HapIdSetCount>,
            counters: IntArray,
            refRange: ReferenceRange,
            nodesInRange: List<HaplotypeNode>
        ): Boolean {
            //diagnostic counters for discarded ranges:
            //countTooFewReads, countTooManyReadsPerKB, countReadsEqual, countDiscardedRanges
            //data class HapIdSetCount(val hapIdSet : Set<Int>, val count : Int)
            val rangeLength = refRange.end() - refRange.start() + 1

            if (setCounts.size < minReadsPerRange) {
                counters[0]++
                counters[3]++
                return false
            }
            if (setCounts.size * 1000 / rangeLength > maxReadsPerKB) {
                counters[1]++
                counters[3]++
                return false
            }

            if (removeEqual && minReadsPerRange > 0) {
                val hapidCounter = HashMultiset.create<Int>()
                setCounts.forEach { set -> set.hapIdSet.forEach { hapidCounter.add(it, set.count) } }
                if (hapidCounter.elementSet().size < totalNumberOfTaxa) return true
                val firstCount = hapidCounter.count(hapidCounter.first())
                if (nodesInRange.map { it.id() }.all { hapidCounter.count(it) == firstCount }) {
                    counters[2]++
                    counters[3]++
                    return false
                }
            }

            return true
        }
    }

    class DiploidTransitionProbabilityWithInbreeding(
        val numberOfTaxaNames: Int,
        haploidNoSwitchProb: Double,
        val f: Double
    ) {
        private val pNoSwitch: Double
        private val pSingleSwitch: Double
        private val pDoubleSwitch: Double
        private val pAAtoAB: Double
        private val pAAtoBB: Double
        private val pAAtoBC: Double
        private val numberOfPairs = numberOfTaxaNames * numberOfTaxaNames
        private val probabilityMatrix = Array(numberOfPairs) { DoubleArray(numberOfPairs) { 0.0 } }

        init {
            val haploidSwitchProb = (1.0 - haploidNoSwitchProb) / (numberOfTaxaNames.toDouble() - 1.0)
            pNoSwitch = haploidNoSwitchProb * haploidNoSwitchProb
            pSingleSwitch = haploidNoSwitchProb * haploidSwitchProb
            pDoubleSwitch = haploidSwitchProb * haploidSwitchProb
            pAAtoAB = (1.0 - f) * pSingleSwitch
            pAAtoBB = f * pSingleSwitch + (1.0 - f) * pDoubleSwitch
            pAAtoBC = (1.0 - f) * pDoubleSwitch

            for (from in 0 until numberOfPairs) {
                for (to in 0 until numberOfPairs) {
                    probabilityMatrix[to][from] = log(transitionProbability(pairFromIndex(from), pairFromIndex(to)), E)
                }
            }
        }

        fun pairFromIndex(index: Int): Pair<Int, Int> {
            return Pair(index / numberOfTaxaNames, index % numberOfTaxaNames)
        }

        /**
         * Adds the vector of [fromProbability] to a vector of transition probabilities and returns
         * the index of the maximum value and the maximum value as a Pair<Int,Double>
         */
        fun maxIndexAndProbabilityForTarget(fromProbability: DoubleArray, to: Int): Pair<Int, Double> {
            val transProbs = probabilityMatrix[to]

            var maxIndex = 0
            var maxProb = fromProbability[maxIndex] + transProbs[maxIndex]
            for (ndx in 1 until fromProbability.size) {
                val prob = fromProbability[ndx] + transProbs[ndx]
                if (prob > maxProb) {
                    maxProb = prob
                    maxIndex = ndx
                }
            }
            return Pair(maxIndex, maxProb)
        }

        fun transitionProbability(from: Pair<Int, Int>, to: Pair<Int, Int>): Double {
//        diploid transition as a function of f (inbreeding coefficient)
//        The coefficient of inbreeding of an individual is the probability that two alleles at any locus in an individual are identical by descent from the common ancestor(s) of the two parents
//
//        p(A,A -> A,A) = P(no switch) * P(no switch) [same whether ibd or not]
//        p(A,A -> A,B | not ibd) = P(single switch) * P(no switch)
//        p(A,A -> A,B | ibd) = 0
//        p(A,A -> B,B  | not ibd) = P(single switch) * P(single switch)
//        p(A,A -> B,B  | ibd) = P(single switch)
//        p(A,A -> B,C | not ibd) = P(single switch) * P(single switch)
//        p(A,A -> B,C | ibd) = 0
//        transition from het is always not ibd


            return if (from.first == from.second) {
                when {
                    from.first == to.first && from.second == to.second -> pNoSwitch
                    from.first == to.first || from.second == to.second -> pAAtoAB
                    to.first == to.second -> pAAtoBB
                    else -> pAAtoBC
                }
            } else {
                when {
                    from.first == to.first && from.second == to.second -> pNoSwitch
                    from.first == to.first || from.second == to.second -> pSingleSwitch
                    else -> pDoubleSwitch
                }
            }
        }

        fun lnTransitionProbability(from: Pair<Int, Int>, to: Pair<Int, Int>): Double {
            return probabilityMatrix[to.first * numberOfTaxaNames + to.second][from.first * numberOfTaxaNames + from.second]
        }

    }
}