package org.copakb.updater.spectra;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

/**
 * COPA parser.
 * Created by vincekyi on 6/11/15.
 */
public class CopaParser {
    private BufferedReader reader;

    public CopaParser() {

    }

    public CopaParser(String filename) {
        open(filename);
    }

    /**
     * Opens a new file to parse.
     *
     * @param file File to open.
     */
    public void open(String file) {
        // Close previous file if open
        close();

        // Initialize file reader
        // Check for .copa extension
        if (!file.substring(file.length() - 4).equals("copa")) {
            throw new RuntimeException("File type must have .copa extension.");
        }

        try {
            reader = new BufferedReader(new FileReader(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("File not found.");
        }
    }

    /**
     * Closes any open files.
     */
    public void close() {
        if (reader != null) {
            try {
                reader.close();
                reader = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Returns the next CopaEntry in the file.
     *
     * @return CopaEntry object; null if not found.
     */
    public CopaEntry next() {
        if (reader == null) {
            return null;
        }

        try {
            String line = reader.readLine();
            if (line != null) {
                // Restore H prefix from previous read if necessary
                if (line.charAt(0) != 'H') {
                    line = "H" + line;
                }

                CopaEntry entry = new CopaEntry();
                // Process header fields
                entry.processHeader(line);
                // Process peaks
                int firstChar;
                while ((firstChar = reader.read()) != -1 && (char) firstChar != 'H') {
                    line = reader.readLine();
                    entry.addPeak((char) firstChar + line);
                }

                return entry;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }
}
