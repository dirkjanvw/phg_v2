# PHG version 2
The Practical Haplotype Graph (PHG) is a powerful for representing pangenomes.  The PHG is optimized for the plant breeding and genetics context, where genomic diversity can be high and where imputation with low density markers is essential for breeding.

The PHG is a trellis graph based representation of genic and intergenic regions (called reference ranges) which represent diversity across and between samples. It can be used to: create custom genomes for alignment, call rare alleles, impute genotypes, and efficiently store genomic data from many lines (i.e. reference, assemblies, and other lines). The PHG also works well with community standards including the Breeding API [BrAPI](https://brapi.org) and powerful tools for R such as [rPHG](https://github.com/maize-genetics/rPHG) for pangenome extraction and [rTASSEL](https://github.com/maize-genetics/rTASSEL) for connecting genotype to phenotype.

[PHGv1](https://bitbucket.org/bucklerlab/practicalhaplotypegraph/wiki/Home) was [published in 2022](https://doi.org/10.1093/bioinformatics/btac410) and solved many of the issues in aligning diverse genomes, efficient storage, and imputing across a pangenome.  However, it relied on a custom relational database that required custom formats and it did not scale well with large numbers of taxa and rare alleles. Additionally, after developing PHGs for six species, we realized there were substantial opportunities to streamline the platform for curation.

# PHGv2 design
The redesign leverages the powerful TileDB-VCF database, which is widely used in human genetics for extensive medical applications and is highly performant for rapid querying and storage of rare variants. The PHG is now backed by two TileDB-VCF databases: one for tracking haplotypes across all samples (h.vcf), and another for tracking variants relative to either the reference genomes or the closest haplotype (g.vcf). Our implementation of haplotype encoding in VCF heavily relies on the VCF ALT haplotype specification [v4.3](http://samtools.github.io/hts-specs/VCFv4.3.pdf).

* High-quality phased genome assemblies (or similar) are available to initialize the PHG.
* Ancestral haplotypes are aligned to the reference genome for the identification of haplotypes.
* All PHG tools rely on public file standards - fasta, vcf, bcf, bed, and maf.
* We rely on public tools like TileDB, Minimap2, GATK, AnchorWave, BioKotlin, and HTSJDK.
* Genotyping with low-density markers is now done using a memory- and speed-efficient kmer approach, followed by pathfinding (imputation) with HMM, BWT, or our ML model.
* Rare allele discovery with short reads is based on the above path, involving short read alignment to the inferred haplotype path genome and the GATK haplotype caller.

# PHG terminoloy


# Example usage
To populate that database
```
## Initialize DBs
./phg initdb /path/to/dbs

## Build VCF data
./phg create-ranges --gff my.gff --boundary gene --pad 500 -o /path/to/bed/file.bed
./anchorwave (ref.fasta, asm.fasta) -o /path/to/maf/files/
./phg build-ref-vcf --bed /my/bed/file.bed --reference /my/ref.fasta -o /path/to/vcfs
./phg build-maf-vcf --maf /my/maf/files -o /path/to/vcfs

## Load data into DBs
./phg load-vcf --vcf /my/vcf/dir --dbpath /my/db/uri
```

```
## Index
./phg index-kmers --ancestor founder.h.vcf -o kmer_index.map // we need this

## Map
./phg map-kmers \
    --kmer-index kmer_index.map \
    --reads my_reads.fastq \ // possibly thousands of samples being inputted
    --output read_count_out.map \ // could we pipe this into impute method? // thousands of outputs
    // consider batch interface here ^^

## Impute
./phg impute \
    --hap-counts read_count_out.map \ // will users understand the di
    --diploid false \
    --ancestor founder.h.vcf \
    --max-anc-hap-num 20 \
    --max-anc-hap-prop 0.95 \
    --output-parent best_parents.txt \
    -o my_impute.h.vcf

## Load
./phg load-vcf --vcf my_impute.vcf --dbpath /my/db/uri
```
