package org.copakb.updater.spectra;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * COPA data structure.
 * Created by Alan on 8/25/2015.
 */
public class CopaEntry {
    private Map<String, String> fields;
    private List<double[]> peaks;

    public CopaEntry() {
        this.fields = new HashMap<>();
        this.peaks = new ArrayList<>();
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public List<double[]> getPeaks() {
        return peaks;
    }

    /**
     * Parses the header from a COPA file and populates the fields map.
     * @param header Header string to parse.
     */
    public void processHeader(String header) {
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

    public void addPeak(String peak) {
        String values[] = peak.split(" ");
        peaks.add(new double[]{Double.valueOf(values[0]), Double.valueOf(values[1])});
    }
}
