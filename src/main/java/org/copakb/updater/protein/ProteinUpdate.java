package org.copakb.updater.protein;

import org.copakb.server.dao.model.*;
import uk.ac.ebi.kraken.interfaces.uniprot.Gene;
import uk.ac.ebi.kraken.interfaces.uniprot.Keyword;
import uk.ac.ebi.kraken.interfaces.uniprot.UniProtEntry;
import uk.ac.ebi.kraken.interfaces.uniprot.dbx.go.Go;
import uk.ac.ebi.kraken.interfaces.uniprot.dbx.go.OntologyType;
import uk.ac.ebi.kraken.interfaces.uniprot.features.Feature;
import uk.ac.ebi.kraken.uuw.services.remoting.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.sql.Connection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

/**
 * Created by vincekyi on 5/26/15.
 */
public class ProteinUpdate {

    public ProteinUpdate(){

    }

    public static void main(String[] args) {
        update("/Users/vincekyi/IdeaProjects/DatabaseUpdater/src/main/resources/test.fasta");
    }


    // updates ProteinCurrent table given fasta file
    public static void update(String file)
    {

        //Todo: check file type

        EntryRetrievalService entryRetrievalService = UniProtJAPI.factory.getEntryRetrievalService();
        UniProtEntry entry = null;
        String uniprotid = "";

        try
        {
            Scanner scanner = new Scanner(new FileInputStream(file));
            while (scanner.hasNextLine() ){
                String uniprotheader = scanner.nextLine();
                if (uniprotheader.startsWith(">") )
                {
                    uniprotid = uniprotheader.substring(4,uniprotheader.indexOf("|",4) ) ;
                    try{
                        entry = (UniProtEntry)entryRetrievalService.getUniProtEntry(uniprotid);
                        System.out.println("Able to retrieve "+uniprotid+" from UniProt");

                    }catch(Exception ex){
                        System.out.println("Uniprot did not retrieve "+uniprotid+"\t"+ex.toString()+ex.getMessage());
                        continue;

                    }


                    // gets the rest of the protein data
                    ProteinCurrent protein = retrieveDataFromUniprot(entry);

                    //Todo insert into ProteinCurrent table

                }
            }
            scanner.close();
        }catch (Exception ex)
        {
            if (entry == null)
                System.out.printf("%s:::%s\n%s\n", ex.toString(), uniprotid, ex.getMessage());
            else
                System.out.println(ex.toString()+ex.getMessage()+entry.getUniProtId());
            return;
        }
        //use HPA to get the correct ensemblegeneid for humans
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

    static ProteinCurrent retrieveDataFromUniprot(UniProtEntry e){

        // initialize
        ProteinCurrent result = new ProteinCurrent();

        String uniprotid = e.getUniProtId().toString();
        result.setProtein_acc(uniprotid);
        result.setSequence(e.getSequence().getValue());
        result.setMolecular_weight(e.getSequence().getMolecularWeight());


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

        // get gene name
        Set<org.copakb.server.dao.model.Gene> genes = new HashSet<org.copakb.server.dao.model.Gene>();
        for(Gene gene: e.getGenes()) {
            org.copakb.server.dao.model.Gene table_gene = new org.copakb.server.dao.model.Gene();
            table_gene.setGene_name(gene.getGeneName().getValue());
            genes.add(table_gene);
            System.out.println("gene = " + gene.getGeneName().getValue());
        }
        result.setGenes(genes);

        // fill in keywords
        String keywords = "";
        for(Keyword k: e.getKeywords()){
            keywords += k.getValue()+"|";
        }
        result.setKeywords(ValideSQL(keywords));

        // fill in cross reference, domain, and ensemble id
        String goTerms = "", crossRef = "", ensembl = "";

        for(uk.ac.ebi.kraken.interfaces.uniprot.DatabaseCrossReference d: e.getDatabaseCrossReferences())  {
            String temp_dName = d.getDatabase().getName();
            // d.getDatabase().toDisplayName();
            if(temp_dName.toUpperCase().substring(0, 2).equals("GO")){
                goTerms += d.toString()+"|";
                continue;
            }

            if(temp_dName.toUpperCase().contains("ENSEMBL")){
                if(d.hasThird()) {
                    ensembl += d.getThird().getValue() + "\n";
                }
            }


            crossRef += d+"\n";
        }

        //Todo insert goTerms, crossRef, ensembl into ProteinCurrent result object

        System.out.println("uniprotid = " + uniprotid);
        System.out.println("sequence = " + result.getSequence());
        System.out.println("weight = " + result.getMolecular_weight());
        System.out.println("name = " + result.getProtein_name());
        System.out.println("features = " + features);
        System.out.println("signal = " + signal);
        System.out.println("noncytoplasmic = " + noncytoplasmic);
        System.out.println("cytoplasmic = " + cytoplasmic);
        System.out.println("transmem = " + transmem);
        System.out.println("keywords = " + keywords);
        System.out.println("ensembl = " + ensembl);
        System.out.println("crossRef = " + crossRef);
        System.out.println("goTerms = " + goTerms);

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
