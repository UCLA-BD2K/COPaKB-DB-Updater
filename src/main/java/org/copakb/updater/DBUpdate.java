package org.copakb.updater;

import org.apache.commons.cli.*;
import org.copakb.updater.disease.DiseaseUpdate;
import org.copakb.updater.hpa.HPAUpdate;
import org.copakb.updater.protein.ProteinUpdate;
import org.copakb.updater.spectra.SpectraUpdate;

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
                .argName("file> [module ID] <instrument> <enzyme")
                .build();
        optionGroup.addOption(optSpectra);

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

        // Default or help
        formatter.printHelp(PROGRAM_NAME, header, options, footer, true);
    }
}
