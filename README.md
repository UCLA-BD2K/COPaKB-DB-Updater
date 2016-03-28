COPaKB-DB-Updater
===================
	BD2K Center of Excellence for Big Data Computing at UCLA

	Alan Kha            akhahaha@gmail.com
	Hannah Chou         hannahjchou@gmail.com
-------------------------------------------------------------------------------
Overview
---------------
Updates the COPaKB database.

Usage
---------------
	usage: DBUpdater [-all <version description> <directory> <instrument>
           <enzyme> | -dis | -h | -hpa <file> | -prot <file> | -spec <file>
           [module ID] <instrument> <enzyme> | -specdir <directory>
           <instrument> <enzyme> | -ver <description>]
    Updates the COPaKB database.

     -all,--all <version description> <directory> <instrument> <enzyme>
                        Add/update version, proteins, spectra, and diseases
     -dis,--disease     Update diseases
     -h,--help          Show usage
     -hpa,--hpa <file>  Update HPA proteins from HPA XML file
     -prot,--proteins <file>
                        Update proteins from FASTA file
     -spec,--spectra <file> [module ID] <instrument> <enzyme>
                        Update Spectra from COPA file
     -specdir,--spectradirectory <directory> <instrument> <enzyme>
                        Update Spectra from COPA files in directory
     -ver,--version <description>
                        Add new version

Notes
---------------
- JUnit tests are not necessarily complete nor usable
