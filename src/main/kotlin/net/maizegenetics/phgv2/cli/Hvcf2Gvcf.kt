package net.maizegenetics.phgv2.cli

import biokotlin.genome.AssemblyVariantInfo
import biokotlin.genome.MAFToGVCF
import biokotlin.genome.refDepth
import biokotlin.seq.NucSeq
import biokotlin.util.bufferedReader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.variantcontext.VariantContextComparator
import htsjdk.variant.vcf.VCFEncoder
import htsjdk.variant.vcf.VCFFileReader
import htsjdk.variant.vcf.VCFHeader
import htsjdk.variant.vcf.VCFHeaderLine
import net.maizegenetics.phgv2.api.ReferenceRange
import net.maizegenetics.phgv2.utils.*
import org.apache.logging.log4j.LogManager
import java.io.File

/**
 * This class takes a phgv2 hvcf file that was created via path-finding, and
 * turns it into a gvcf file.  It assumes the gvcf file for all samples
 * reflected in the hvcf file live in the tiledb database.  These will be
 * exported for use.
 *
 * The file name for the newly created gvcf file will be the same as the hvcf file
 * (minus extension).
 *
 * Steps:
 * 1.  verify initial data
 * 2.  read hvcf file, get sample names represented
 *    - sample names come from the ALT header lines - grab all the SampleName fields
 *    and add to a set.
 * 3.  export the gvcf files from the tiledb database
 * 4.  ALso from the hvcf files, create a list of ReferenceRange objects for each sample
 * 5.  Read the gvcf files, a sample at a time, and for each sample, pull the gvcf records
 * that overlap the ReferenceRanges for that sample.  This will be a list of VariantContext records.
 * 6.  Merge the list of vcRecords for each sample together, sorted by reference ranges, and write
 * to a new gvcf file in the output directory.
 * 7.  The header for the gvcf file will be the same as the header from one of the gvcf files.
 *     With the exception that sampleName will be changed to the sample name from the hvcf file.
 * 8.  The gvcf file will be written to the output directory.
 *
 *
 */
class Hvcf2Gvcf: CliktCommand(help = "Create  h.vcf files from existing PHG created g.vcf files")  {
    private val myLogger = LogManager.getLogger(Hvcf2Gvcf::class.java)

    val hvcfDir by option("--hvcf-dir", help = "Path to directory holding hVCF files. Data will be pulled directly from these files instead of querying TileDB")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--hvcf-dir must not be blank"
            }
        }

    val condaEnvPrefix by option (help = "Prefix for the conda environment to use.  If provided, this should be the full path to the conda environment.")
        .default("")

    val outputDir by option (help = "Output directory for the gVCF files.  If not provided, the current working directory is used.")
        .default("")

    val dbPath by option(help = "Folder name where TileDB datasets and AGC record is stored.  If not provided, the current working directory is used")
        .default("")
    val referenceFile by option(help = "Path to local Reference FASTA file needed for sequence dictionary")
        .default("")
        .validate {
            require(it.isNotBlank()) {
                "--reference-file must not be blank"
            }
        }

    override fun run() {
        //TODO("Not yet implemented")
        val dbPath = if (dbPath.isBlank()) {
            System.getProperty("user.dir")
        } else {
            dbPath
        }

        println("Hvcf2Gvcf: dbPath = $dbPath, hvcfDir = $hvcfDir, outputDir = $outputDir, condaEnvPrefix = $condaEnvPrefix")

        // Verify the tiledbURI - verifyURI will throw an exception if the URI is not valid
        val validDB = verifyURI(dbPath,"hvcf_dataset",condaEnvPrefix)

        // create the reference file sequence dictionary
        val refSeq = CreateMafVcf().buildRefGenomeSeq(referenceFile)
        buildGvcfFromHvcf(dbPath, refSeq, outputDir, hvcfDir, condaEnvPrefix)
    }

    fun buildGvcfFromHvcf(dbPath: String, refSeq: Map<String, NucSeq>, outputDir: String, hvcfDir: String, condaEnvPrefix: String) {
        // load the reference file
        println("LCJ - in buildGvcfFromHvcf")

        println("LCJ - after buildREfGenomeSeq, walk the hvcf directory")
        // get list of hvcf files
        // walk the gvcf directory process files with g.vcf.gz extension
        File(hvcfDir).walk().filter { !it.isHidden && !it.isDirectory }
            .filter { it.name.endsWith("h.vcf.gz")  || it.name.endsWith("h.vcf")  }.toList()
            .forEach { hvcfFile ->
                println("buildGvcfFromHvcf: Processing hvcf file: ${hvcfFile.name}")
                val records = processSingleHVCF(refSeq, outputDir, hvcfFile, dbPath,condaEnvPrefix)

                val sample = hvcfFile.toString().substringAfterLast("/").substringBefore(".")
                val gvcfFile = "$outputDir/${sample}.g.vcf"
                exportVariantContext(sample,records,gvcfFile, refSeq,setOf())
            }

    }

    fun processSingleHVCF(refSeq:Map<String,NucSeq>, outputDir:String, hvcfFile: File, dbPath: String, condaEnvPrefix: String): List<VariantContext> {

        val reader = VCFFileReader(hvcfFile,false)
        // We also need to print to the new gvcf all the headers from 1 of the accessed gvcf files
        val header = reader.fileHeader
        val altHeaders = parseALTHeader(header = header)
        val sampleNames = altHeaders.values.map { it.sampleName() }.toSet()
        val contigNames = header.contigLines.map { it.id } // contig names needed for sorting

        // This checks for existing gvcf files, and if they don't exist, exports them
        val exportSuccess = exportGvcfFiles(sampleNames, outputDir, dbPath, condaEnvPrefix)

        // Using the hvcfdFileReader, walk the hvcf file and for each entry create
        // a ReferenceRange object and add that to a list of Reference Range objects
        // for the sample to which it applies.  You will get the sample by indexing the
        // altHeaders map with the key of the current record's ID.
        val sampleToRefRanges = mutableMapOf<String, MutableList<ReferenceRange>>()

        // Get the sampleName from the hvcf file.  THis is the name
        // without the path, and without the extension of h.vcf, hvcf, h.vcf.gz or hvcf.gz
        val pathSample = hvcfFile.toString().substringAfterLast("/").substringBefore(".")

        var rangesSkipped = 0
        // process the hvcf records into the sampleToRefRanges map
        reader.forEach { context ->
            // ARe these ref ranges?  Or just ranges where we could not align?
            if (context.alternateAlleles.isEmpty() || context.alternateAlleles.any { it.isNoCall }) {
                // skip records with no alternate alleles
                rangesSkipped++
                return@forEach
            }
            val id = context.getAlternateAllele(0).displayString.removeSurrounding("<", ">")

            val altHeader = altHeaders[id]
            val sampleName = altHeader?.sampleName() ?: throw IllegalStateException("No ALT header found for record: ${id}")
            val refRange = ReferenceRange(context.contig, context.start, context.end)
            val sampleList = sampleToRefRanges.getOrDefault(sampleName, mutableListOf())
            sampleList.add(refRange)
            sampleToRefRanges[sampleName] = sampleList

        }
        reader.close()

        // There is no gvcf for the reference, so we need to create one if
        // the reference is in the sampleNames list.  The query below gets the
        // ref sample name from the agc file (which is <dbPath>/assemblies.agc)
        val refSampleName = retrieveRefSampleName (dbPath, condaEnvPrefix)
        // check if file outputDir/refSampleName.vcf exists
        val refGvcfFile = "$outputDir/${refSampleName}.vcf"

        // If the refSampleName is in the sampleNames list, and the refGvcfFile does not exist,
        // create a gvcf of ref blocks for all reference ranges. Because there may be multiple
        // hvcf files, and each one may have different reference ranges, we need to create a
        // gvcf file for the reference sample that contains all the reference ranges from the bed file
        if (sampleNames.contains(refSampleName) && !File(refGvcfFile).exists()) {
            // find the bed file.  When the reference was loaded, the bed file was copied
            // to the dbPath/reference folder.  Look for any file with extension .bed in
            // folder.
            val bedFile = File("$dbPath/reference").walk().filter { it.name.endsWith(".bed") }.toList().firstOrNull()?.absolutePath
            check(bedFile != null) { "No bed file found in $dbPath/reference, cannot pull ref haplotypes" }

            // create a gvcf of ref blocks for all reference ranges
            createRefGvcf(refSampleName, bedFile, refSeq, outputDir)
        }

        println("processSingleHVCF: rangesSkipped = $rangesSkipped")

        // For each sample, read the gvcf file and pull the gvcf records that overlap the ReferenceRanges
        // for that sample.  This will be a list of VariantContext records.  After we have the list of vcfRecords
        // for each sample, we will merge them together, sorted by reference ranges, and write to a new gvcf file
        // in the output directory.

        val refRangeToVariantContext = mutableMapOf<ReferenceRange, MutableList<VariantContext>>()

        sampleToRefRanges.forEach { sample, ranges ->
            println("processSingleHVCF: processing sample: $sample")
            val gvcfFile = "$outputDir/${sample}.vcf" // tiledb wrote with extension .vcf
            val gvcfReader = VCFFileReader(File(gvcfFile),false)

            val gvcfVariants  = mutableListOf<VariantContext>()
            gvcfReader.use { reader ->
                for (vc in reader) {
                    gvcfVariants.add(vc)
                }
            }
            gvcfReader.close()
            val rangeToGvcfRecords = findOverlappingRecordsForSample(ranges, gvcfVariants)

            //Add the rangeToGvcfRecords to the refRangeToVariantContext map
            // we can use "plus" but it creates a new map containing the combined entries
            // and assigns it back to refRangeToVariantContext, which is inefficient
            for ((range, variantList) in rangeToGvcfRecords) {
                val existingList = refRangeToVariantContext.getOrPut(range) { mutableListOf() }
                existingList.addAll(variantList)
            }
        }
        // Put all the VariantContext records from the refRangeToVariantContext map
        // onto a list, then sort that list.
        val allRecords = mutableListOf<VariantContext>()
        refRangeToVariantContext.values.forEach { allRecords.addAll(it) }
        val variants = allRecords.sortedWith(VariantContextComparator(contigNames))

        return variants
    }

    // This function creates a gvcf of refBlocks based on the ReferenceRanges for the ref sample
    fun createRefGvcf(refSampleName:String, bedFile:String, refSeq:Map<String,NucSeq>,outputDir:String) {

        val mafToGvcf = MAFToGVCF()
        val variantList = mutableListOf<AssemblyVariantInfo>()
        // Read the bedZFile, use it to create a list of VariantInfo records
        bufferedReader(bedFile).use { reader ->
            // buildRefBLockVariantInfo for each range in the bed file
            reader.forEachLine { line ->
                val fields = line.split("\t")
                val contig = fields[0]
                val start = fields[1].toInt()
                val end = fields[2].toInt()
                variantList += buildRefBlockVariantInfo(
                    refSeq,
                    contig,
                    Pair<Int,Int>(start+1,end),
                    contig, // asm data is same as ref data
                    Pair<Int,Int>(start+1,end),
                    "+" // strand is always positive for ref
                )
            }
        }
        val vcs = mafToGvcf.createVariantContextsFromInfo(refSampleName, variantList, false, false, 0)

        val refGvcfFile = "$outputDir/${refSampleName}.vcf"
        // Once created, we export the ref gvcf file.  The extension is ".vcf" as that
        // is consistent with the extension used by tiledbvcf when exporting the gvcf files
        exportVariantContext(refSampleName,vcs,refGvcfFile, refSeq,setOf())
    }

    fun buildRefBlockVariantInfo(
        refSequence: Map<String, NucSeq>,
        chrom: String,
        currentRefBlockBoundaries: Pair<Int, Int>,
        assemblyChrom: String,
        currentAssemblyBoundaries: Pair<Int, Int>,
        assemblyStrand: String
    ): AssemblyVariantInfo {
        // -1, NucSeq is 0-based
        return AssemblyVariantInfo(
            chrom, currentRefBlockBoundaries.first, currentRefBlockBoundaries.second, "REF",
            refSequence[chrom]!!.get(currentRefBlockBoundaries.first - 1).toString(), ".", false,
            refDepth, assemblyChrom, currentAssemblyBoundaries.first, currentAssemblyBoundaries.second, assemblyStrand
        )
    }

    // function to export from tiledb the gvcf files if they don't exist
    // If we get the tiledb-java API working for MAC, we can hold these in memory
    // while processing and skip the export.
    fun exportGvcfFiles(sampleNames:Set<String>, outputDir:String, dbPath:String, condaEnvPrefix:String):Boolean {
        val success = true
        // Check if the files listed in gvcfFIles already exist
        val gvcfFiles = mutableListOf<String>()
        sampleNames.forEach { sampleName ->
            val gvcfFile = "$outputDir/${sampleName}.vcf"
            gvcfFiles.add(gvcfFile)
        }

        println("exportGvcfFiles: gvcfFiles= ${gvcfFiles}")
        val missingFiles = gvcfFiles.filter { !File(it).exists() }
        // For the entries in the missingFiles list, create a list of sampleNames.
        // The sampleNames are the entry in the missingFiles list, remove up to and
        // including the first "/" and remove the ".vcf" extension
        val missingSampleNames = missingFiles.map { it.substringAfterLast("/").substringBeforeLast(".") }

        // Setup the conda enviroment portion of the command
        var command = if (condaEnvPrefix.isNotBlank()) mutableListOf("conda","run","-p",condaEnvPrefix) else mutableListOf("conda","run","-n","phgv2-conda")
        // Call ExportVcf with the outputDir and the missingSamleNames list
        // to create the gvcf files
        var dataCommand = mutableListOf(
            "conda",
            "run",
            "-n",
            "phgv2-conda",
            "tiledbvcf",
            "export",
            "--uri",
            "$dbPath/gvcf_dataset",
            "-O",
            "v",
            "--sample-names",
            missingSampleNames.joinToString(","),
            "--output-dir",
            outputDir
        )

        // join the conda and data portion of the commands
        command.addAll(dataCommand)
        val builder = ProcessBuilder(command)

        val redirectError = "$outputDir/export_gvcf_error.log"
        val redirectOutput = "$outputDir/export_gvcf_output.log"
        builder.redirectOutput(File(redirectOutput))
        builder.redirectError(File(redirectError))

        myLogger.info("ExportVcf Command: " + builder.command().joinToString(" "))
        println("TILEDB ExportVcf Command: " + builder.command().joinToString(" "))
        val process = builder.start()
        val error = process.waitFor()
        if (error != 0) {
            myLogger.error("tiledbvcf export for: $missingSampleNames run via ProcessBuilder returned error code $error")
            throw IllegalStateException("Error running tiledbvcf export of dataset $dbPath/gvcf_dataset for: $missingSampleNames. error: $error")
        }
        return success
    }

    fun findOverlappingRecordsForSample(ranges:List<ReferenceRange>, variantContexts:List<VariantContext>):MutableMap<ReferenceRange,MutableList<VariantContext>> {
        // split ranges and variantContexts by chromosome
        val overlappingRecords = mutableMapOf<ReferenceRange, MutableList<VariantContext>>()
        val rangesByChrom = ranges.groupBy { it.contig }
        val variantContextsByChrom = variantContexts.groupBy { it.contig }
        // call findOverlappingRecords for each chromosome, add the results to overlappingRecords
        rangesByChrom.keys.forEach { chrom ->
            val chromRanges = rangesByChrom[chrom] ?: emptyList()
            val chromVariants = variantContextsByChrom[chrom] ?: emptyList()
            myLogger.info("findOverlappingRecordsForSample: Processing ${chromRanges.size} ranges and ${chromVariants.size} variants for chromosome $chrom")
            val chromOverlappingRecords = findOverlappingRecords(chromRanges, chromVariants)
            overlappingRecords.putAll(chromOverlappingRecords)
        }
        return overlappingRecords
    }

    // This is based on CreateMafVCF:convertGVCFToHVCFForChrom() that determines if a variant or part
    // of a variant is in a reference range.  It differs in that we are not creating hvcf meta data,
    // but rather, at this stage, are merely finding the overlapping variants
    // Another function will be called to split the gvcf records if they extend  beyond the reference range
    // either at the beginning or the end.
    fun findOverlappingRecords(ranges:List<ReferenceRange>, variantContexts:List<VariantContext>):MutableMap<ReferenceRange,MutableList<VariantContext>> {
        val refRangeToVariantContext = mutableMapOf<ReferenceRange, MutableList<VariantContext>>() // this will be returned
        var currentVariantIdx = 0
        for (range in ranges) {
            val regionStart = range.start
            val regionEnd = range.end
            val regionChrom = range.contig
            val tempVariants = mutableListOf<VariantContext>()

            while (currentVariantIdx < variantContexts.size) {
                val currentVariant = variantContexts[currentVariantIdx]

                // check different cases for the variant
                //If variant is fully contained in Bed region add to temp list and increment currentVariantIdx
                //If variant is partially contained in Bed region add to temp list do not increment as we need to see if the next bed also overlaps
                //If variant is not contained in Bed region, skip and do not increment as we need to see if the next bed overlaps
                if(CreateMafVcf().bedRegionContainedInVariant(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)), currentVariant)) {
                    // This is the case where the region is completely contained within the variant,
                    // meaning the variant may overlap the region.  We need to adjust the asm positions
                    val fixedVariants = fixPositions(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)), listOf(currentVariant))
                    // Add the fixedVariants to the refRangeToVariantContext map, for the current range
                    val currentVariants = refRangeToVariantContext.getOrPut(range, { mutableListOf() })
                    currentVariants.addAll(fixedVariants)
                    refRangeToVariantContext[range] = currentVariants
                    tempVariants.clear()
                    break
                }
                if(CreateMafVcf().variantFullyContained(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)), currentVariant)) {
                    //This is the case where the variant is completely contained within the region
                    tempVariants.add(currentVariant)
                    currentVariantIdx++
                }
                else if(CreateMafVcf().variantPartiallyContainedStart(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)),currentVariant)) {
                    tempVariants.add(currentVariant)
                    break
                }
                else if(CreateMafVcf().variantPartiallyContainedEnd(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)), currentVariant)) {
                    tempVariants.add(currentVariant)
                    currentVariantIdx++
                }
                else if(CreateMafVcf().variantAfterRegion(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)), currentVariant)) {
                    //write out what is in tempVariants
                    if(tempVariants.isNotEmpty()) {
                        val fixedVariants = fixPositions(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)), tempVariants)
                        val currentVariants = refRangeToVariantContext.getOrPut(range, { mutableListOf() })
                        currentVariants.addAll(fixedVariants)
                        refRangeToVariantContext[range] = currentVariants
                        tempVariants.clear()
                    }
                    //move up Bed region
                    break
                }
                else { //this is the case where the Variant is behind the BED region
                    //move up Variant
                    currentVariantIdx++
                }
            }
            // Process the last variants in the list
            if(tempVariants.isNotEmpty()) {
                val fixedVariants = fixPositions(Pair(Position(regionChrom,regionStart),Position(regionChrom,regionEnd)), tempVariants)
                val currentVariants = refRangeToVariantContext.getOrPut(range, { mutableListOf() })
                currentVariants.addAll(fixedVariants)
                refRangeToVariantContext[range] = currentVariants
                tempVariants.clear()
            }
        }

        return refRangeToVariantContext
    }

    // This fixes both the ref start/end and the asm positions in the tempVariants list.  It should return an
    // ammended version of the list. Code is based on CreateMafVcf:convertGVCFRecordsToHVCFMetaData()
    // but only deals with the ASM positions portion of this code.  In addition, it adds code
    // to adjust the variant's start/end positions. Indels are not currently handled.
    fun fixPositions(region: Pair<Position,Position>, variants: List<VariantContext> ): List<VariantContext> {
        val fixedVariants = mutableListOf<VariantContext>()

        // TODO ADD INDEL SUPPORT
        //Take the first and the last variantContext
        val firstVariant = variants.first()
        val lastVariant = variants.last()

        //val check strandedness of the variants
        val firstStrand = firstVariant.getAttributeAsString("ASM_Strand","+")

        val lastStrand = lastVariant.getAttributeAsString("ASM_Strand","+")

        // Resize the first and last variantContext ref/ASM start and end based on the regions
        // THis returs a list of Pairs of Ints.  The first Int is the reference position
        // and the second Int is the ASM position.  The first pair is for the start of the first variant,
        // the second pair is for the end of the last variant..  If there is only 1 variant, the 2 should be

        val newGvcfPositions = resizeVCandASMpositions(Pair(firstVariant,lastVariant), Pair(region.first.position,region.second.position), Pair(firstStrand,lastStrand))

        // At this point, we have changes for the first and last regions.  If the list size
        // is only 1, we change it based on newStartPositions and newEndPositions
        // If there are multiple, we change the first and last entries.  The first entry gets its start changed,
        // THe last entry gets end values changed.
        // NOTE: we do not alter the .stop or .attribute("END") entries.  The size is always 1
        // for both REF_BLOCK and SNP.  WE are not yet supporting indels.
        if (variants.size == 1) {
            // Not setting "stop" for the ref (vs ASM_*) position as it will alwasy
            // be 1 for REF_BLOCK or SNP, so this doesn't change
            val updatedFirstVariant = VariantContextBuilder(firstVariant)
                .start(newGvcfPositions[0].first.toLong())
                .attribute("ASM_Start", newGvcfPositions[1].second)
                .attribute("ASM_End", newGvcfPositions[1].second)
                .make()
            fixedVariants.add(updatedFirstVariant)
        }
        else {
            // update the first and last variants, leaving those
            // in the middle unchanged
            val updatedFirstVariant = VariantContextBuilder(firstVariant)
                .start(newGvcfPositions[0].first.toLong())
                .attribute("ASM_Start", newGvcfPositions[0].second)
                .make()
            fixedVariants.add(updatedFirstVariant)
            val updatedLastVariant = VariantContextBuilder(lastVariant)
                //.stop(newGvcfPositions[1].first.toLong())
                //.attribute("END", newGvcfPositions[1].first.toLong())
                .attribute("ASM_End", newGvcfPositions[1].second)
                .make()

            if (variants.size > 2) {
                fixedVariants.addAll(variants.subList(1,variants.size-1))
            }
            fixedVariants.add(updatedLastVariant)
        }

        return fixedVariants

    }

    //  Based on CreateMafVcf:resizeVariantContext() - but this version deals with both
    // the reference start/end as well as and the ASM_* positions
    // This new version: returns a List of Pairs of Ints.  The first Int is the reference position
    // and the second Int is the ASM position.  This first pair is for the start of the first variant,
    // the second pair is for the end of the last variant..  If there is only 1 variant, the 2 should be
    // the same.
    // TODO = verify if this is correct. Check against CreateMafVcf:resizeVariantContext()
    fun resizeVCandASMpositions(variants: Pair<VariantContext,VariantContext>, positions: Pair<Int,Int>, strands : Pair<String,String>) : List<Pair<Int,Int>> {
        //check to see if the variant is either a RefBlock or is a SNP with equal lengths
        val updatedPositions = mutableListOf<Pair<Int,Int>>()
        var refAsmPos_first = Pair<Int,Int>(-1,-1)
        var refAsmPos_last = Pair<Int,Int>(-1,-1)
        val firstVariant = variants.first
        val lastVariant = variants.second

        if (CreateMafVcf().isVariantResizable(firstVariant) ) {
            // if the position is < the start of the variant, then we return <variant.sart,asm_start>

            // But the "position" must be modified in the above with an offset.
            // these 2 checks verify the position is within the variant range
            // if the strand is +, then we return position + offset
            // if the strand is -, then we return position - offset
             refAsmPos_first = when {
                positions.first < firstVariant.start -> {
                    // The reference position starts before the variant, so we keep the new gvcf
                    // entry start equal to the current variant start
                    Pair(firstVariant.start,firstVariant.getAttributeAsInt("ASM_Start",firstVariant.start))
                }
                strands.first == "+" -> {
                    val offset = positions.first - firstVariant.start
                    // We need to offset the ASM_Start, by the difference between the position and the variant start
                    // However, the ref position should be the same as the ref range start as it is equal to or
                    // greater than the variant start
                    Pair(positions.first,firstVariant.getAttributeAsInt("ASM_Start",firstVariant.start) + offset)
                }
                strands.first == "-" -> {
                    val offset = positions.first - firstVariant.start
                    // offset for reverse strand
                    Pair(positions.first,firstVariant.getAttributeAsInt("ASM_Start",firstVariant.end) - offset)
                }
                else -> {
                    // change ASM_S but not the ref position as this VC is not resizable
                    val newASMStart = if(strands.first == "+") firstVariant.getAttributeAsInt("ASM_Start",positions.first)
                    else firstVariant.getAttributeAsInt("ASM_End",positions.first)
                    Pair(firstVariant.start,newASMStart)

                }
            }
        } else {
            // not resizable, so only change the ASM_* values
            val newASMStart = if(strands.first == "+") firstVariant.getAttributeAsInt("ASM_Start",positions.first)
            else firstVariant.getAttributeAsInt("ASM_End",positions.first)
            refAsmPos_first = Pair(firstVariant.start,newASMStart)
        }
        updatedPositions.add(refAsmPos_first)

        // Processing the last variant that overlaps the reference range.
        // This could be the same as the first variant if the list only has 1 variant
        // The updated values depend on whether the refRange overlaps the beginning of the
        // variant, the end, or is completely contained within the variant.
        if (CreateMafVcf().isVariantResizable(lastVariant)) {
            refAsmPos_last = when {
                positions.second >= lastVariant.end -> {
                    // The end of the ref range is beyond the end of the variant, so
                    // keep the variant.end and ASM_End the same
                    Pair(lastVariant.end,lastVariant.getAttributeAsInt("ASM_End",lastVariant.end))
                }
                strands.first == "+" -> {
                    val offset = positions.second - lastVariant.start
                    // RefRanges ends is before the lastVariant.end
                    // We need to offset the ASM_Start, by the difference between the position and the variant start
                    Pair(positions.second,lastVariant.getAttributeAsInt("ASM_End",lastVariant.start) + offset)
                }
                strands.first == "-" -> {
                    val offset = positions.second - lastVariant.start
                    // variant end at or beyond the ref range end.  Move the ASM_Start by the offset
                    Pair(positions.second,lastVariant.getAttributeAsInt("ASM_Start",firstVariant.end) - offset)
                }
                else -> {
                    // set them explicity
                    val newASMEnd = if(strands.second == "+") lastVariant.getAttributeAsInt("ASM_End",positions.second)
                    else lastVariant.getAttributeAsInt("ASM_Start",positions.second)
                    Pair(lastVariant.start,newASMEnd)

                }
            }

        } else {
            // not resizable so only change the ASM_* values
            val newASMEnd = if(strands.second == "+") lastVariant.getAttributeAsInt("ASM_End",positions.second)
            else lastVariant.getAttributeAsInt("ASM_Start",positions.second)
            refAsmPos_last = Pair(lastVariant.start,newASMEnd)
        }
        updatedPositions.add(refAsmPos_last)
        return updatedPositions

    }

}