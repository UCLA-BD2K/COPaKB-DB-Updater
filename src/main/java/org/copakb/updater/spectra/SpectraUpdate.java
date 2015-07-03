package org.copakb.updater.spectra;

import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.PeptideDAO;
import org.copakb.server.dao.ProteinDAO;
import org.copakb.server.dao.model.*;
import org.copakb.updater.protein.ProteinUpdate;
import uk.ac.ebi.kraken.interfaces.uniprot.UniProtEntry;
import uk.ac.ebi.kraken.uuw.services.remoting.EntryRetrievalService;
import uk.ac.ebi.kraken.uuw.services.remoting.UniProtJAPI;

import java.io.*;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by vincekyi on 6/11/15.
 */
public class SpectraUpdate {

    private static String SPECTRUM_OUTPUT_PATH = "target/Spectra_Files/";

    private static int specNumCounter = 0;
    private static int reverseCounter = 0;

    //parameters subject to change
    public static void update(String file, int mod_id, String instr, String enzyme){

        Date dateBeg = new Date();

        PeptideDAO peptideDAO = DAOObject.getInstance().getPeptideDAO();
        ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();

        LibraryModule tempLibMod = null;
        String organelle = "";
        String libmod = "";
        if(mod_id != -1) {
            tempLibMod = peptideDAO.searchLibraryModuleWithId(mod_id);
        }
        if(mod_id == -1 || tempLibMod == null){
            String[] shortFileName = file.split("/");
            String shortName = shortFileName[shortFileName.length-1];
            shortName = shortName.substring(0, shortName.length()-5);
            String[] parsedShortName = shortName.split("_");
            String parseShortName = parsedShortName[parsedShortName.length-1];

            try { // last section of file name is an integer, truncate
                Integer.parseInt(parseShortName);
                organelle = parsedShortName[parsedShortName.length-2];
                for(int i = 0; i < parsedShortName.length-1; i++) {
                    libmod += parsedShortName[i] + "_";
                }
                libmod = libmod.substring(0, libmod.length()-1);
            }
            catch (Exception e) { // last section of file name is not an integer, must be organelle
                if(e instanceof NumberFormatException) {
                    organelle = parseShortName;
                    libmod = shortName;
                }
                else {
                    e.printStackTrace();
                }
            }

            String species = parsedShortName[0];
            // match to formatted species name
            species = species.substring(0,1).toUpperCase() + species.substring(1).toLowerCase();
            Species tempSpecies = proteinDAO.searchSpecies(species);
            if(tempSpecies == null) {
                tempSpecies = new Species(0, species, null, null);
                proteinDAO.addSpecies(tempSpecies);
            }

            Date date = new Date();

            // add library module according to file name
            // assumes format as [organism]_[organ]_[organelle](_[date]).copa
            LibraryModule checkMod = peptideDAO.searchLibraryModuleWithModule(shortName);
            if(checkMod != null) {
                tempLibMod = checkMod;
            }
            else {
                tempLibMod = new LibraryModule(libmod, instr, organelle, date, enzyme, tempSpecies);
            }

            mod_id = peptideDAO.addLibraryModule(tempLibMod);
        }

        BufferedWriter writer = null;

        CopaParser cp = new CopaParser(file);
        HashMap copaEntry = null;
        while(cp.processEntry()>0) {
//            for(Object s : cp.getCurrentEntry().values())
//                System.out.println(s.toString());
            copaEntry = cp.getCurrentEntry();
            populateSpectraObject(copaEntry, mod_id, peptideDAO, proteinDAO, writer);
        }
        cp.closeBuffer();

        System.out.println("\n\n");
        System.out.println("BEGINNING: " + dateBeg.toString());
        Date dateEnd = new Date();
        System.out.println("ENDING: " + dateEnd.toString());

    }

    private static void populateSpectraObject(HashMap entry, int mod_id, PeptideDAO peptideDAO, ProteinDAO proteinDAO, BufferedWriter writer){

        System.out.println("\n\n********************");
        System.out.println("PEPID: " + (String)entry.get("PEPID"));

        String whole_sequence = (String)entry.get("SEQ");
        String ptm_sequence = whole_sequence.substring(2, whole_sequence.length() - 2);

        ArrayList<String> variations = getVariations(ptm_sequence);
        for(String s: variations) {
            specNumCounter++;

            if(!((String) entry.get("REVERSE")).equals("NotReverseHit")) {
                reverseCounter++;
                continue; // skip, don't add this peptide if reverse
            }

            // populate peptide object
            Peptide peptide = new Peptide();
            String peptide_sequence = extractPeptide(ptm_sequence);
            peptide.setPeptide_sequence(peptide_sequence);
            peptide.setSequence_length(peptide_sequence.length());

            // populate spectrum object
            Spectrum spectrum = new Spectrum();
            spectrum.setPtm_sequence(s);
            int charge = Integer.parseInt((String)entry.get("CHARGE"));
            spectrum.setCharge_state(charge);
            spectrum.setXcorr(Double.parseDouble((String) entry.get("XCORR")));
            spectrum.setDelta_cn(Double.parseDouble((String) entry.get("DELTACN")));
            spectrum.setZscore(Double.parseDouble((String) entry.get("ZSCORE")));
            spectrum.setPrecursor_mz(Double.parseDouble((String) entry.get("MZ")));
            spectrum.setRawfile_id((String) entry.get("SPECTRUMFILE"));
            double fdr = ((double)reverseCounter)/((double)specNumCounter);
            spectrum.setFdr(fdr);

            double[] arr = calcMWandPrecursor(ptm_sequence, charge);
            spectrum.setTh_precursor_mz(arr[1]);
            peptide.setMolecular_weight(arr[0]);

            PTM_type tempType = peptideDAO.searchPtmType(parsePtmSequence(ptm_sequence));
            spectrum.setPtm(tempType);

            LibraryModule tempLibMod = peptideDAO.searchLibraryModuleWithId(mod_id);
            spectrum.setModule(tempLibMod);

            // temp values
            Peptide tempPep = peptideDAO.searchBySequence(ptm_sequence);
            if(tempPep == null)
            {
                tempPep = new Peptide(ptm_sequence, 0.01, 15);
                peptideDAO.addPeptide(tempPep);
            }

            spectrum.setPeptide(tempPep);
            int specNum = peptideDAO.addSpectrum(spectrum);

            // create and save spectrum files; currently hardcoded the location
            new File(SPECTRUM_OUTPUT_PATH).mkdir();
            String fileName = SPECTRUM_OUTPUT_PATH + specNum + ".txt";
            try {
                writer = new BufferedWriter(new FileWriter(new File(fileName)));
                writer.write((String) entry.get("header") + "\n");
                writer.write((String) entry.get("spectrum"));
                writer.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            // Spectrum Protein
            EntryRetrievalService entryRetrievalService = UniProtJAPI.factory.getEntryRetrievalService();
            UniProtEntry uniProtEntry = null;

            SpectrumProtein sp = new SpectrumProtein();
            sp.setPrevAA(whole_sequence.charAt(0));
            sp.setNextAA(whole_sequence.charAt(whole_sequence.length() - 1));
            String proteins = (String)entry.get("UNIPROTIDS");

            ProteinCurrent prot = null;
            String protSeq = null;
            int loc;

            String[] tokens = proteins.split(";");
            for (String token : tokens) {
                System.out.println("Token " + token);
                prot = proteinDAO.searchByID(token);
                if(prot == null) {
                    try { // use uniprot.org to find protein sequence if not in database
                        uniProtEntry = (UniProtEntry) entryRetrievalService.getUniProtEntry(token);
                        if(uniProtEntry == null) {
                            System.out.println("Uniprot Entry does not exist!");
                            continue;
                        }
                        protSeq = uniProtEntry.getSequence().getValue();

                    }
                    catch (Exception e) {
                        System.out.println("Uniprot did not retrieve " + token + "\t" + e.toString() + e.getMessage());
                        continue;
                    }
                }
                else {
                    protSeq = prot.getSequence();
                }

                String tempPtmSeq = ptm_sequence.replaceAll("[^A-Za-z]", "");
                loc = protSeq.indexOf(tempPtmSeq);

                prot  = new ProteinCurrent();
                prot.setProtein_acc(token);
                sp.setProtein_acc(prot.getProtein_acc());

                sp.setLocation(loc);
                sp.setLibraryModule(tempLibMod);
                sp.setFeature_peptide(true);
                sp.setSpecies_unique(true);
                Spectrum tempSpec = peptideDAO.searchSpectrum(spectrum.getPtm_sequence(), spectrum.getModule().getMod_id(), spectrum.getCharge_state());
                sp.setSpectrum(tempSpec);
                sp.setPeptide(tempPep);
                proteinDAO.addSpectrumProtein(sp);
            }
            //need to get spectrum_id
            //may need to add the spectrum entry to the database first to get the spectrum_id


        }
        //todo: for later (after all modules are inserted)...calculate unique_peptide, feature_peptide, and fdr
    }

    private void updateUniqueAndFeatureStates() {

    }

    private static ArrayList<String> getVariations(String sequence){

        ArrayList<String> variations = new ArrayList<String>();
        variations.add(sequence);
        if(sequence.contains("Z") || sequence.contains("B") || sequence.contains("J")){
            // if Z is found
            for(int i = sequence.indexOf("Z", 0); i >= 0; i = sequence.indexOf("Z", i+1)){
                int len = variations.size();
                for(int j = 0; j < len; j++){
                    variations.add(variations.get(j).substring(0, i)+"E"+variations.get(j).substring(i+1));
                    variations.add(variations.get(j).substring(0, i)+"Q"+variations.get(j).substring(i+1));
                    variations.remove(j);
                    j--;
                    len--;
                }
            }
            // if B is found
            for(int i = sequence.indexOf("B", 0); i >= 0; i = sequence.indexOf("B", i+1)){
                int len = variations.size();
                for(int j = 0; j < len; j++){
                    variations.add(variations.get(j).substring(0, i)+"D"+variations.get(j).substring(i+1));
                    variations.add(variations.get(j).substring(0, i)+"N"+variations.get(j).substring(i+1));
                    variations.remove(j);
                    j--;
                    len--;
                }
            }
            // if J is found
            for(int i = sequence.indexOf("J", 0); i >= 0; i = sequence.indexOf("J", i+1)){
                int len = variations.size();
                for(int j = 0; j < len; j++){
                    variations.add(variations.get(j).substring(0, i)+"L"+variations.get(j).substring(i+1));
                    variations.add(variations.get(j).substring(0, i)+"I"+variations.get(j).substring(i+1));
                    variations.remove(j);
                    j--;
                    len--;
                }
            }
        }

        return variations;

    }

    private static String extractPeptide(String ptm_sequence){
        String peptide = "";
        int i, start = 0, end = 0;
        int len = ptm_sequence.length();
        for(i = 0; i < len; i++){
            // look for open parentheses
            if(ptm_sequence.charAt(i)=='('){
                end = i;
                peptide += ptm_sequence.substring(start, end);
                // skip to end parentheses
                int j;
                for(j = i; ptm_sequence.charAt(j)!=')' &&
                        j < len; j++) {
                }
                i = j;
                start = j+1;
            }
        }
        if(start < len)
            peptide += ptm_sequence.substring(start, len);
        return peptide;
    }


    private static void storeSpectraInFile(String spectra, String filename){
        Writer writer = null;

        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(filename), "utf-8"));
            writer.write(spectra);
        } catch (IOException ex) {
            ex.printStackTrace();
            // report
        } finally {
            try {writer.close();} catch (Exception ex) {/*ignore*/}
        }
    }

    private static double[] calcMWandPrecursor(String sequence, double charge){

        double result[] = {0, 0};
        // initialize molecular weight and theoretical precursor
        double MW = 0, th_mz = 0;

        // holds the sequence so it can be modified
        StringBuffer buffer = new StringBuffer(sequence);
        int length = buffer.length();

        // loop through each character and add to molecular weight depending on amino acid
        for (int i = 0; i < length; i++)
        {
            char c = sequence.charAt(i);
            switch (c)
            {
                case 'A':
                    MW += 71.03711;
                    th_mz += 71.03711;
                    break;
                case 'C':
                    MW += 103.00919;
                    th_mz += 103.00919;
                    //buffer.insert(i+1, "(57.02146)");
                    break;
                case 'D':
                    MW += 115.02694;
                    th_mz += 115.02694;
                    break;
                case 'E':
                    MW += 129.04259;
                    th_mz += 129.04259;
                    break;
                case 'F':
                    MW += 147.06841;
                    th_mz += 147.06841;
                    break;
                case 'G':
                    MW += 57.02146;
                    th_mz += 57.02146;
                    break;
                case 'H':
                    MW += 137.05891;
                    th_mz += 137.05891;
                    break;
                case 'I':
                    MW += 113.08406;
                    th_mz += 113.08406;
                    break;
                case 'K':
                    MW += 128.09496;
                    th_mz += 128.09496;
                    break;
                case 'L':
                    MW += 113.08406;
                    th_mz += 113.08406;
                    break;
                case 'M':
                    MW += 131.04048;
                    th_mz += 131.04048;
                    break;
                case 'N':
                    MW += 114.04293;
                    th_mz += 114.04293;
                    break;
                case 'P':
                    MW += 97.05276;
                    th_mz += 97.05276;
                    break;
                case 'Q':
                    MW += 128.05858;
                    th_mz += 128.05858;
                    break;
                case 'R':
                    MW += 156.10111;
                    th_mz += 156.10111;
                    break;
                case 'S':
                    MW += 87.03202;
                    th_mz += 87.03202;
                    break;
                case 'T':
                    MW += 101.04768;
                    th_mz += 101.04768;
                    break;
                case 'V':
                    MW += 99.06841;
                    th_mz += 99.06841;
                    break;
                case 'W':
                    MW += 186.07931;
                    th_mz += 186.07931;
                    break;
                case 'Y':
                    MW += 163.06333;
                    th_mz += 163.06333;
                    break;
                case '(':
                    int end = buffer.indexOf(")", i);
                    String value = buffer.substring(i+1, end);
                    try{
                        th_mz += Double.parseDouble(value);
                    }catch(NumberFormatException ex){
                        //do nothing
                    }
                    break;
                default:
                    break;
            }//end switch
        }//end for loop

        // return molecular weight
        result[0] = MW + 18.0152;

        // return the (molecular weight + weight of water + charge)/charge
        result[1] = (th_mz + 18.0152 + charge)/(double)charge;
        //System.out.println(result[1]);
        return result;
    }

//            1	Carbamidomethylation	C,K,H	57.02000
//            2	Acetylation	K,N-term	42.01000
//            4	Oxidation	M	15.99000
//            8	Phosphorylation	S,T	79.97000
//            16	Succinylation	K	100.01860
//            32	Propionamide	C	71.03712
//            64	Pyro-carbamidomethyl	C	39.99492
//            128	Pyro-glu	E	-17.03000

    private static int parsePtmSequence(String ptm_sequence) {
        int result = 0;
        double range = 0.01;

        boolean carb = false, acet = false, oxid = false, phos = false, succ = false, prop = false, pyroc = false, pyrog = false;

        String tempPtmType = "";
        for(char aa : ptm_sequence.toCharArray()) {
            if(Character.isLetter(aa)) {
                continue;
            }

            if(aa == '(') {
                tempPtmType = "";
            }

            // at end of sequence, check the ptm type based on the value and add to total type number
            if(aa == ')' && tempPtmType.length() > 1) {
                double ptmVal = Double.parseDouble(tempPtmType);
                System.out.println(ptmVal);
                if(withinRange(ptmVal, 57.020000, range)) { // Carbamidomethylation
                    if(!carb)
                        result += 1;
                    carb = true;
                }
                else if(withinRange(ptmVal, 42.01000, range)) { // Acetylation
                    if(!acet)
                        result += 2;
                    acet = true;
                }
                else if(withinRange(ptmVal, 15.99000, range)) { // Oxidation
                    if(!oxid)
                        result += 4;
                    oxid = true;
                }
                else if(withinRange(ptmVal, 79.97000, range)) { // Phosphorylation
                    if(!phos)
                        result += 8;
                    phos = true;
                }
                else if(withinRange(ptmVal, 100.01860, range)) { // Succinylation
                    if(!succ)
                        result += 16;
                    succ = true;
                }
                else if(withinRange(ptmVal, 71.03712, range)) { // Propionamide
                    if(!prop)
                        result += 32;
                    prop = true;
                }
                else if(withinRange(ptmVal, 39.99492, range)) { // Pyro-carbamidomethyl
                    if(!pyroc)
                        result += 64;
                    pyroc = true;
                }
                else if(withinRange(ptmVal, -17.03000, range)) { // Pyro-glu
                    if(!pyrog)
                        result += 128;
                    pyrog = true;
                }
                else {
                    continue;
                }
            }

            // add ptm number together
            if(Character.isDigit(aa) || aa == '.') {
                tempPtmType += aa;
            }
        }

        //System.out.println(result);
        return result;
    }

    public static boolean withinRange(double num, double refNum, double range) {
        if((num <= (refNum+range)) && (num >= (refNum-range)))
            return true;
        return false;
    }

    /**
     * temporary method for adding ptm types
     */
    public static void addPtm_Types() {
//            1	Carbamidomethylation	C,K,H	57.02000
//            2	Acetylation	K,N-term	42.01000
//            4	Oxidation	M	15.99000
//            8	Phosphorylation	S,T	79.97000
//            16	Succinylation	K	100.01860
//            32	Propionamide	C	71.03712
//            64	Pyro-carbamidomethyl	C	39.99492
//            128	Pyro-glu	E	-17.03000

        PeptideDAO peptideDAO = DAOObject.getInstance().getPeptideDAO();
        HashMap<Integer, Double> map = new HashMap<Integer, Double>(8);
        map.put(1, 57.02000);
        map.put(2, 42.01000);
        map.put(4, 15.99000);
        map.put(8, 79.97000);
        map.put(16, 100.01860);
        map.put(32, 71.03712);
        map.put(64, 39.99492);
        map.put(128, -17.03000);

        HashMap<Integer, String> map2 = new HashMap<Integer, String>(8);
        map2.put(1, "Carbamidomethylation;");
        map2.put(2, "Acetylation;");
        map2.put(4, "Oxidation;");
        map2.put(8, "Phosphorylation;");
        map2.put(16, "Succinylation;");
        map2.put(32, "Propionamide;");
        map2.put(64, "Pyro-carbamidomethyl;");
        map2.put(128, "Pyro-glu;");

        HashMap<Integer, String> map3 = new HashMap<Integer, String>(8);
        map3.put(1, "C,K,H;");
        map3.put(2, "K,N-term;");
        map3.put(4, "M;");
        map3.put(8, "S,T;");
        map3.put(16, "K;");
        map3.put(32, "C;");
        map3.put(64, "C;");
        map3.put(128, "E;");

        String binary = "";
        PTM_type tempPtmType = null;
        String mod = "";
        String res = "";
        double mass = 0.0;
        int counter = 0;
        int key = 0;
        for(int i = 1; i <= 255; i++) {
            mod = "";
            res = "";
            mass = 0.0;
            counter = 0;
            key = 0;

            binary = Integer.toString(i,2);
            System.out.println(binary);
            char[] arr = binary.toCharArray();
            for(int x = arr.length-1; x >= 0; x--) {
                char a = arr[x];
                if(a == '1') {
                    key = (int) Math.pow(2, counter);
                    System.out.println(key);
                    mod += map2.get(key);
                    res += map3.get(key);
                    mass += map.get(key);
                }
                counter++;
            }
            mod = mod.substring(0, mod.length()-1);
            res = res.substring(0, res.length()-1);

            tempPtmType = new PTM_type(i, mod, res, mass, null);
            peptideDAO.addPtmType(tempPtmType);
        }
    }

    public static void main(String[] args) {
        /*String s = "p1;";
        String[] tokens = s.split(";");
        for (String token : tokens) {
            System.out.println(token);
        }*/

        update("./src/main/resources/mouse_heart_nuclei.copa", -1, "LTQ", "Trypsin");

        //parsePtmSequence("(42.0106)VNKVIEINPYLLGTM(15.9949)SGCAADCQYWER");

        //addPtm_Types();
    }
}
