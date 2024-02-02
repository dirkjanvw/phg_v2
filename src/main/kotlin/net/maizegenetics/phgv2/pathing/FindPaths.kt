package net.maizegenetics.phgv2.pathing

import biokotlin.seqIO.NucSeqIO
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import htsjdk.variant.variantcontext.VariantContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.maizegenetics.phgv2.api.HaplotypeGraph
import net.maizegenetics.phgv2.utils.*
import org.apache.logging.log4j.LogManager
import java.io.File

class FindPaths: CliktCommand(help = "Impute best diploid path using read mappings.")  {

    val pathKeyfile by option(help = "tab-delimited file with first two columns: SampleName, ReadMappingFiles. ReadMappingFiles " +
            "must be the full path to a read mapping file or a comma separated list of file paths.")
        .required()
        .validate { require(File(it).exists()) {"$it is not a valid file"} }

    val hvcfDir by option(help = "The directory containing the hvcf files used to build a HaplotypeGraph for path finding.")
        .required()
        .validate { require(File(it).isDirectory) {"$it is not a valid directory."} }

    val referenceGenome by option(help = "path to reference genome (fasta or fastq")
        .required()
        .validate { require(File(it).exists()) {"$it is not a valid file"} }

    val outputDir by option(help = "The directory where the output hvcfs will be written. The output file names will be <sampleName>.h.vcf.")
        .required()
        .validate { require(File(it).isDirectory) {"$it is not a valid directory."} }

    val pathType by option(help = "The type of path to find. Must be lower case. Haploid infers a single path through the graph. Diploid infers a pair of paths.")
        .choice("haploid", "diploid")
        .required()

    val probCorrect by option(help = "The probability that a mapped read was mapped correctly")
        .double()
        .default(0.99)
        .validate { require(it in 0.5..1.0) {"prob-correct must be between 0.5 and 1.0"} }

    val probSameGamete by option(help = "The probability of transitioning to the same gamete in the next reference range")
        .double()
        .default(0.99)
        .validate { require(it in 0.01..1.0) {"prob-correct must be between 0.01 and 1.0"} }

    val minGametes by option(help = "The minimum number of gametes with a haplotype in a reference range. " +
            "Reference ranges with fewer gametes will not be imputed.")
        .int()
        .default(1)
        .validate { require(it > -1) {"min-gametes must be a positive integer"} }

    val minReads by option(help = "The minimum number of reads per ReferenceRange. Reference ranges with fewer reads will not be imputed")
        .int()
        .default(0)
        .validate { require(it > -1) {"min-reads must be a positive integer."} }

    val inbreedingCoefficient by option(help = "The estimated coefficient of inbreeding for the samples being evaluated.")
        .double()
        .default(0.0)
        .validate { require(it in 0.0..1.0) {"inbreeding-coefficient must be between 0.0 and 1.0"} }

    val maxReadsPerKb by option(help = "ReferenceRanges with more than max-reads-per-kb will not be imputed.")
        .int()
        .default(1000)
        .validate { require(it > -1) {"max-reads-per-kb must be a positive integer."} }

    val useLikelyAncestors by option(help="Use only the most likely ancestors of each sample for path finding.")
        .boolean()
        .default(false)

    val maxAncestors by option(help = "If use-likely-ancestors = true, use at most max-ancestors.")
        .int()
        .default(Int.MAX_VALUE)

    val minCoverage by option(help = "If use-likely-ancestors = true, use the fewest number of ancestors that together have this proportion of mappable reads.")
        .double()
        .default(1.0)
        .validate { require(it in 0.5..1.0) {"min-coverage must be between 0.5 and 1.0"} }

    val likelyAncestorFile by option(help="If useLikelyAncestors is true, a record of the ancestors used for each sample will be written to this file.")
        .default("")


    val threads by option(help = "number of threads used to find paths.").int().default(3)



    private val myLogger = LogManager.getLogger(FindPaths::class.java)

    override fun run() {
        val samplesToReadMappingFiles = processKeyFile()
        processReadMappings(samplesToReadMappingFiles)
    }

    private fun buildHaplotypeGraph(): HaplotypeGraph {
        val listOfHvcfFilenames = File(hvcfDir).listFiles().filter { it.name.contains(".h.vcf") }.map { it.path }
        return HaplotypeGraph(listOfHvcfFilenames)
    }

    /**
     * Process the keyfile. Reads the keyfile. For each sample in the keyfile, imputes a path and writes
     * it to a .h.vcf file and, optionally, appends the likely parents to a file.
     */
    private fun processKeyFile(): Map<String, List<String>> {
        //processes the key file to produce a map of sample name -> list of read mapping files for that sample
        myLogger.info("Processing key file $pathKeyfile")
        val keyFileLines = getBufferedReader(pathKeyfile).use { it.readLines()}
            .map { it.split("\t") }
            .filter { it.size > 1 }
        val headerList = keyFileLines[0]

        val sampleNameIsFirstColumn = headerList[0].equals("SampleName", true)
        val readMappingIsSecondColumn =  headerList[1].equals("ReadMappingFiles", true)
        require(sampleNameIsFirstColumn && readMappingIsSecondColumn) {"The first column heading of the keyfile " +
                "must be SampleName and the second column heading must be ReadMappingFiles."}

        //check for duplicate sample names (not allowed)
        val sampleNameSet = keyFileLines.drop(1).map { it[0] }.toSet()
        check(sampleNameSet.size == keyFileLines.size - 1) {"key file contains duplicate sample names"}

        //map of sampleName -> list of read mapping file names
        val sampleToFiles = keyFileLines.drop(1).associate { Pair(it[0], it[1]) }
            .mapValues { it.value.split(",").map{filename -> filename.trim()} }

        return checkForExistingOutput(sampleToFiles)
    }

    /**
     * Takes a map of samples to file list as input. If the output directory contains an hvcf file for any sample,
     * that sample will be removed from the map and the trimmed map will be returned.
     */
    private fun checkForExistingOutput(sampleToFiles: Map<String, List<String>>): Map<String, List<String>> {

        val existingSamples = File(outputDir).listFiles().filter { it.name.contains(".h.vcf") }
            .map { file -> file.name.substringBefore(".h.vcf") }

        val newSamples = sampleToFiles.filter { (sampleName, _) -> !existingSamples.contains(sampleName) }

        if (newSamples.size < sampleToFiles.size)
            myLogger.info("The key file $pathKeyfile contains ${sampleToFiles.size}, only ${newSamples.size} will be imputed." +
                    "The others already existing in the output directory")

        return newSamples
    }

    private data class ReadMappingResult(val name: String, val readMappingCounts: Map<List<String>, Int>)
    private data class Path(val name: String, val hapidList: List<PathFinderWithViterbiHMM.PathNode>, val graph: HaplotypeGraph, val likelyParents: List<MostLikelyParents.ParentStats>)

    /**
     * Takes a map of sample name -> list of read mapping files. It processes the samples in parallel.
     * Multi-threading is across samples. The results are written to files by an additional thread.
     * The results are a h.vcf for each sample and, if the most likely parents are used, a single file of
     * the chosen ancestors (parents) for each sample.
     */
    private fun processReadMappings(sampleToFiles: Map<String, List<String>>) = runBlocking {
        myLogger.info("processing read mappings.")
        val myGraph = buildHaplotypeGraph()

        //check conditions for useLikelyAncestor
        if (useLikelyAncestors) {
            require(maxAncestors < myGraph.sampleGametesInGraph().size || minCoverage < 1.0)
            {"UseLikelyAncestors is true but likelyAncestors will not be checked because minCoverage is 1.0 " +
                    "and maxAncestors is >= the number of potential ancestors."}
            if (likelyAncestorFile.isBlank()) myLogger.warn("Likely ancestors will be determined for each sample " +
                    "but information about the ancestors used will not be written to a file because no file name was provided.")
        }

        //create a channel for read mappings
        val readMappingChannel = Channel<ReadMappingResult>(10)

        //create a channel for paths
        val pathChannel = Channel<Path>(10)

        //create worker threads to process entries from the read mapping channel
        val jobList = mutableListOf<Job>()
        repeat(threads) {
            jobList.add(launch(Dispatchers.Default) {
                imputePath(myGraph, readMappingChannel, pathChannel)
            })
        }

        //create a coroutine to store paths
        launch(Dispatchers.IO) {
            savePath(pathChannel)
        }

        //load read mappings for each sample into a channel
        for (sampleFileList in sampleToFiles) {
            val listOfReadMaps = sampleFileList.value.map { filename -> importReadMapping(filename) }
            val readMappingsForSample = DiploidPathFinding.mergeReadMappings(listOfReadMaps)
            myLogger.info("submitting read mapping for $sampleFileList")
            readMappingChannel.send(ReadMappingResult(sampleFileList.key, readMappingsForSample))
        }
        readMappingChannel.close()

        //now wait for all the impute jobs to finish, then close the pathChannel
        for (imputeJob in jobList) imputeJob.join()
        pathChannel.close()

        //block does not finish until savePath is finished processing results
    }

    private suspend fun imputePath(graph: HaplotypeGraph, readMappingChannel: ReceiveChannel<ReadMappingResult>, pathChannel: SendChannel<Path>) {
        val pathFinder = PathFinderWithViterbiHMM(graph = graph,
            isHaploidPath = pathType == "haploid",
            probCorrect = probCorrect,
            sameGameteProbability = probSameGamete,
            minGametesPerRange = minGametes,
            minReadsPerRange = minReads,
            maxReadsPerKB =  maxReadsPerKb,
            useLikelyParents = useLikelyAncestors,
            maxParents = maxAncestors,
            minCoverage = minCoverage,
            inbreedCoef = inbreedingCoefficient
        )

        for (result in readMappingChannel) {
            val mappingsByRefrange = HaploidPathFinding.readMappingByRange(result.readMappingCounts, graph)
            val pathResult = pathFinder.findBestPath(mappingsByRefrange)
            pathChannel.send(Path(result.name, pathResult.first, graph, pathResult.second))
        }

    }

    private suspend fun savePath(pathChannel : ReceiveChannel<Path>) {
        for (path in pathChannel) {
            writeHvcf(path)
            if (useLikelyAncestors && likelyAncestorFile.isNotBlank()) appendParentStats(path)
        }
    }

    private fun writeHvcf(myPath: Path) {
        //use method exportVariantContext() in VariantLoadingUtils
        //use the alt header lines from the graph for the hapids in myPath
        //create a list of VariantContexts from myPath: use method createHvcfRecord() in VariantLoadingUtils

        val referenceSequence = NucSeqIO(referenceGenome).readAll()
        val altHeadersSample = mutableListOf<AltHeaderMetaData>()

        val variantContextList = mutableListOf<VariantContext>()

        //process the path
        for ( node in myPath.hapidList) {
            val startPos = Position(node.refRange.contig, node.refRange.start)
            val endPos = Position(node.refRange.contig, node.refRange.end)
            val refAllele = referenceSequence[node.refRange.contig]!!.sequence[node.refRange.start - 1].name
            val hapids = node.sampleGametes.mapNotNull { myPath.graph.sampleToHapId(node.refRange, it) }

            if (hapids.isNotEmpty()) variantContextList.add(createDiploidHVCFRecord(myPath.name, startPos, endPos, hapids, refAllele))
        }

        //exportVariantContext()
        val hvcfFileName = if (outputDir.endsWith("/")) "${outputDir}${myPath.name}.h.vcf"
        else "${outputDir}/${myPath.name}.h.vcf"
        val headerSet = altHeadersSample.map { DiploidPathFinding.altHeaderMetadataToVCFHeaderLine(it) }.toSet()

        exportVariantContext(myPath.name, variantContextList, hvcfFileName, referenceSequence, headerSet)

    }

    /**
     * Appends taxa chosen by likely parents to a file along with incremental read counts and cumulative coverage.
     */
    private fun appendParentStats(path: Path) {
        val fileExists = File(likelyAncestorFile).exists()
        getBufferedWriter(likelyAncestorFile, append = true).use { myWriter ->
            if (!fileExists) myWriter.write("sample\tancestor\tgameteId\treads\tcoverage\n")
            for (stats in path.likelyParents) {
                myWriter.write("${path.name}\t${stats.parent.name}\t${stats.parent.gameteId}\t${stats.readCount}\t${stats.coverage}\n")
            }
        }
    }

}