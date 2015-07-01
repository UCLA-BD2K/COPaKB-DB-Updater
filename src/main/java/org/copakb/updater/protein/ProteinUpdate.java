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
 * Created by vincekyi on 5/26/15.
 */
public class ProteinUpdate {

    private static final int CHAR_BUFFER_SIZE = 8192;
    private static final String ENSEMBL_BASE_URL = "http://rest.ensembl.org/lookup/id/";
    private static final String UNIPROT_BASE_URL = "http://www.uniprot.org/uniprot/";

    private static final Boolean PRINT_FAILED = true;
    private static final String PRINT_FAILED_PATH = "target/uniprot_failed.txt";

    public ProteinUpdate() {

    }

    public static void main(String[] args) {
//        updateFromIDs("data/uniprot_not_added.txt");
        updateFromFasta("data/uniprot_elegans_6239_canonical.fasta");
//        updateFromFasta("./src/main/resources/test.fasta");
    }

    public static void updateFromFasta(String filepath) {
        FileInputStream inputStream = null;

        try {
            inputStream = new FileInputStream(filepath);
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

                    // Skip if already in database
                    if (DAOObject.getInstance().getProteinDAO().searchByID(uniprotID) != null) {
                        System.out.println(uniprotID + " already exists.");
                        continue;
                    }

                    // Get protein
                    ProteinCurrent protein = null;
                    try {
                        protein = getProteinFromUniprot(uniprotID);
                    } catch (IOException | SAXException | ParserConfigurationException e) {
                        protein = null;
                    }

                    // Add to database
                    if (protein == null || !addProtein(protein)) {
                        System.out.println(uniprotID + " retrieval and update failed.");
                        if (PRINT_FAILED && writer != null) {
                            writer.println(uniprotID);
                        }
                        continue;
                    }

                    System.out.println(uniprotID + " added.");
                }
            }

            writer.close();
            sc.close();
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
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
        if (result.isEmpty() || result.equals("Failed")) {
            System.out.println(protein.getProtein_acc() + " add failed.");
            return false;
        } else if (result.equals("Existed")) {
            System.out.println(protein.getProtein_acc() + " already exists in database.");
        }

        proteinDAO.addDbRef(protein.getDbRef());

        return true;
    }

    /**
     * Updates ProteinCurrent table given a file of Uniprot IDs
     *
     * @param filename File with UniProtIDs
     * @throws Exception
     */
    public static void updateFromIDs(String filename) {
        // Open file and iterate through UniProt IDs
        FileInputStream inputStream = null;

        try {
            inputStream = new FileInputStream(filename);
            Scanner sc = new Scanner(inputStream, "UTF-8");

            PrintWriter writer = null;
            if (PRINT_FAILED) {
                writer = new PrintWriter(PRINT_FAILED_PATH, "UTF-8");
            }

            while (sc.hasNextLine()) {
                String uniprotID = sc.nextLine();

                // Skip if already in database
                if (DAOObject.getInstance().getProteinDAO().searchByID(uniprotID) != null) {
                    System.out.println(uniprotID + " already exists.");
                    continue;
                }

                // Get protein
                ProteinCurrent protein = null;
                try {
                    protein = getProteinFromUniprot(uniprotID);
                } catch (IOException | SAXException | ParserConfigurationException e) {
                    protein = null;
                }

                // Add to database
                if (protein == null || !addProtein(protein)) {
                    System.out.println(uniprotID + " retrieval failed.");
                    if (PRINT_FAILED && writer != null) {
                        writer.println(uniprotID);
                    }
                    continue;
                }

                System.out.println(uniprotID + " added.");
            }

            writer.close();
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
    private static ProteinCurrent getProteinFromUniprot(String uniprotID)
            throws IOException, ParserConfigurationException, SAXException {
        // Generate XML URL
        URL url = new URL(UNIPROT_BASE_URL + uniprotID + ".xml");

        // Open connection
        URLConnection connection = url.openConnection();
        HttpURLConnection httpConnection = (HttpURLConnection) connection;
        httpConnection.setRequestProperty("Content-Type", "application/json");
        InputStream response = connection.getInputStream();

        // Validate response
        int responseCode = httpConnection.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("Bad response: " + responseCode);
        }

        // Get content
        Reader reader = new BufferedReader(new InputStreamReader(response, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[CHAR_BUFFER_SIZE];
        int read;
        while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
            sb.append(buffer, 0, read);
        }
        reader.close();

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

        // Get sequence
        protein.setSequence(proteinElement.getElementsByTagName("sequence").item(0).getTextContent());

        // Get protein full (recommended) name
        protein.setProtein_name(proteinElement.getElementsByTagName("fullName").item(0).getTextContent());

        // Get molecular weight
        // Potential sequence nodes when there are isoforms, target should always be the last one
        NodeList sequences = proteinElement.getElementsByTagName("sequence");
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
            featureTable.append(featureElement.getAttribute("type") + "\t");

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
                featureTable.append(start + "\t" + end + "\t");

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
                featureTable.append(((Element) position.item(0)).getAttribute("position") + "\t");
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

            String type = dbRefElement.getAttribute("type");
            if (type.equals("PDB")) {
                pdb.add(dbRefElement.getAttribute("id"));
            } else if (type.equals("Reactome")) {
                reactome.add(dbRefElement.getAttribute("id"));
            } else if (type.equals("GeneWiki")) {
                geneWiki.add(dbRefElement.getAttribute("id"));
            } else if (type.equals("Proteomes")) {
                // Get chromosome
                protein.setChromosome(((Element) dbRefElement
                        .getElementsByTagName("property").item(0))
                        .getAttribute("value"));
            } else if (type.equals("GO")) {
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
        // Check scientific name first
        String speciesName = ((Element) proteinElement.getElementsByTagName("organism").item(0))
                .getElementsByTagName("name").item(0) // First species name should be scientific
                .getTextContent();
        // Check common name
        Species species = DAOObject.getInstance().getProteinDAO().searchSpecies(speciesName);
        if (species == null) {
            speciesName = ((Element) proteinElement.getElementsByTagName("organism").item(0))
                    .getElementsByTagName("name").item(1) // Second species name should be common
                    .getTextContent();
        }
        species = DAOObject.getInstance().getProteinDAO().searchSpecies(speciesName);
        protein.setSpecies(species);

        // Get gene
        List<String> ensemblIDs = new ArrayList<>();
        NodeList dbReferences = proteinElement.getElementsByTagName("dbReference");
        for (int dbRefIndex = 0; dbRefIndex < dbReferences.getLength(); dbRefIndex++) {
            Element dbRefElement = (Element) dbReferences.item(dbRefIndex);
            String dbRefType = dbRefElement.getAttribute("type");
            // TODO Create array to check against valid gene dbReference types
            if (dbRefType.startsWith("Ensembl") || dbRefType.startsWith("WormBase")) {
                // TODO Check for property type = "gene ID", or verify consistency as 2nd element
                Element property = (Element) dbRefElement.getElementsByTagName("property").item(1);
                if (property != null) {
                    ensemblIDs.add(property.getAttribute("value"));
                }
            }
        }
        Set<Gene> genes = new HashSet<>();
        Gene gene = new Gene();
        gene.setGene_name(((Element) proteinElement
                .getElementsByTagName("gene").item(0))
                .getElementsByTagName("name").item(0)
                .getTextContent());
        gene.setEnsembl_id(String.join(", ", ensemblIDs)); // Concatenate ensemblIDs
        genes.add(gene);
        protein.setGenes(genes);

        // SKIP PTMs
        // SKIP GoTerms
        // SKIP Spectra

        return protein;
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
        InputStream response = connection.getInputStream();

        // Validate response
        int responseCode = httpConnection.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("Bad response: " + responseCode);
        }

        // Get content
        Reader reader = new BufferedReader(new InputStreamReader(response, "UTF-8"));
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

}
