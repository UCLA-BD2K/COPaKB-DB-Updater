package org.copakb.updater.protein;

import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.ProteinDAO;
import org.copakb.server.dao.model.*;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import uk.ac.ebi.kraken.interfaces.uniprot.Gene;
import uk.ac.ebi.kraken.interfaces.uniprot.Keyword;
import uk.ac.ebi.kraken.interfaces.uniprot.UniProtEntry;
import uk.ac.ebi.kraken.interfaces.uniprot.dbx.go.Go;
import uk.ac.ebi.kraken.interfaces.uniprot.dbx.go.OntologyType;
import uk.ac.ebi.kraken.interfaces.uniprot.features.Feature;
import uk.ac.ebi.kraken.uuw.services.remoting.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by vincekyi on 5/26/15.
 */
public class ProteinUpdate {

    public ProteinUpdate(){

    }

    public static void main(String[] args) {
        update("./src/main/resources/uniprot_elegans_6239_canonical.fasta");
        //update("./src/main/resources/test.fasta");
    }


    // updates ProteinCurrent table given fasta file
    public static void update(String file)
    {
        Date dateBeg = new Date();
        System.out.println("BEGINNING: " + dateBeg.toString());

        String cleanFile = cleanFilePath(file);

        EntryRetrievalService entryRetrievalService = UniProtJAPI.factory.getEntryRetrievalService();
        UniProtEntry entry = null;
        String uniprotid = "";

        DAOObject obj = new DAOObject();
        ProteinDAO proteinDAO = obj.getProteinDAO();

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

                    if(proteinDAO.searchByID(uniprotid) != null) {
                        System.out.println("Uniprot ID: " + uniprotid + " is already in the database.");
                        continue;
                    }

                    // gets the rest of the protein data
                    ProteinCurrent protein = retrieveDataFromUniprot(entry, proteinDAO);

                    String addResult = "";

                    if(protein != null) {
                        protein.setProtein_acc(uniprotid);
                        addResult = proteinDAO.addProteinCurrent(protein);
                    }
                    else { // make list of all uniprot id's that were not added
                        writer.println(uniprotid);
                        continue;
                    }

                    if(addResult.equals("Existed")) {
                        System.out.println("Uniprot ID: " + uniprotid + " is already in the database.");
                    }
                    if(addResult.equals("") || addResult.equals("Failed")) {
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
        //use HPA to get the correct ensemblegeneid for humans

        System.out.println("\n\n");
        System.out.println("BEGINNING: " + dateBeg.toString());
        Date dateEnd = new Date();
        System.out.println("ENDING: " + dateEnd.toString());
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
        Set<String> chromoensemblIds = new HashSet<String>();

        for(uk.ac.ebi.kraken.interfaces.uniprot.DatabaseCrossReference d: e.getDatabaseCrossReferences())  {
            String temp_dName = d.getDatabase().getName();
            // d.getDatabase().toDisplayName();
            // create go term objects as they are parsed
            if(temp_dName.toUpperCase().substring(0, 2).equals("GO")){
                String fullTerm = d.toString();
                int goAcc = Integer.parseInt(fullTerm.substring(7, 14));
                String goTermInfo = fullTerm.substring(15);

                // create completed GoTerms object
                org.copakb.server.dao.model.GoTerms tempGO = new org.copakb.server.dao.model.GoTerms(goAcc, goTermInfo, null);
                tempGO.getProteins();

                protGoTerms.add(tempGO);
                goTerms += d.toString()+"|"; // for printing purposes only
                continue;
            }

            // get all related ensembl ids
            if(temp_dName.toUpperCase().contains("ENSEMBL")){
                if(d.hasThird()) {
                    //System.out.println(d.getThird().getValue());
                    String chromosomeEnsembl = d.getPrimaryId().getValue();
                    chromoensemblIds.add(chromosomeEnsembl); // add to HashSet to remove duplicates
                    String tempEnsembl = d.getThird().getValue();
                    ensemblIds.add(tempEnsembl); // add to HashSet to remove duplicates
                    ensembl += d.getThird().getValue() + "\n";
                }
            }
            crossRef += d+"\n";
        }

        if(protGoTerms.size() < 1) {
            System.out.println("Cannot find GO Terms! Aborting.");
            return null;
        }
        result.setGoTerms(protGoTerms);

        // get gene name
        Set<org.copakb.server.dao.model.Gene> genes = new HashSet<org.copakb.server.dao.model.Gene>();
        // create gene - ensembl mapping information at the end (after duplicates are removed)
        if(ensemblIds.size() < 1) {
            System.out.println("Cannot find Ensembl ids! Aborting.");
            return null;
        }

        // get chromosome location information from primary ENST key
        // only returns one from the first location from each listing
        String loc = "";
        for(String id : chromoensemblIds) {
            String tempLoc = Chromosome.getChromosome(id);
            if(tempLoc.equals("ie6")) {
                continue;
            }
            if(!loc.equals(tempLoc) && !loc.equals("")) {
                loc = loc + ", " +tempLoc;
            }
            else if (!loc.equals(tempLoc)) {
                loc = tempLoc;
            }
        }

        for(String id : ensemblIds)
        {
            try {
                // get gene information from ensembl
                String relatedGene = getGeneFromEnsembl(id);
                org.copakb.server.dao.model.Gene table_gene = new org.copakb.server.dao.model.Gene();
                table_gene.setGene_name(relatedGene);
                table_gene.setEnsembl_id(id);
                table_gene.setChromosome(loc);
                table_gene.getDiseases();
                table_gene.getProteins();
                table_gene.getHpas();
                genes.add(table_gene);
                //System.out.println("gene = " + relatedGene + "\trelated ensembl = " + id);
            }
            catch (Exception exception)
            {
                exception.printStackTrace();
            }
        }

        // get gene name
        /*Set<org.copakb.server.dao.model.Gene> genes = new HashSet<org.copakb.server.dao.model.Gene>();
        for(Gene gene: e.getGenes()) {
            org.copakb.server.dao.model.Gene table_gene = new org.copakb.server.dao.model.Gene();
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

    /**
     * Code adapted from rest.ensembl.org.
     * @param ensemblID
     * @return
     * @throws Exception
     */
    public static String getGeneFromEnsembl(String ensemblID) throws Exception {
        String server = "http://rest.ensembl.org";
        String ext = "/lookup/id/" + ensemblID + "?";
        URL url = new URL(server + ext);

        URLConnection connection = url.openConnection();
        HttpURLConnection httpConnection = (HttpURLConnection)connection;

        httpConnection.setRequestProperty("Content-Type", "application/json");

        InputStream response = connection.getInputStream();
        int responseCode = httpConnection.getResponseCode();

        if(responseCode != 200) {
            throw new RuntimeException("Response code was not 200. Detected response was "+responseCode);
        }

        String output;
        Reader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(response, "UTF-8"));
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer, 0, buffer.length)) > 0) {
                builder.append(buffer, 0, read);
            }
            output = builder.toString();
        }
        finally {
            if (reader != null) try {
                reader.close();
            } catch (IOException logOrIgnore) {
                logOrIgnore.printStackTrace();
            }
        }

        // parse the json information to find just the "display name" which corresponds to gene symbol
        Pattern pattern = Pattern.compile("\"display_name\":\"(.+?)\"");
        Matcher matcher = pattern.matcher(output);
        if(matcher.find())
        {
            String geneSymbol = matcher.group(0).substring(16, matcher.group(0).length()-1);
            return geneSymbol;
        }
        return "";
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
