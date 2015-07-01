package org.copakb.updater.protein;

import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.model.DBRef;
import org.copakb.server.dao.model.Gene;
import org.copakb.server.dao.model.ProteinCurrent;
import org.copakb.server.dao.model.Species;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import uk.ac.ebi.kraken.interfaces.uniprot.UniProtEntry;
import uk.ac.ebi.kraken.uuw.services.remoting.EntryRetrievalService;
import uk.ac.ebi.kraken.uuw.services.remoting.UniProtJAPI;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by vincekyi on 5/26/15.
 */
public class ProteinUpdate {

    private static final int CHAR_BUFFER_SIZE = 8192;
    private static final String ENSEMBL_BASE_URL = "http://rest.ensembl.org/lookup/id/";
    private static final String UNIPROT_BASE_URL = "http://www.uniprot.org/uniprot/";

    public ProteinUpdate() {

    }

    public static void main(String[] args) {
//        updateFromIDs("data/uniprot_not_added.txt");
//        updateFromFasta("./src/main/resources/uniprot_elegans_6239_canonical.fasta");
        updateFromFasta("./src/main/resources/test.fasta");
    }

    // updates ProteinCurrent table given fasta file
    public static void updateFromFasta(String file) {
        Date dateBeg = new Date();
        System.out.println("BEGINNING: " + dateBeg.toString());

        String cleanFile = cleanFilePath(file);

        EntryRetrievalService entryRetrievalService = UniProtJAPI.factory.getEntryRetrievalService();
        UniProtEntry entry = null;
        String uniprotid = "";

        try {
            PrintWriter writer = new PrintWriter("./src/main/resources/uniprot_not_added.txt", "UTF-8");

            Scanner scanner = new Scanner(new FileInputStream(cleanFile));
            while (scanner.hasNextLine()) {
                String uniprotheader = scanner.nextLine();
                if (uniprotheader.startsWith(">")) {
                    uniprotid = uniprotheader.substring(4, uniprotheader.indexOf("|", 4));
                    try {
                        entry = (UniProtEntry) entryRetrievalService.getUniProtEntry(uniprotid);
                        System.out.println("\n*************************");
                        System.out.println("Able to retrieve " + uniprotid + " from UniProt");

                    } catch (Exception ex) {
                        System.out.println("Uniprot did not retrieve " + uniprotid + "\t" + ex.toString() + ex.getMessage());
                        writer.println(uniprotid);
                        continue;
                    }

                    if (entry == null) {
                        System.out.println("Uniprot did not retrieve " + uniprotid + ". Could not find entry.");
                        writer.println(uniprotid);
                        continue;
                    }

                    if (DAOObject.getInstance().getProteinDAO().searchByID(uniprotid) != null) {
                        System.out.println("Uniprot ID: " + uniprotid + " is already in the database.");
                        continue;
                    }

                    // gets the rest of the protein data
                    ProteinCurrent protein = getProteinFromUniprot(uniprotid);

                    // Add protein to database
                    if (protein == null || !addProtein(protein)) {
                        // make list of all uniprot id's that were not added
                        writer.println(uniprotid);
                        continue;
                    }
                }
            }
            scanner.close();
            writer.close();
        } catch (Exception ex) {
            if (entry == null) {
                System.out.printf("%s:::%s\n%s\n", ex.toString(), uniprotid, ex.getMessage());
            } else {
                System.out.println(ex.toString() + ex.getMessage() + entry.getUniProtId());
                ex.printStackTrace();
            }

            return;
        }
        //use HPAProtein to get the correct ensemblegeneid for humans

        System.out.println("\n\n");
        System.out.println("BEGINNING: " + dateBeg.toString());
        Date dateEnd = new Date();
        System.out.println("ENDING: " + dateEnd.toString());
    }

    /**
     * Attempts to add a protein to the database.
     *
     * @param protein ProteinCurrent to add
     * @return Returns True if add successful or protein already exists.
     */
    public static Boolean addProtein(ProteinCurrent protein) {
        // Attempt to add the protein
        String result = DAOObject.getInstance().getProteinDAO().addProteinCurrent(protein);

        // Process result
        if (result.isEmpty() || result.equals("Failed")) {
            System.out.println(protein.getProtein_acc() + " add failed.");
            return false;
        } else if (result.equals("Existed")) {
            System.out.println(protein.getProtein_acc() + " already exists in database.");
        }

        DAOObject.getInstance().getProteinDAO().addDbRef(protein.getDbRef());
        return true;
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
            } else if (line.startsWith("seq_region_name: ")) {
                // Get chromosome
                gene.setChromosome(line.split(" ")[1]);
            }
        }

        return gene;
    }

    public static String cleanFilePath(String file) {
        String result = file;
        if (!file.substring(file.length() - 6).equals(".fasta"))
            return null;
        Pattern textPattern = Pattern.compile("[^0-9A-Za-z/\\._\\- ]");
        Matcher textMatcher = textPattern.matcher(file);
        result = textMatcher.replaceAll("");
        return result;
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
            ArrayList<String> failed = new ArrayList<String>();
            while (sc.hasNextLine()) {
                String uniprotID = sc.nextLine();

                if (DAOObject.getInstance().getProteinDAO().searchByID(uniprotID) != null) {
                    System.out.println("Uniprot ID: " + uniprotID + " is already in the database.");
                    continue;
                }

                try {
                    if (addProtein(getProteinFromUniprot(uniprotID))) {
                        System.out.println(uniprotID + " SUCCESS"); // TODO Debug
                    }
                } catch (Exception e) {
                    failed.add(uniprotID);
                    System.out.println(uniprotID + " FAILED"); // TODO Debug
                }
            }

            System.out.println("Failed: " + failed); // TODO Debug
            sc.close();
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns the ProteinCurrent for a UniProt ID
     *
     * @param uniprotID
     * @return
     * @throws Exception
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

        // SKIP Get ref_kb_id

        // Get dbReferences
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
            }
        }
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

        // Get species ID
        // Check scientific name first
        String species = ((Element) proteinElement.getElementsByTagName("organism").item(0))
                .getElementsByTagName("name").item(0) // First species name should be scientific
                .getTextContent();
        // Check common name
        Species speciesID = DAOObject.getInstance().getProteinDAO().searchSpecies(species);
        if (DAOObject.getInstance().getProteinDAO().searchSpecies(species) == null) {
            species = ((Element) proteinElement.getElementsByTagName("organism").item(0))
                    .getElementsByTagName("name").item(1) // Second species name should be common
                    .getTextContent();
        }
        speciesID = DAOObject.getInstance().getProteinDAO().searchSpecies(species);
        protein.setSpecies(speciesID);

        // SKIP wiki_link

        // Get genes (only take the first gene)
        Set<Gene> genes = new HashSet<Gene>();
        NodeList dbReferences = proteinElement.getElementsByTagName("dbReference");
        for (int dbRefIndex = 0; dbRefIndex < 1; dbRefIndex++) {
            Element dbRefElement = (Element) dbReferences.item(dbRefIndex);
            String dbRefType = dbRefElement.getAttribute("type");
            // TODO Create array to check against valid gene dbReference types
            if (dbRefType.startsWith("Ensembl") || dbRefType.startsWith("WormBase")) {
                // TODO Check for property type = "gene ID", or verify consistency as 2nd element
                Element property = (Element) dbRefElement.getElementsByTagName("property").item(1);
                if (property != null) {
                    String id = property.getAttribute("value");
                    genes.add(getGeneFromEnsembl(id));
                }
            }
        }
        protein.setGenes(genes);

        // SKIP PTMs
        // SKIP GoTerms
        // SKIP Spectra

        return protein;
    }
}
