package org.copakb.updater;

import org.apache.commons.cli.*;
import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.model.Version;
import org.copakb.updater.disease.DiseaseUpdate;
import org.copakb.updater.hpa.HPAUpdate;
import org.copakb.updater.protein.ProteinUpdate;
import org.copakb.updater.spectra.SpectraUpdate;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;

/**
 * Main entry point for DB-Updater.
 * <p>
 * Created by Alan on 7/1/2015.
 */
public class DBUpdate {
    private final static String PROGRAM_NAME = "DBUpdater";
    private final static String PROGRAM_DESCRIPTION = "Updates the COPaKB database.";

    public static void main(String[] args) {
        // Create options
        Options options = new Options();
        OptionGroup optionGroup = new OptionGroup();

        Option optAll = Option.builder("all")
                .longOpt("all")
                .desc("Add/update version, proteins, spectra, and diseases")
                .hasArgs()
                .argName("version description> <directory> <instrument> <enzyme")
                .build();
        optionGroup.addOption(optAll);

        Option optVersion = Option.builder("ver")
                .longOpt("version")
                .desc("Add new version")
                .hasArgs()
                .argName("description")
                .build();
        optionGroup.addOption(optVersion);

        Option optDisease = Option.builder("dis")
                .longOpt("disease")
                .desc("Update diseases")
                .build();
        optionGroup.addOption(optDisease);

        Option optHPA = Option.builder("hpa")
                .longOpt("hpa")
                .desc("Update HPA proteins from HPA XML file")
                .hasArg()
                .argName("file")
                .build();
        optionGroup.addOption(optHPA);

        Option optProteins = Option.builder("prot")
                .longOpt("proteins")
                .desc("Update proteins from FASTA file")
                .hasArg()
                .argName("file")
                .build();
        optionGroup.addOption(optProteins);

        Option optSpectra = Option.builder("spec")
                .longOpt("spectra")
                .desc("Update Spectra from COPA file")
                .hasArgs()
                .argName("file> <module ID> <instrument> <enzyme")
                .build();
        optionGroup.addOption(optSpectra);

        Option optSpectraDir = Option.builder("specdir")
                .longOpt("spectradirectory")
                .desc("Update Spectra from COPA files in directory")
                .hasArgs()
                .argName("directory> <instrument> <enzyme")
                .build();
        optionGroup.addOption(optSpectraDir);

        Option optHelp = Option.builder("h")
                .longOpt("help")
                .desc("Show usage")
                .build();
        optionGroup.addOption(optHelp);

        options.addOptionGroup(optionGroup);

        // Create help text
        HelpFormatter formatter = new HelpFormatter();
        String header = PROGRAM_DESCRIPTION + "\n\n";
        String footer = "\n";

        // Parse command line arguments
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            formatter.printHelp(PROGRAM_NAME, header, options, footer, true);
            return;
        }

        if (cmd.hasOption(optAll.getOpt())) {
            String[] allArgs = cmd.getOptionValues(optAll.getOpt());

            // update version
            updateVersion(allArgs[0]);

            try {
                // go through all files in fasta folder
                System.out.println("Iterating through: " + allArgs[1] + "/fasta");
                Files.walk(Paths.get(allArgs[1] + "\\fasta")).forEach(filePath -> {
                    if (Files.isRegularFile(filePath)) {
                        // check if .copa spectrum files
                        if (filePath.toString().endsWith(".fasta")) {
                            System.out.println(filePath);
                            ProteinUpdate.updateFromFasta(".\\" + filePath.toString());
                        }
                    }
                });

                // go through all files in copa folder
                System.out.println("Iterating through: " + allArgs[1] + "/copa");
                Files.walk(Paths.get(allArgs[1] + "\\copa")).forEach(filePath -> {
                    if (Files.isRegularFile(filePath)) {
                        // check if .copa spectrum files
                        if (filePath.toString().endsWith(".copa")) {
                            System.out.println(filePath);
                            SpectraUpdate.update(".\\" + filePath.toString(),
                                    -1,
                                    allArgs[2],
                                    allArgs[3]);
                        }
                    }
                });


            }
            catch (Exception e) {
                e.printStackTrace();
                System.out.println("Cannot read files from directory: " + allArgs[1]);
            }

            return;
        }

        if (cmd.hasOption(optVersion.getOpt())) {
            updateVersion(cmd.getOptionValues(optVersion.getOpt())[0]);
            return;
        }

        if (cmd.hasOption(optDisease.getOpt())) {
            DiseaseUpdate.update();
            return;
        }

        if (cmd.hasOption(optHPA.getOpt())) {
            HPAUpdate.update(cmd.getOptionValue(optHPA.getOpt()));
            return;
        }

        if (cmd.hasOption(optProteins.getOpt())) {
            ProteinUpdate.updateFromFasta(cmd.getOptionValue(optProteins.getOpt()));
            return;
        }

        if (cmd.hasOption(optSpectra.getOpt())) {
            String[] spectraArgs = cmd.getOptionValues(optSpectra.getOpt());
            // Has Module ID
            if (spectraArgs.length == 4) {
                // Has module ID
                SpectraUpdate.update(spectraArgs[0],
                        Integer.valueOf(spectraArgs[1]),
                        spectraArgs[2],
                        spectraArgs[3]);
            } else if (spectraArgs.length == 3) {
                // Default module ID
                SpectraUpdate.update(spectraArgs[0],
                        -1,
                        spectraArgs[1],
                        spectraArgs[2]);
            }

            return;
        }

        if (cmd.hasOption(optSpectraDir.getOpt())) {
            String[] spectraDirArgs = cmd.getOptionValues(optSpectraDir.getOpt());
            try {
                // go through all files in folder
                Files.walk(Paths.get(spectraDirArgs[0])).forEach(filePath -> {
                    if (Files.isRegularFile(filePath)) {
                        // check if .copa spectrum files
                        if(filePath.toString().endsWith(".copa")) {
                            System.out.println(filePath);
                            //String fileName = filePath.toString().split("[\\\\/]")[filePath.toString().split("[\\\\/]").length - 1];
                            //System.out.println(fileName);
                            System.out.println("." + filePath);
                            SpectraUpdate.update(".\\" + filePath.toString(),
                                    -1,
                                    spectraDirArgs[1],
                                    spectraDirArgs[2]);
                        }
                    }
                });
            }
            catch (Exception e) {
                e.printStackTrace();
                System.out.println("Cannot read files from directory: " + spectraDirArgs[0]);
            }
            return;
        }

        // Default or help
        formatter.printHelp(PROGRAM_NAME, header, options, footer, true);
    }

    public static void updateVersion(String desc) {
        Version version = new Version();
        version.setVersion(DAOObject.getInstance().getProteinDAO().searchLatestVersion().getVersion() + 1);
        version.setDescription(desc);
        version.setDate(new Date());
        DAOObject.getInstance().getProteinDAO().addVersion(version);
    }
}
