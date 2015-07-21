package org.copakb.updater.spectra;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

/**
 * COPA parser
 * Created by vincekyi on 6/11/15.
 */
public class CopaParser {
    private HashMap<String, String> fields;
    private String spectraInfo;
    private String file;
    private BufferedReader reader;

    public CopaParser(String filename) {
        file = filename;
        fields = new HashMap<>();
        spectraInfo = "";
        initializeFile();
    }

    /**
     * Initializes the parser.
     *
     * @return Success or failure string.
     */
    private String initializeFile() {
        reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));

            // Check for .copa extension
            if (!file.substring(file.length() - 4).equals("copa")) {
                System.out.println("File type must be .copa!");
                return "File type must be .copa!";
            }
        } catch (FileNotFoundException ex) {
            System.out.println("Not a valid file!");
            return "Not a valid file!";
        }
        return "Success";
    }

    /**
     * Returns the map that holds the current entry's info information.
     * @return Current entry map.
     */
    public HashMap<String, String> getCurrentEntry() {
        return fields;
    }

    public String getSpectraInfo() {
        return spectraInfo;
    }

    /**
     * Parses through the header and splits it into fields
     * @param header Header string to parse.
     */
    private void processHeader(String header) {
        // Isolate each field
        String tokens[] = header.split("\\|\\|\\|");
        for (int i = 1; i < tokens.length; i++) {
            String field[] = tokens[i].split(":::");
            if (field.length == 1) {
                fields.put(field[0], "");
            } else {
                fields.put(field[0], field[1]);
            }
        }
    }

    public void closeBuffer() {
        try {
            if (reader != null)
                reader.close();
        } catch (IOException ex) {
            // Do nothing
        }
    }

    /**
     * Processes each entry of data in the COPA file.
     *
     * @return 1 if processed correctly and has a following entry, 0 if processed correctly and has no follow entries,
     * and -1 if no entry to process.
     */
    public int processEntry() {
        try {
            // Read in the first line and check if it contains header
            String line = reader.readLine();
            if (line != null) {
                if (line.charAt(0) == 'H') {
                    fields.put("header", line);
                } else {
                    fields.put("header", "H" + line);
                }
                processHeader(line);

                // Store the string of spectra points
                StringBuilder spectra = new StringBuilder();

                // Append to spectra if first character of line is part of the entry
                int firstChar;
                while ((firstChar = reader.read()) != -1 && (char) firstChar != 'H') {
                    line = reader.readLine();
                    spectra.append((char) firstChar);
                    spectra.append(line).append("\n");
                }
                fields.put("spectrum", spectra.toString());

                if ((char) firstChar == 'H') {
                    return 1; // Processed entry, but there's more entries
                } else {
                    return 0; // Processed entry, and there's no more after
                }
            }
        } catch (IOException ex) {
            System.out.println("IO Exception!");
        }

        // No more entries
        return -1;
    }
}
