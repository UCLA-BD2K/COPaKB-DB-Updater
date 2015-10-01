package org.copakb.updater.disease;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.DiseaseDAO;
import org.copakb.server.dao.model.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Updates diseases.
 * Created by vincekyi on 6/17/15.
 */
public class DiseaseUpdate {
    private final static int BATCH_SIZE = 100;
    private final static String API_KEY = "030D6F97830E4C3BB0EB93407A1EC93F66887C80";
    private final static String FORMAT = "json";
    private final static String OMIM_URL = "http://api.omim.org/api/search/geneMap?search=";
    private final static String OMIM_URL2 = "http://api.omim.org/api/entry?mimNumber=";
    private final static String OMIM_DESC = "include=text:description";
    private final static String OMIM_REF = "http://api.omim.org/api/entry/referenceList?mimNumber=";

    public static void update() {
        DiseaseDAO diseaseDAO = DAOObject.getInstance().getDiseaseDAO();

        // Iterate through genes
        int geneIndex = 0;
        List<Gene> genes = diseaseDAO.limitedGeneList(geneIndex, BATCH_SIZE);
        while (!genes.isEmpty()) {
            for (Gene gene : genes) {
                System.out.println("**********************");
                System.out.println("FOR GENE: " + gene.getGene_symbol() + "\n");
                for (Disease disease : getDiseases(gene.getGene_symbol())) {
                    System.out.println("Adding: " + disease.getDOID());
                    diseaseDAO.addDisease(disease);
                    diseaseDAO.addDiseaseGene(getDiseaseGene(disease, gene));
                }
            }

            genes = diseaseDAO.limitedGeneList(geneIndex, BATCH_SIZE);
            geneIndex += BATCH_SIZE;
        }
    }

    public static JSONObject getJSON(String uri) {
        uri += "&apiKey=" + API_KEY + "&format=" + FORMAT;
        uri = uri.replaceAll(" ", "%20");

        HttpResponse<JsonNode> request = null;
        try {
            request = Unirest.get(uri)
                    .header("accept", "application/json")
                    .asJson();

            return request.getBody().getObject();

        } catch (UnirestException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Extracts DiseaseGene information from omim.org.
     * Uses disease and gene information to create a DiseaseGene object which includes references and pubmed information.
     *
     * @param disease disease object that is used to find the DiseaseGene object
     * @param gene    gene object that is used to find the DiseaseGene object
     * @return defined DiseaseGene object that is mapped by the combination of the disease and gene parameters
     */
    public static DiseaseGene getDiseaseGene(Disease disease, Gene gene) {
        DiseaseGene diseaseGene = new DiseaseGene();
        diseaseGene.setGene(gene);
        diseaseGene.setDisease(disease);

        JSONObject json = getJSON(OMIM_REF + disease.getDOID());

        JSONObject omim = json.getJSONObject("omim");

        // only takes first pubmed information with all necessary values
        JSONArray referenceLists = omim.getJSONArray("referenceLists");
        JSONArray referenceList = referenceLists.getJSONObject(0).getJSONArray("referenceList");
        JSONObject reference = null;
        for (int i = 0; i < referenceList.length(); i++) { // iterate until it has an entry with all values
            reference = referenceList.getJSONObject(i).getJSONObject("reference");
            if (reference.has("authors") && reference.has("pubmedID") && reference.has("title")) {
                diseaseGene.setPubmed_author("");
                diseaseGene.setPubmed_id("");
                diseaseGene.setPubmed_title("");
                break;
            }
        }

        // in case no entry has all the values
        try {
            diseaseGene.setPubmed_author(reference.getString("authors"));
            diseaseGene.setPubmed_id(String.valueOf(reference.getInt("pubmedID")));
            diseaseGene.setPubmed_title(reference.getString("title"));
        } catch (Exception e) {
            System.out.println("Could not find referenced literature");
        }

        return diseaseGene;
    }

    /**
     * Extracts Disease information from omim.org.
     * Does this for all diseases related to a specified gene.
     *
     * @param gene gene symbol
     * @return list of diseases relevant to the gene specified
     */
    public static List<Disease> getDiseases(String gene) {
        ArrayList<Disease> diseases = new ArrayList<Disease>(0);
        if (gene.isEmpty()) {
            return diseases;
        }

        JSONObject json = getJSON(OMIM_URL + gene);

        JSONObject omim = json.getJSONObject("omim");
        JSONObject searchResponse = omim.getJSONObject("searchResponse");
        JSONArray geneMapList = searchResponse.getJSONArray("geneMapList");
        for (int k = 0; k < geneMapList.length(); k++) {
            JSONObject geneMap = geneMapList.getJSONObject(k).getJSONObject("geneMap");
            if (geneMap.has("phenotypeMapList")) {
                JSONArray phenotypeMapList = geneMap.getJSONArray("phenotypeMapList");
                for (int i = 0; i < phenotypeMapList.length(); i++) {
                    JSONObject j = phenotypeMapList.getJSONObject(i);
                    JSONObject phenotypeMap = j.getJSONObject("phenotypeMap");

                    Disease disease = new Disease();
                    int omimId;

                    if (phenotypeMap.has("phenotypeMimNumber")) {
                        omimId = phenotypeMap.getInt("phenotypeMimNumber");
                        disease.setDOID(omimId);
                    } else {
                        continue;
                    }

                    if (phenotypeMap.has("phenotype")) {
                        // FORMAT the name
                        disease.setName(phenotypeMap.getString("phenotype"));
                    } else {
                        continue;
                    }

                    System.out.println(omimId);
                    try {
                        // get description using omim id
                        String description = "";
                        JSONObject json2 = getJSON(OMIM_URL2 + omimId + "&" + OMIM_DESC);
                        JSONObject omim2 = json2.getJSONObject("omim");
                        JSONArray entryList = omim2.getJSONArray("entryList");
                        for (int x = 0; x < entryList.length(); x++) {
                            JSONObject entry = entryList.getJSONObject(x).getJSONObject("entry");
                            if (entry.has("textSectionList")) {
                                JSONArray textSectionList = entry.getJSONArray("textSectionList");
                                for (int y = 0; y < textSectionList.length(); y++) {
                                    JSONObject textSection = textSectionList.getJSONObject(y).getJSONObject("textSection");
                                    description += textSection.getString("textSectionContent") + " ";
                                }
                            }
                        }

                        if (description.length() >= 1000) { // truncate descriptions that are too long
                            description = description.substring(0, 1000);
                        }
                        disease.setDescription(description);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    // Flag heart disease if the name contains cardio or heart
                    disease.setHeart_disease(disease.getName().contains("cardio") || disease.getName().contains("heart"));
                    diseases.add(disease);
                }
            }
        }

        return diseases;
    }
}
