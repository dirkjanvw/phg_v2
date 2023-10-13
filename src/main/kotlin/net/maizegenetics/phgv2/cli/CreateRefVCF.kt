package net.maizegenetics.phgv2.cli

import biokotlin.genome.fastaToNucSeq
import biokotlin.seq.NucSeq
import biokotlin.util.bufferedReader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Range
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.vcf.VCFAltHeaderLine
import htsjdk.variant.vcf.VCFHeaderLine
import htsjdk.variant.vcf.VCFHeaderVersion
import net.maizegenetics.phgv2.utils.*
import java.util.*
import java.util.logging.Logger


class CreateRefVCF : CliktCommand() {

    private val myLogger = Logger.getLogger("net.maizegenetics.phgv2.cli.CreateRefVcf")

    var myRefSequence: Map<String, NucSeq>? = null

    // refurl is not required.  If present, it will result in a ##reference header
    // in the hvcf file.
    val refurl by option(help = "URL where the reference FASTA file can be downloaded")
        .default("")

    val bed by option(help = "BED file")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--bed must not be blank"
            }
        }

    val referencefile by option(help = "Reference FASTA file")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--referencefile must not be blank"
            }
        }

    val refname by option(help = "Line name for reference to be used in hvcf ")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--refname must not be blank"
            }
        }

    val outputDir by option("-o", "--output-dir", help = "Name for output VCF file Directory")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--output-dir/-o must not be blank"
            }
        }

    override fun run() {
        myLogger.info("begin run")
        createRefHvcf(bed,referencefile,refname,refurl,outputDir)

    }

    fun createRefHvcf(ranges:String,refGenome:String,refName:String,refUrl:String,outputDir:String) {

        myLogger.info("begin createRefHvcf,  refGenome=${refGenome}")


        // Verify the bed file is good.
        // If there are overlaps, throw an error and exit.
        // Overlaps are printed to stdout.
        val overlaps = verifyIntervalRanges(ranges)
        if (overlaps.isNotEmpty()) {
            // Overlaps not permitted.  User can fix via manually or via CreateValidIntervalsFilePlugin.  Throw error
            overlaps.forEach { entry: String -> myLogger.severe("BuildRefVcf:  range Overlap entry: $entry") }
            throw IllegalArgumentException("BuildRefVcf: intervals bed file has overlapping positions. Overlapping reference ranges are not supported.  Please consolidate/remove overlaps.")
        }

        var groupAndPositionsMap: Multimap<String, Range<Position>> = HashMultimap.create()

        // read the ref fasta into the myRefSequence map
        myRefSequence = fastaToNucSeq(refGenome)

        // Create VCList: - this list contains VariantContext records for all the reference ranges
        val fullRefVCList = mutableListOf<VariantContext>()

        // Create HashSet to hold a list of ALT header lines for this gvcf
        // There will be 1 ALT header line created for every reference range in
        // this file
        val altHeaderLines: MutableSet<VCFHeaderLine> = HashSet()

        // add ##reference line if a refURL was provided
        if (refUrl.isNotBlank()) {
            altHeaderLines.add(
                VCFHeaderLine("reference","${refUrl}")) // add the ##reference line
        }

        // Process the user interval ranges file
        try {
            bufferedReader(ranges).use { br ->
                var chrom = "-1"
                var prevChrom = "-1"
                var line: String? = null
                var chr: String? = null
                //var chrSeq = NucSeq("")
                var chrSeq: NucSeq? = NucSeq("")

                var chromAnchors = 0

                line = br.readLine()

                while (line != null) {
                    if (line.uppercase(Locale.getDefault()).contains("CHROMSTART")) {
                        line = br.readLine()
                        continue // skip header line
                    }
                    val tokens = line.split("\t")
                    // Line must contain at least 3 columns: chrom, chromStart, chromEnd
                    // Additional columns are ignored
                    if (tokens.size < 3) {
                        throw IllegalArgumentException("Error processing intervals file on line : ${line} . Must have values for columns chrom, chromStart, chromEnd and name")
                    }
                    chrom = tokens[0]

                    if (chrom != prevChrom) {
                        myLogger.info("Total intervals for chrom ${prevChrom}: ${chromAnchors}")
                        myLogger.info("Starting chrom $chrom")
                        chr = chrom
                        prevChrom = chrom
                        chromAnchors = 0
                        chrSeq = myRefSequence!![chr]
                    }

                    val anchorStart = tokens[1].toInt() + 1  // NucSeq is 0 based, bed file is 0 based, no need to change
                    val anchorEnd = tokens[2].toInt() // bed file is exclusive, but 0-based, so no need to change

                    chromAnchors++
                    // get bytes from reference, convert to string, add data to list
                    val intervalSeq = chrSeq!![anchorStart, anchorEnd-1].toString()
                    val intervalHash = getChecksumForString(intervalSeq, "Md5")
                    val intervalStart = Position(chrom, anchorStart)
                    val intervalEnd = Position(chrom, anchorEnd)
                    val intervalRange = Range.closed(intervalStart, intervalEnd)
                    val type = tokens[3]
                    groupAndPositionsMap.put(type, intervalRange)

                    // SEnding null for asm_start and asm_end.
                    // this prevents them from being written to the hvcf file
                    val vc = createRefRangeVC(
                        myRefSequence!!,
                        refName,
                        intervalStart,
                        intervalEnd,
                        null,
                        null
                    )
                    fullRefVCList.add(vc) // this is a list of ALL the VC records for all ranges - will become the hvcf file.

                    // Add vcf header lines here, doing somthing like this:
                    // headerLines.add(VCFAltHeaderLine("<ID=${intervalHash}, Description=\"${nodeDescription(node)}\">", VCFHeaderVersion.VCF4_2))
                    altHeaderLines.add(
                        VCFAltHeaderLine(
                            "<ID=${intervalHash}, Description=\"haplotype data for line: ${refName}\">,Number=6,Source=\"${refGenome}\",Contig=\"${chr}\",Start=\"${anchorStart}\",End=\"${anchorEnd}\",Checksum=\"Md5\",RefRange=\"${intervalHash}\">",
                            VCFHeaderVersion.VCF4_2
                        )
                    )
                    line = br.readLine()
                } // end while
                myLogger.info("Total intervals for chrom ${prevChrom} : ${chromAnchors}")
            } // end buffered reader

            // Load to an hvcf file, write files to user specified outputDir

            val hvcfFileName = "${refName}.h.vcf"
            var localRefHVCFFile = outputDir + "/" + hvcfFileName

            //  This is in VariantUtils - it exports the gvcf file.
            // Include the VCF ALT Header lines created in the loop above
            exportVariantContext(refName, fullRefVCList, localRefHVCFFile, myRefSequence!!,altHeaderLines)
            //bgzip and csi index the file
            val bgzippedGVCFFileName = bgzipAndIndexGVCFfile(localRefHVCFFile)
            myLogger.info("${bgzippedGVCFFileName} created and stored to ${outputDir}")


        } catch (exc: Exception) {
            myLogger.severe("Error creating Ref HVCF file: ${exc.message}")
            throw IllegalStateException("Error creating Ref HVCF file: ${exc.message}")
        }

    } // end createRefRanges()

}