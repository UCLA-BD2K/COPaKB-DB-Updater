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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by vincekyi on 6/11/15.
 */
public class SpectraUpdate {

    //parameters subject to change
    public static void update(String file, int mod_id, String instr, String enzyme){

        DAOObject obj = new DAOObject();
        PeptideDAO peptideDAO = obj.getPeptideDAO();
        ProteinDAO proteinDAO = obj.getProteinDAO();

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

            LibraryModule checkMod = peptideDAO.searchLibraryModuleWithModule(shortName);
            if(checkMod != null) {
                tempLibMod = checkMod;
            }
            else {
                tempLibMod = new LibraryModule(libmod, instr, organelle, date, enzyme, tempSpecies);
            }

            mod_id = peptideDAO.addLibraryModule(tempLibMod);
        }

        CopaParser cp = new CopaParser(file);
        HashMap copaEntry = null;
        while(cp.processEntry()>0) {
//            for(Object s : cp.getCurrentEntry().values())
//                System.out.println(s.toString());
            copaEntry = cp.getCurrentEntry();
            populateSpectraObject(copaEntry, mod_id, peptideDAO, proteinDAO);
        }
        cp.closeBuffer();

    }

    private static void populateSpectraObject(HashMap entry, int mod_id, PeptideDAO peptideDAO, ProteinDAO proteinDAO){

        String whole_sequence = (String)entry.get("SEQ");
        String ptm_sequence = whole_sequence.substring(2, whole_sequence.length() - 2);

        ArrayList<String> variations = getVariations(ptm_sequence);
        for(String s: variations) {

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
            spectrum.setRawfile_id((String)entry.get("SPECTRUMFILE"));

            double[] arr = calcMWandPrecursor(ptm_sequence, charge);
            spectrum.setTh_precursor_mz(arr[1]);
            peptide.setMolecular_weight(arr[0]);

            //todo: store spectrum in file; use storeSpectraInFile function
            //todo: calculate ptm
                //uses info below; ask howard on how ptm is calculated
                //will also need to populate ptm_type table
//            1	Carbamidomethylation	C,K,H	57.02000
//            2	Acetylation	K,N-term	42.01000
//            4	Oxidation	M	15.99000
//            8	Phosphorylation	S,T	79.97000
//            16	Succinylation	K	100.01860
//            32	Propionamide	C	71.03712
//            64	Pyro-carbamidomethyl	C	39.99492
//            128	Pyro-glu	E	-17.03000
            PTM_type tempType = peptideDAO.searchPtmType(1);
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
            peptideDAO.addSpectrum(spectrum);

            // Spectrum Protein
            EntryRetrievalService entryRetrievalService = UniProtJAPI.factory.getEntryRetrievalService();
            UniProtEntry uniProtEntry = null;

            SpectrumProtein sp = new SpectrumProtein();
            sp.setPrevAA(whole_sequence.charAt(0));
            sp.setNextAA(whole_sequence.charAt(whole_sequence.length() - 1));
            String proteins = (String)entry.get("UNIPROTIDS");

            ProteinCurrent prot;
            String protSeq;
            int loc;

            String[] tokens = proteins.split(";");
            for (String token : tokens) {
                prot = proteinDAO.searchByID(token);
                if(prot == null) {
                    try { // use uniprot.org to find protein sequence if not in database
                        uniProtEntry = (UniProtEntry) entryRetrievalService.getUniProtEntry(token);
                        protSeq = uniProtEntry.getSequence().getValue();
                        prot = ProteinUpdate.retrieveDataFromUniprot(uniProtEntry, proteinDAO);
                        if(prot != null) {
                            prot.setProtein_acc(token);
                            // works if not automatically added to the database. does it need to?
                            //proteinDAO.addProteinCurrent(prot);
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        continue;
                    }
                }
                else {
                    protSeq = prot.getSequence();
                }
                loc = protSeq.indexOf(ptm_sequence);

                sp.setProtein_acc(prot.getProtein_acc());
                sp.setLocation(loc);
                sp.setLibraryModule(tempLibMod);
                sp.setFeature_peptide(true);
                sp.setSpecies_unique(true);
                Spectrum tempSpec = peptideDAO.searchSpectrum(spectrum.getPtm_sequence(), spectrum.getModule().getMod_id(), spectrum.getCharge_state());
                sp.setSpectrum(tempSpec);
                proteinDAO.addSpectrumProtein(sp);
            }
            //need to get spectrum_id
            //may need to add the spectrum entry to the database first to get the spectrum_id


        }
        //todo: for later (after all modules are inserted)...calculate unique_peptide, feature_peptide, and fdr
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

    public static void main(String[] args) {
        /*String s = "p1;";
        String[] tokens = s.split(";");
        for (String token : tokens) {
            System.out.println(token);
        }*/

        update("./src/main/resources/mouse_heart_test_mitochondria.copa", -1, "LTQ", "Trypsin");
    }
}
