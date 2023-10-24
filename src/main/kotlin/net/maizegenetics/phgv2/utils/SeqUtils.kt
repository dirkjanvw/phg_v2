package net.maizegenetics.phgv2.utils

import biokotlin.seq.NucSeq
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.logging.Logger

private val myLogger = Logger.getLogger("net.maizegenetics.phgv2.utils.SeqUtils")

/**
 * The retrieveAgcContigs function is called to pull data using the "agc getctg" command.
 *  The user command list should have components that look like one of the following:
 *     contig@genome:start-end
 *     contig@genome
 *     contig:start-end (this will error if there are more than 1 genome with "contig")
 *     contig1 (this will error if there are more than 2 genome with "contig1" )
 *  The dbPath is the path to the folder that contains the assemblies.agc file.  It is
 *  expected this will be the same folder that contains the tiledb datasets.
 */
fun retrieveAgcContigs(dbPath:String,ranges:List<String>):Map<String, NucSeq> {

    val commands = buildAgcCommandFromList(dbPath, ranges)
    val chromNucSeqMap = queryAgc(commands)
    return chromNucSeqMap
}

/**
 * This builds the command that is sent to AGC.  It creates the initial
 *  command, which sets the conda environment, then adds the AGC file
 *  path, then follows with the commands the user has put together in a list
 */
fun buildAgcCommandFromList(dbPath:String, ranges:List<String>): Array<String> {

    // Validate the dbPath. We check if the folder exists, and if it contains the file assemblies.agc
    val agcFile = "${dbPath}/assemblies.agc"

    check(File(agcFile).exists()) { "File assemblies.agc does not exist in folder $dbPath- please send a valid path that indicates the parent folder for your assemblies.agc compressed file." }

    val command = mutableListOf<String>()
    val commandPrefix = arrayOf("conda","run","-n","phgv2-conda","agc","getctg",agcFile)
    command.addAll(commandPrefix)
    command.addAll(ranges)

    return command.toTypedArray()
}

/**
 * Retrieve AGC data from a list of commands sent via ProcessBuilder
 */
fun queryAgc(commands:Array<String>):Map<String, NucSeq> {
    check(commands.size > 0) { "Error:  No commands sent to retrieveAgcData!" }
    myLogger.info("Running Agc Command:\n${commands.joinToString(" ")}")

    // .redirectError(ProcessBuilder.Redirect.INHERIT) redirects to stdout
    // For now, running without redirecting output or error.  The errorStream will be
    // checked and handled at the end.
    val agcProcess = ProcessBuilder(*commands)
        .start()

    val agcOut = BufferedInputStream(agcProcess.inputStream, 5000000)

    // Note AGC does not include the genome in the idline, so we
    // can end up with the same idline multiple times, e.g. if we want chrom 1 from
    // multiple genomes, all the idlines will be ">1" and we will only get the last one
    // when processing into a map.
    // It is the same if we give a region for the chrom, e.g. 1@LineA:1-1000 and 1@LineB:1-1000,
    // we will get the same idline multiple times.
    val chromNucSeqMap = HashMap<String, NucSeq>()
    try {
        agcOut.bufferedReader().use { br ->
            var currChrom = "-1"
            var currSeq = ByteArrayOutputStream()
            var line = br.readLine()
            while (line != null) {

                line = line.trim()
                if (line.startsWith(">")) {
                    if (currChrom != "-1") {
                        // finished with this chromosome's sequence
                        myLogger.info("fastaToNucSeq: finished chrom $currChrom")
                        chromNucSeqMap[currChrom] = NucSeq(currSeq.toString())
                    }
                    // reset chromosome name and sequence, begin processing next chrom
                    currChrom = line.substring(1).split(" ")[0]
                    currSeq = ByteArrayOutputStream()
                } else {
                    currSeq.write(line.toByteArray())
                }
                line = br.readLine()
            }
            if (currSeq.size() > 0) {
                myLogger.info("queryAgc: finished chrom $currChrom")
                chromNucSeqMap[currChrom] = NucSeq(currSeq.toString())
            }
        }
    } catch (exc:Exception) {
        myLogger.severe("Error:  Exception in queryAgc: ${exc.message}")
        throw IllegalStateException("Error:  Exception in queryAgc: ${exc.message}")
    }

    // Check for any errors
    val agcError = BufferedInputStream(agcProcess.errorStream, 5000000)
    val errors = agcError.bufferedReader().readLines()
    myLogger.severe("queryAgc: errors found in errorStream: ${errors.size}")
    if (errors != null && errors.size > 0) {
        throw IllegalArgumentException("Error running AGC command: ${commands.joinToString(" ")}\nError: $errors")
    }

    return chromNucSeqMap
}
