package org.copakb.updater.spectra;

import org.junit.Test;

/**
 * SpectraUpdate test class.
 * Created by Alan on 7/6/2015.
 */
public class SpectraUpdateTest {
    private final String COPA_TEST_FILE = "src/test/resources/test_testmodule.copa";

    @Test
    public void testUpdate() throws Exception {
        SpectraUpdate.update(COPA_TEST_FILE, -1, "LTQ", "Trypsin");
    }

    @Test
    public void testWithinRange() throws Exception {
        // TODO
    }

    @Test
    public void testAddPtm_Types() throws Exception {
        // TODO
    }
}
