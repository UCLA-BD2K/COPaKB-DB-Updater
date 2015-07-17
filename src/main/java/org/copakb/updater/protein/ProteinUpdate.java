package org.copakb.updater.protein;

import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.ProteinDAO;
import org.copakb.server.dao.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

/**
 * Update proteins from FASTA or UniProt IDs.
 * Created by vincekyi on 5/26/15.
 */
public class ProteinUpdate {
    private static final int CHAR_BUFFER_SIZE = 8192;
    private static final String ENSEMBL_BASE_URL = "http://rest.ensembl.org/lookup/id/";
    private static final String UNIPROT_BASE_URL = "http://www.uniprot.org/uniprot/";

    private static final Boolean PRINT_FAILED = true;
    private static final String PRINT_FAILED_PATH = "target/uniprot_failed.txt";

    /**
     * Updates ProteinCurrent table given a FAST file.
     *
     * @param filepath FASTA file.
     */
    public static void updateFromFasta(String filepath) {
        try {
            FileInputStream inputStream = new FileInputStream(filepath);
            Scanner sc = new Scanner(inputStream, "UTF-8");

            PrintWriter writer = null;
            if (PRINT_FAILED) {
                writer = new PrintWriter(PRINT_FAILED_PATH, "UTF-8");
            }

            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                // Check if header line
                if (line.startsWith(">")) {
                    // Get UniProt ID from header line
                    String uniprotID = line.substring(4, line.indexOf("|", 4));

                    // Get protein
                    ProteinCurrent protein;
                    try {
                        protein = getProteinFromUniprot(uniprotID);

                        int check = checkAndUpdateHistories(uniprotID, protein);

                        if (check == 0) { // already exists and no changes
                            System.out.println(uniprotID + " is up to date.");
                        } else if (check == 1) { // updated protein and protein history
                            System.out.println(uniprotID + " was updated.");
                        } else if (check == 2) { // exists in db but was deleted from uniprot
                            System.out.println(uniprotID + " was deleted.");
                        } else if (check != -1) {
                            continue;
                        }

                        // else if check failed or protein now exists and didn't before, proceed to add as normal

                    } catch (IOException | SAXException | ParserConfigurationException e) {
                        protein = null;
                    }

                    // Add to database
                    if (protein == null || !addProtein(protein)) {
                        System.err.println(uniprotID + " retrieval and update failed.");
                        if (PRINT_FAILED) {
                            writer.println(uniprotID);
                        }
                        continue;
                    }

                    System.out.println(uniprotID + " added.");
                }
            }

            assert writer != null;
            writer.close();
            sc.close();
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Updates ProteinCurrent table given a file of Uniprot IDs.
     *
     * @param filepath File with UniProtIDs.
     */
    public static void updateFromIDs(String filepath) {
        // Open file and iterate through UniProt IDs
        try {
            FileInputStream inputStream = new FileInputStream(filepath);
            Scanner sc = new Scanner(inputStream, "UTF-8");

            PrintWriter writer = null;
            if (PRINT_FAILED) {
                writer = new PrintWriter(PRINT_FAILED_PATH, "UTF-8");
            }

            while (sc.hasNextLine()) {
                String uniprotID = sc.nextLine();

                // Get protein
                ProteinCurrent protein;
                try {
                    protein = getProteinFromUniprot(uniprotID);

                    int check = checkAndUpdateHistories(uniprotID, protein);

                    if (check == 0) { // already exists and no changes
                        System.out.println(uniprotID + " is up to date.");
                    } else if (check == 1) { // updated protein and protein history
                        System.out.println(uniprotID + " was updated.");
                    } else if (check == 2) { // exists in db but was deleted from uniprot
                        System.out.println(uniprotID + " was deleted.");
                    } else if (check != -1) {
                        continue;
                    }

                    // else if check failed or protein now exists and didn't before, proceed to add as normal
                } catch (IOException | SAXException | ParserConfigurationException e) {
                    protein = null;
                }

                // Add to database
                if (protein == null || !addProtein(protein)) {
                    System.err.println(uniprotID + " retrieval failed.");
                    if (PRINT_FAILED) {
                        writer.println(uniprotID);
                    }
                    continue;
                }

                System.out.println(uniprotID + " added.");
            }

            if (writer != null) {
                writer.close();
            }

            sc.close();
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns the ProteinCurrent for a UniProt ID
     *
     * @param uniprotID UniProt ID to get
     * @return ProteinCurrent
     */
    public static ProteinCurrent getProteinFromUniprot(String uniprotID)
            throws IOException, ParserConfigurationException, SAXException {
        // Generate XML URL
        URL url = new URL(UNIPROT_BASE_URL + uniprotID + ".xml");

        // Open connection
        URLConnection connection = url.openConnection();
        HttpURLConnection httpConnection = (HttpURLConnection) connection;
        httpConnection.setRequestProperty("Content-Type", "application/json");

        // Validate response
        int responseCode = httpConnection.getResponseCode();
        if (responseCode != 200) {
            System.err.println("Bad url: " + url);
            return null;
        }

        // Get content
        Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[CHAR_BUFFER_SIZE];
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
            sb.append(buffer, 0, read);
        }
        reader.close();

        if (sb.length() <= 1) {
            return null;
        }

        // Parse XML
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(sb.toString())));
        doc.getDocumentElement().normalize();

        // Iterate through protein entries
        NodeList entries = doc.getElementsByTagName("entry");
        Element proteinElement = (Element) entries.item(0);
        ProteinCurrent protein = new ProteinCurrent();

        // Get accession (UniProt ID, use first one as primary)
        protein.setProtein_acc(proteinElement.getElementsByTagName("accession").item(0).getTextContent());

        // Get protein full (recommended) name
        protein.setProtein_name(proteinElement.getElementsByTagName("fullName").item(0).getTextContent());

        // Get sequence and molecular weight
        // Potential sequence nodes when there are isoforms, target should always be the last node
        NodeList sequences = proteinElement.getElementsByTagName("sequence");
        protein.setSequence(sequences.item(sequences.getLength() - 1).getTextContent()
                .replaceAll("[^A-Z]", "")); // Strip non-uppercase alpha characters
        protein.setMolecular_weight(Double.valueOf(
                ((Element) sequences.item(sequences.getLength() - 1)).getAttribute("mass")));

        // Handle feature data (default empty strings)
        protein.setTransmembrane_domain("");
        protein.setCytoplasmatic_domain("");
        protein.setNoncytoplasmatic_domain("");
        protein.setSignal_peptide("");
        protein.setFeature_table("");

        StringBuilder featureTable = new StringBuilder();
        NodeList features = proteinElement.getElementsByTagName("feature");
        for (int featureIndex = 0; featureIndex < features.getLength(); featureIndex++) {
            Element featureElement = (Element) features.item(featureIndex);
            Element locationElement = (Element) featureElement.getElementsByTagName("location").item(0);
            NodeList position = locationElement.getElementsByTagName("position");

            // Append new line between feature elements
            if (featureIndex > 0) {
                featureTable.append("\n");
            }

            // Append feature type
            featureTable.append(featureElement.getAttribute("type")).append("\t");

            // Feature element location has start/end OR position
            if (position.getLength() == 0) {
                // Handle start/end
                String start = ((Element) locationElement
                        .getElementsByTagName("begin").item(0))
                        .getAttribute("position");
                String end = ((Element) locationElement
                        .getElementsByTagName("end").item(0))
                        .getAttribute("position");

                // Append start/end positions
                featureTable.append(start).append("\t").append(end).append("\t");

                // Set domain values
                if (featureElement.getAttribute("type").equals("transmembrane region")) {
                    protein.setTransmembrane_domain(start + " - " + end);
                } else if (featureElement.getAttribute("type").equals("topological domain") &&
                        featureElement.getAttribute("description").equals("Cytoplasmic")) {
                    protein.setCytoplasmatic_domain(start + " - " + end);
                } else if (featureElement.getAttribute("type").equals("topological domain") &&
                        featureElement.getAttribute("description").equals("Extracellular")) {
                    protein.setNoncytoplasmatic_domain(start + " - " + end);
                } else if (featureElement.getAttribute("type").equals("transit peptide") &&
                        featureElement.getAttribute("description").equals("Mitochondrion")) {
                    protein.setNoncytoplasmatic_domain(start + " - " + end);
                } else if (featureElement.getAttribute("type").equals("signal peptide")) {
                    protein.setSignal_peptide(start + " - " + end);
                }
            } else {
                // Handle position
                // Append position position
                featureTable.append(((Element) position.item(0)).getAttribute("position")).append("\t");
            }

            // Append feature description
            featureTable.append(featureElement.getAttribute("description"));
        }

        // Get dbReferences
        Set<GoTerms> goTerms = new HashSet<>();
        DBRef dbRef = new DBRef();
        List<String> pdb = new ArrayList<>();
        List<String> reactome = new ArrayList<>();
        List<String> geneWiki = new ArrayList<>();
        NodeList dbRefs = proteinElement.getElementsByTagName("dbReference");
        for (int dbRefIndex = 0; dbRefIndex < dbRefs.getLength(); dbRefIndex++) {
            Element dbRefElement = (Element) dbRefs.item(dbRefIndex);
            // Ignore if not a top-level dbReference
            if (!dbRefElement.getParentNode().getNodeName().equals("entry")) {
                continue;
            }

            switch (dbRefElement.getAttribute("type")) {
                case "PDB":
                    pdb.add(dbRefElement.getAttribute("id"));
                    break;
                case "Reactome":
                    reactome.add(dbRefElement.getAttribute("id"));
                    break;
                case "GeneWiki":
                    geneWiki.add(dbRefElement.getAttribute("id"));
                    break;
                case "Proteomes":
                    // Get chromosome
                    protein.setChromosome(((Element) dbRefElement
                            .getElementsByTagName("property").item(0))
                            .getAttribute("value"));
                    break;
                case "GO":
                    // Get GOTerms
                    GoTerms goTerm = new GoTerms();
                    goTerm.setGO_accession(
                            Integer.valueOf(dbRefElement.getAttribute("id")
                                    .split(":")[1])); // Ignore the GO: prefix

                    goTerm.setTerms(((Element) dbRefElement
                            .getElementsByTagName("property").item(0))
                            .getAttribute("value"));
                    goTerm.setEvidence(((Element) dbRefElement
                            .getElementsByTagName("property").item(1))
                            .getAttribute("value"));
                    goTerm.setReference(((Element) dbRefElement
                            .getElementsByTagName("property").item(2))
                            .getAttribute("value"));
                    Set<ProteinCurrent> proteins = new HashSet<>();
                    proteins.add(protein);
                    goTerm.setProteins(proteins);
                    goTerms.add(goTerm);
                    break;
            }
        }
        protein.setGoTerms(goTerms);
        dbRef.setPdb(String.join("\n", pdb));
        dbRef.setReactome(String.join("\n", reactome));
        dbRef.setGeneWiki(String.join("\n", geneWiki));
        dbRef.setProtein_acc(protein.getProtein_acc());
        dbRef.setProteinCurrent(protein);
        protein.setDbRef(dbRef);

        // Get keywords
        NodeList keywords = proteinElement.getElementsByTagName("keyword");
        StringBuilder kw = new StringBuilder();
        for (int keywordIndex = 0; keywordIndex < keywords.getLength(); keywordIndex++) {
            // Append pipe separator between keywords
            if (keywordIndex > 0) {
                kw.append(" | ");
            }

            kw.append(keywords.item(keywordIndex).getTextContent());
        }
        protein.setKeywords(kw.toString());

        // Get feature table string
        protein.setFeature_table(featureTable.toString());

        // Get species
        NodeList speciesNames = ((Element) proteinElement.getElementsByTagName("organism").item(0))
                .getElementsByTagName("name");
        // Check scientific name first
        String speciesName = speciesNames.item(0).getTextContent(); // First species name should be scientific
        Species species = DAOObject.getInstance().getProteinDAO().searchSpecies(speciesName);
        // Check common name if necessary
        if (species == null && speciesNames.getLength() > 1) {
            speciesName = speciesNames.item(1).getTextContent(); // Second species name should be common
        }
        species = DAOObject.getInstance().getProteinDAO().searchSpecies(speciesName);
        // If neither scientific or common name are in the database, add the common name
        if (species == null) {
            Species sp = new Species(0, speciesName, null, null);
            DAOObject.getInstance().getProteinDAO().addSpecies(sp);
        }
        protein.setSpecies(species);

        // Get gene
        Set<String> ensemblIDs = new HashSet<>();
        NodeList dbReferences = proteinElement.getElementsByTagName("dbReference");
        for (int dbRefIndex = 0; dbRefIndex < dbReferences.getLength(); dbRefIndex++) {
            Element dbRefElement = (Element) dbReferences.item(dbRefIndex);
            String dbRefType = dbRefElement.getAttribute("type");
            if (dbRefType.startsWith("Ensembl") || dbRefType.startsWith("WormBase")) {
                Element property = (Element) dbRefElement.getElementsByTagName("property").item(1);
                if (property != null) {
                    ensemblIDs.add(property.getAttribute("value"));
                }
            }
        }
        Set<Gene> genes = new HashSet<>();
        Gene gene = new Gene();
        if (proteinElement.getElementsByTagName("gene").getLength() >= 1) {
            gene.setGene_name(((Element) proteinElement
                    .getElementsByTagName("gene").item(0))
                    .getElementsByTagName("name").item(0)
                    .getTextContent());
        } else {
            return null;
        }
        gene.setEnsembl_id(String.join(", ", ensemblIDs)); // Concatenate ensemblIDs
        genes.add(gene);
        protein.setGenes(genes);

        return protein;
    }

    /**
     * Attempts to add a protein to the database.
     *
     * @param protein ProteinCurrent to add
     * @return Returns True if add successful or protein already exists.
     */
    public static Boolean addProtein(ProteinCurrent protein) {
        ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();
        // Attempt to add the protein
        String result = proteinDAO.addProteinCurrent(protein);

        // Process result
        return !(result.isEmpty() || result.equals("Failed"));

    }

    /**
     * Returns the Gene for an Ensembl ID
     *
     * @param ensemblID Ensembl ID to lookup
     * @return Gene object with name, id, and chromosome
     * @throws IOException
     */
    public static Gene getGeneFromEnsembl(String ensemblID) throws IOException {
        // Generate URL
        URL url = new URL(ENSEMBL_BASE_URL + ensemblID);

        // Open connection
        URLConnection connection = url.openConnection();
        HttpURLConnection httpConnection = (HttpURLConnection) connection;
        httpConnection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

        // Validate response
        int responseCode = httpConnection.getResponseCode();
        if (responseCode != 200) {
            System.err.println("Bad url: " + url);
            return null;
        }

        // Get content
        Reader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[CHAR_BUFFER_SIZE];
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
            sb.append(buffer, 0, read);
        }
        reader.close();

        // Get gene data
        Gene gene = new Gene();
        gene.setEnsembl_id(ensemblID);
        String[] lines = sb.toString().split("\n");
        for (String line : lines) {
            if (line.startsWith("error: ")) {
                throw new RuntimeException(line);
            } else if (line.startsWith("display_name: ")) {
                // Get display name
                gene.setGene_name(line.split(" ")[1]);
            }
        }

        return gene;
    }

    /**
     * Called if protein to be updated or deleted from database, otherwise does nothing.
     * If any updates or deletions happen, then the protein history table will also be updated.
     *
     * @param protein_acc accession id of protein to be checked in the database
     * @param p           the updated protein current object. if null, then assumed it has been deleted from uniprot
     *                    and will also be deleted from the database; if p is defined and there is a valid entry
     *                    with the given protein_acc, then the entry will be updated
     * @return 0 if no change, 1 if exists and has been updated, 2 if deleted
     */
    public static int checkAndUpdateHistories(String protein_acc, ProteinCurrent p) {
        ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();
        ProteinCurrent existingProtein = proteinDAO.searchByID(protein_acc); // add param

        Version version = new Version();
        Version latestVersion = proteinDAO.searchLatestVersion();
        if (latestVersion != null) {
            version.setVersion(latestVersion.getVersion() - 1); // set it to previous versions
        } else {
            version.setVersion(1); // Create first version
        }
        version.setDescription("Update"); // TODO Change the description values
        version.setDate(new Date());

        // if exists on uniprot and db, then update the object and change protein history
        if (existingProtein != null && p != null) {
            if (!proteinDAO.compareProteinCurrent(p, existingProtein)) { // different than current entry in db
                // create and add protein history entry
                ProteinHistory proteinHistory = new ProteinHistory();
                proteinHistory.setProtein_acc(protein_acc);
                proteinHistory.setProtein_name(p.getProtein_name());
                proteinHistory.setChromosome(p.getChromosome());
                proteinHistory.setMolecular_weight(p.getMolecular_weight());
                proteinHistory.setSequence(p.getSequence());
                proteinHistory.setVersion(version);
                proteinHistory.setDelete_date(new Date());

                proteinDAO.addProteinHistory(proteinHistory);

                // update the protein current entry
                proteinDAO.updateProteinCurrent(p.getProtein_acc(), p);
                return 1;
            } else { // same as current entry in db
                return 0;
            }

            // if exists in db but no longer exists on uniprot
        } else if (existingProtein != null) {
            ProteinHistory proteinHistory = new ProteinHistory();
            proteinHistory.setProtein_acc(protein_acc);
            proteinHistory.setProtein_name(existingProtein.getProtein_name());
            proteinHistory.setChromosome(existingProtein.getChromosome());
            proteinHistory.setMolecular_weight(existingProtein.getMolecular_weight());
            proteinHistory.setSequence(existingProtein.getSequence());
            proteinHistory.setVersion(version);
            proteinHistory.setDelete_date(new Date());

            proteinDAO.addProteinHistory(proteinHistory);

            List<SpectrumProtein> spectrumProteins = proteinDAO.searchSpectrumProteins(existingProtein);
            if (spectrumProteins.size() > 0) {
                for (SpectrumProtein spectrumProtein : spectrumProteins) {
                    SpectrumProteinHistory spectrumProteinHistory = new SpectrumProteinHistory();
                    spectrumProteinHistory.setSpectrumProtein_id(spectrumProtein.getSpectrumProtein_id());
                    spectrumProteinHistory.setSpectrum_id(spectrumProtein.getSpectrum().getSpectrum_id());
                    spectrumProteinHistory.setVersion(version);
                    spectrumProteinHistory.setProtein_acc(spectrumProtein.getProtein().getProtein_acc());
                    spectrumProteinHistory.setFeature_peptide(spectrumProtein.isFeature_peptide());
                    spectrumProteinHistory.setSpecies_unique(spectrumProtein.isSpecies_unique());
                    spectrumProteinHistory.setLibraryModule(spectrumProtein.getLibraryModule().getMod_id());
                    spectrumProteinHistory.setLocation(spectrumProtein.getLocation());
                    spectrumProteinHistory.setPrevAA(spectrumProtein.getPrevAA());
                    spectrumProteinHistory.setNextAA(spectrumProtein.getNextAA());
                    spectrumProteinHistory.setDelete_date(new Date());

                    // Add to spectrum protein history before deleting
                    proteinDAO.addSpectrumProteinHistory(spectrumProteinHistory);
                    proteinDAO.deleteSpectrumProtein(spectrumProtein.getSpectrumProtein_id());
                }
            }

            // delete protein current entry and all affiliated entries (DBRef, Gene, GO Terms, SpectrumProtein)
            if (proteinDAO.deleteProteinCurrent(protein_acc)) {
                return 2;
            } else {
                System.out.println("Could not delete " + protein_acc);
                return -1;
            }
        }

        // if did not exist in db but now exists on uniprot, do nothing

        return -1; // if failed and did nothing
    }
}
