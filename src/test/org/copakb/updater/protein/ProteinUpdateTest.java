package org.copakb.updater.protein;

import org.copakb.server.dao.model.Gene;
import org.copakb.server.dao.model.ProteinCurrent;
import org.junit.Test;

/**
 * ProteinUpdate test class.
 * Created by Alan on 7/6/2015.
 */
public class ProteinUpdateTest {
    private final static String ENSEMBL_ID = "ENSG00000172115";
    private final static String UNIPROT_ID = "P99999";
    private final static String FASTA_TEST_FILE = "src/test/resources/test.fasta";
    private final static String PID_TEST_FILE = "src/test/resources/test.pid";

    @Test
    public void testGetGeneFromEnsembl() throws Exception {
        Gene gene = ProteinUpdate.getGeneFromEnsembl(ENSEMBL_ID);

        assert gene != null;
        assert gene.getGene_name().equals("CYCS");
        assert gene.getEnsembl_id().equals(ENSEMBL_ID);
    }

    @Test
    public void testGetProteinFromUniprot() throws Exception {
        ProteinCurrent protein = ProteinUpdate.getProteinFromUniprot(UNIPROT_ID);

        assert protein != null;
        assert protein.getProtein_acc().equals("P99999");
        assert protein.getProtein_name().equals("Cytochrome c");
        assert protein.getChromosome().equals("Chromosome 7");
        assert protein.getSequence().length() == 105;
    }

    @Test
    public void testAddProtein() throws Exception {
        ProteinUpdate.addProtein(ProteinUpdate.getProteinFromUniprot(UNIPROT_ID));

        // TODO Verify add
    }

    @Test
    public void testUpdateFromFasta() throws Exception {
        ProteinUpdate.updateFromFasta(FASTA_TEST_FILE);

        // TODO Verify add
    }

    @Test
    public void testUpdateFromIDs() throws Exception {
        ProteinUpdate.updateFromIDs(PID_TEST_FILE);

        // TODO Verify add
    }
}
