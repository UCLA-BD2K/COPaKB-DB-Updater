package org.copakb.updater;

import org.apache.commons.cli.*;
import org.copakb.updater.disease.DiseaseUpdate;
import org.copakb.updater.hpa.HPA_Update;
import org.copakb.updater.protein.ProteinUpdate;
import org.copakb.updater.spectra.SpectraUpdate;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;

/**
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
                .argName("file mod_id instr enzyme")
                .build();
        optSpectra.setArgs(4);
        optionGroup.addOption(optSpectra);

        Option optHelp = Option.builder("h")
                .longOpt("help")
                .desc("Show usage")
                .build();
        optionGroup.addOption(optHelp);

        options.addOptionGroup(optionGroup);

        // Create help text
        HelpFormatter formatter = new HelpFormatter();
        String header = PROGRAM_DESCRIPTION  + "\n\n";
        String footer = "\n";

        // Parse command line arguments
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            formatter.printHelp(PROGRAM_NAME, header, options, footer, true);
            return ;
        }

        // Process arguments
        if (cmd.hasOption(optHelp.getOpt())) {
            formatter.printHelp(PROGRAM_NAME, header, options, footer, true);
            return ;
        }

        if (cmd.hasOption(optDisease.getOpt())) {
            DiseaseUpdate.update();
            return ;
        }

        if (cmd.hasOption(optHPA.getOpt())) {
            HPA_Update.update(cmd.getOptionValue(optHPA.getOpt()));
            return ;
        }

        if (cmd.hasOption(optProteins.getOpt())) {
            ProteinUpdate.updateFromFasta(cmd.getOptionValue(optProteins.getOpt()));
            return ;
        }

        if (cmd.hasOption(optSpectra.getOpt())) {
            SpectraUpdate.update(cmd.getOptionValue(optSpectra.getValue(0)),
                    Integer.valueOf(optSpectra.getValue(1)),
                    optSpectra.getValue(2),
                    optSpectra.getValue(3));
            return ;
        }
    }
}
