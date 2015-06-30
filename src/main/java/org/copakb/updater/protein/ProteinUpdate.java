package org.copakb.updater.protein;

import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.ProteinDAO;
import org.copakb.server.dao.model.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import uk.ac.ebi.kraken.interfaces.uniprot.Keyword;
import uk.ac.ebi.kraken.interfaces.uniprot.UniProtEntry;
import uk.ac.ebi.kraken.interfaces.uniprot.dbx.go.Go;
import uk.ac.ebi.kraken.interfaces.uniprot.dbx.go.OntologyType;
import uk.ac.ebi.kraken.interfaces.uniprot.features.Feature;
import uk.ac.ebi.kraken.uuw.services.remoting.*;

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

    public ProteinUpdate(){

    }

    public static void main(String[] args) {
//        updateFromIDs("data/uniprot_not_added.txt");
//        updateFromFasta("./src/main/resources/uniprot_elegans_6239_canonical.fasta");
        updateFromFasta("./src/main/resources/test.fasta");
    }

    // updates ProteinCurrent table given fasta file
    public static void updateFromFasta(String file)
    {
        Date dateBeg = new Date();
        System.out.println("BEGINNING: " + dateBeg.toString());

        String cleanFile = cleanFilePath(file);

        EntryRetrievalService entryRetrievalService = UniProtJAPI.factory.getEntryRetrievalService();
        UniProtEntry entry = null;
        String uniprotid = "";

        try
        {
            PrintWriter writer = new PrintWriter("./src/main/resources/uniprot_not_added.txt", "UTF-8");

            Scanner scanner = new Scanner(new FileInputStream(cleanFile));
            while (scanner.hasNextLine() ){
                String uniprotheader = scanner.nextLine();
                if (uniprotheader.startsWith(">") )
                {
                    uniprotid = uniprotheader.substring(4,uniprotheader.indexOf("|",4) ) ;
                    try{
                        entry = (UniProtEntry)entryRetrievalService.getUniProtEntry(uniprotid);
                        System.out.println("\n*************************");
                        System.out.println("Able to retrieve "+uniprotid+" from UniProt");

                    }catch(Exception ex){
                        System.out.println("Uniprot did not retrieve "+uniprotid+"\t"+ex.toString()+ex.getMessage());
                        writer.println(uniprotid);
                        continue;
                    }

                    if(entry == null) {
                        System.out.println("Uniprot did not retrieve "+uniprotid + ". Could not find entry.");
                        writer.println(uniprotid);
                        continue;
                    }

                    if(DAOObject.getInstance().getProteinDAO().searchByID(uniprotid) != null) {
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
        }catch (Exception ex)
        {
            if (entry == null) {
                System.out.printf("%s:::%s\n%s\n", ex.toString(), uniprotid, ex.getMessage());
            }
            else {
                System.out.println(ex.toString()+ex.getMessage()+entry.getUniProtId());
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
     * @param protein   ProteinCurrent to add
     * @return          Returns True if add successful or protein already exists.
     */
    public static Boolean addProtein(ProteinCurrent protein) {
        // Attempt to add the protein
        String result = DAOObject.getInstance().getProteinDAO().addProteinCurrent(protein);

        // Process result
        if (result.isEmpty() || result.equals("Failed")) {
            System.out.println(protein.getProtein_acc() + " add failed.");
            return false;
        } else if (result.equals("Existed")) {
            System.out.println( protein.getProtein_acc() + " already exists in database.");
        }

        DAOObject.getInstance().getProteinDAO().addDbRef(protein.getDbRef());
        return true;
    }

    static String ValideSQL(String sql)
    {
        sql = sql.replace("\'","\'\'");
        if(sql.length() > 4000)
            sql = sql.substring(0, 4000);
        return sql;
    }

    static String getGoAnnotation(UniProtEntry e){
        String result = "";

        for(Go g : e.getGoTerms())  {
            String oType = "";
            if(g.getOntologyType() == OntologyType.C)
                oType = "C";
            else if(g.getOntologyType() == OntologyType.F)
                oType = "F";
            else if(g.getOntologyType() == OntologyType.P)
                oType = "P";
            else
                return "";
            result +=oType+";"+g.getGoId().getValue()+";"+ g.getGoTerm().getValue()+"|";
        }
        return ValideSQL(result);
    }

    public static ProteinCurrent retrieveDataFromUniprot(UniProtEntry e, ProteinDAO proteinDAO){

        // initialize
        ProteinCurrent result = new ProteinCurrent();

        //String uniprotid = e.getUniProtId().toString();
        //result.setProtein_acc(uniprotid); // not correct uniprot id
        String uniprotid = e.getPrimaryUniProtAccession().toString();
        String seq = e.getSequence().getValue();
        result.setSequence(seq);
        int molweight = e.getSequence().getMolecularWeight();
        result.setMolecular_weight(molweight);

        if(uniprotid.length() < 2 || seq.length() < 2 || molweight <= 0.0) {
            System.out.println("Uniprot ID, Sequence, or Molecular weight could not be found.");
            return null;
        }

        String species = "";
        species = String.valueOf(e.getOrganism().getCommonName());
        if(species.length() < 2) {
            species = String.valueOf(e.getOrganism().getScientificName());
        }
        Species spec = new Species(0, species, null, null);
        if(proteinDAO.searchSpecies(species) == null)
            proteinDAO.addSpecies(spec);
        result.setSpecies(proteinDAO.searchSpecies(species));

        try{
            result.setProtein_name(e.getProteinDescription().getSection().getNames().get(0).getFields().get(0).getValue());
        }catch(Exception ex){
            //the protein has been deleted
            System.out.println("Unable to get description from UniProt: "+uniprotid);
            System.out.println("Deleted: "+uniprotid);
            return null;
        }

        // fill in transmembrane/cytoplasmic/noncytoplasmic domain, signal peptides, and features
        boolean foundCytoplasmic = true;
        String transmem = "", cytoplasmic = "", noncytoplasmic = "", signal = "", features = "";
        for(Feature f: e.getFeatures()){
            if(f.getType().getName().toUpperCase().contains("TRANSMEM")){
                transmem+= Integer.toString(f.getFeatureLocation().getStart()) +" - "+ Integer.toString(f.getFeatureLocation().getEnd())+ ", ";
            }
            else if(f.getType().getName().toUpperCase().contains("TOPO_DOM")){
                if(foundCytoplasmic) {
                    cytoplasmic = Integer.toString(f.getFeatureLocation().getStart()) +" - "+ Integer.toString(f.getFeatureLocation().getEnd());
                    foundCytoplasmic = false;
                }
                else
                    noncytoplasmic = Integer.toString(f.getFeatureLocation().getStart()) +" - "+ Integer.toString(f.getFeatureLocation().getEnd());
            }
            else if(f.getType().getName().toUpperCase().contains("SIGNAL") || f.getType().getName().toUpperCase().contains("TRANSIT"))
                signal = Integer.toString(f.getFeatureLocation().getStart()) +" - "+ Integer.toString(f.getFeatureLocation().getEnd());

            features += f.getType().getName()+"\t"+f.getFeatureLocation().getStart() +"\t"+f.getFeatureLocation().getEnd()+"\t"+f.getType().getValue() +"\n";
        }


        result.setTransmembrane_domain(ValideSQL(transmem));
        result.setCytoplasmatic_domain(ValideSQL(cytoplasmic));
        result.setNoncytoplasmatic_domain(ValideSQL(noncytoplasmic));
        result.setSignal_peptide(ValideSQL(signal));
        result.setFeature_table(ValideSQL(features));

        // fill in keywords
        String keywords = "";
        for(Keyword k: e.getKeywords()){
            keywords += k.getValue()+"|";
        }
        result.setKeywords(ValideSQL(keywords));

        // fill in cross reference, domain, and ensemble id
        String goTerms = "", crossRef = "", ensembl = "";
        Set<GoTerms> protGoTerms = new HashSet<GoTerms>();
        Set<String> ensemblIds = new HashSet<String>();

        for(uk.ac.ebi.kraken.interfaces.uniprot.DatabaseCrossReference d: e.getDatabaseCrossReferences())  {
            String temp_dName = d.getDatabase().getName();
            // d.getDatabase().toDisplayName();
            // create go term objects as they are parsed
            if(temp_dName.toUpperCase().substring(0, 2).equals("GO")){
                String fullTerm = d.toString();
                int goAcc = Integer.parseInt(fullTerm.substring(7, 14));
                String goTermInfo = fullTerm.substring(15);

                // create completed GoTerms object
                GoTerms tempGO = new GoTerms(goAcc, goTermInfo, null);
                tempGO.getProteins();

                protGoTerms.add(tempGO);
                goTerms += d.toString()+"|"; // for printing purposes only
                continue;
            }

            // get all related ensembl ids
            if(temp_dName.toUpperCase().contains("ENSEMBL")){
                if(d.hasThird()) {
                    //System.out.println(d.getThird().getValue());
                    String tempEnsembl = d.getThird().getValue();
                    ensemblIds.add(tempEnsembl); // add to HashSet to remove duplicates
                    ensembl += d.getThird().getValue() + "\n";
                }
            }
            crossRef += d+"\n";
        }

        /*if(protGoTerms.size() < 1) {
            System.out.println("Cannot find GO Terms! Aborting.");
            return null;
        }*/
        if(protGoTerms.size() >=  1) {
             result.setGoTerms(protGoTerms);
        }
        //result.setGoTerms(protGoTerms);

        // Get genes (with name, id, and chromosome)
        Set<Gene> genes = new HashSet<Gene>();
        // create gene - ensembl mapping information at the end (after duplicates are removed)
        if(ensemblIds.size() < 1) {
            System.out.println("Cannot find Ensembl ids! Aborting.");
            return null;
        }

        for(String id : ensemblIds)
        {
            try {
                // get gene information from ensembl
                Gene table_gene = getGeneFromEnsembl(id);
                table_gene.getDiseases();
                table_gene.getProteins();
                table_gene.getHpaProteins();
                genes.add(table_gene);
                //System.out.println("gene = " + relatedGene + "\trelated ensembl = " + id);
            }
            catch (Exception exception)
            {
                exception.printStackTrace();
            }
        }

        // get gene name
        /*Set<Gene> genes = new HashSet<Gene>();
        for(Gene gene: e.getGenes()) {
            Gene table_gene = new Gene();
            table_gene.setGene_name(gene.getGeneName().getValue());
            genes.add(table_gene);
            System.out.println("gene = " + gene.getGeneName().getValue());
        }*/
        if(genes.size() < 1) {
            System.out.println("Cannot find Genes! Aborting.");
            return null;
        }
        result.setGenes(genes);

    // If the gene list given by uniprot always matches the corresponding genes referenced by the list of ensembl id
    // it would be easier to use the ensembl API (as above) to fill in gene/ensembl for Gene object information
    // There are separate lists so we have to manually check to see which ensembl ids match
    // to which gene info because there can be multiple ensembl -> one gene AND multiple ensembl -> multiple genes
    // The following section is commented out in order to ignore the separate Gene list and just
    // to go by the ensembl list, WILL BE VERIFIED

        //System.out.println("uniprotid = " + uniprotid);
        /*System.out.println("sequence = " + result.getSequence());
        System.out.println("weight = " + result.getMolecular_weight());
        System.out.println("name = " + result.getProtein_name());
        System.out.println("features = " + features);
        System.out.println("signal = " + signal);
        System.out.println("noncytoplasmic = " + noncytoplasmic);
        System.out.println("cytoplasmic = " + cytoplasmic);
        System.out.println("transmem = " + transmem);
        System.out.println("keywords = " + keywords);*/
        //System.out.println("ensembl = " + ensembl);
        //System.out.println("crossRef = " + crossRef);
        //System.out.println("goTerms = " + goTerms);
        //System.out.println("species = " + species);

        return result;
    }

    private static final String ENSEMBL_BASE_URL = "http://rest.ensembl.org/lookup/id/";

    /**
     * Returns the Gene for an Ensembl ID
     *
     * @param ensemblID     Ensembl ID to lookup
     * @return              Gene object with name, id, and chromosome
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
        if(!file.substring(file.length()-6).equals(".fasta"))
            return null;
        Pattern textPattern = Pattern.compile("[^0-9A-Za-z/\\._\\- ]");
        Matcher textMatcher = textPattern.matcher(file);
        result = textMatcher.replaceAll("");
        return result;
    }

    private static final String UNIPROT_BASE_URL = "http://www.uniprot.org/uniprot/";

    /**
     * Updates ProteinCurrent table given a file of Uniprot IDs
     *
     * @param filename      File with UniProtIDs
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

                if(DAOObject.getInstance().getProteinDAO().searchByID(uniprotID) != null) {
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

        // Get protein name
        protein.setProtein_name(proteinElement.getElementsByTagName("name").item(0).getTextContent());

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

    /*
    public static int calcDistinctPeptides(Connection conn, String protein_id){

        try{
            Statement s = conn.createStatement();
            ResultSet rs = s.executeQuery("SELECT sum(species_unique) from seq_protein_tbl where protein_id = \'"+protein_id+"\'");
            if(rs.next()){
                int result = rs.getInt(1);
                s.close();
                rs.close();
                return result;
            }

        }   catch(SQLException ex){
            System.out.println(ex.getMessage());
        }

        return 0;
    }


    public static void updateNumUniquePeptides(Connection conn){
        try{
            Statement s = conn.createStatement();
            ResultSet rs = s.executeQuery("SELECT protein_cop_id from protein_tbl");
            while(rs.next()){
                Statement s1 = conn.createStatement();
                s1.executeUpdate("UPDATE protein_tbl SET number_of_distinct_peptides = "+calcDistinctPeptides(conn, rs.getString("protein_cop_id"))+" where protein_cop_id = \'"+rs.getString("protein_cop_id")+"\'");

            }
            s.close();
            rs.close();

        }   catch(SQLException ex){
            System.out.println(ex.getMessage());
        }

    }


    public static void useHPA(Connection conn){

        try{
            Statement s1 = conn.createStatement();
            ResultSet rs1 = s1.executeQuery("SELECT protein_cop_id, ref_kb_id FROM protein_tbl");

            while(rs1.next()){
                String protein_id = rs1.getString("protein_cop_id");
                String kb = rs1.getString("ref_kb_id");
                int index = kb.indexOf("Ensembl");
                if(index>0){
                    int end = kb.indexOf("\n", index);
                    if(end < 0)
                        end = kb.length();
                    String ids = kb.substring(index, end);

                    Statement s2 = conn.createStatement();
                    ResultSet rs2 = s2.executeQuery("SELECT ensg_id FROM hpa_crossref_tbl WHERE \'"+ids+"\' like CONCAT('%', ensg_id, '%') LIMIT 1");
                    if(rs2.next()){
                        Statement s3 = conn.createStatement();
                        s3.executeUpdate("UPDATE protein_tbl SET ensemblegeneid=\'"+rs2.getString("ensg_id")+"\' WHERE protein_cop_id=\'"+protein_id+"\'");
                        s3.close();
                        System.out.println(protein_id+"\t"+rs2.getString("ensg_id"));
                    }
                    rs2.close();
                    s2.close();
                }
            }
            rs1.close();
            s1.close();

        }   catch(Exception ex){
            System.err.println(ex.getMessage());
        }

    }
    */
}
