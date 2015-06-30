package org.copakb.updater.disease;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.ProteinDAO;
import org.copakb.server.dao.model.Disease;
import org.copakb.server.dao.model.Gene;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by vincekyi on 6/17/15.
 */
public class DiseaseUpdate {

    private final static int TOTALGENES = 2000;
    private final static int CAPACITY = 100;
    private final static String apiKey = "030D6F97830E4C3BB0EB93407A1EC93F66887C80";
    private final static String format = "json";
    private final static String omimURL = "http://api.omim.org/api/search/geneMap?search=";
    public static void update(){
        ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();

        for (int i = 0; i < TOTALGENES; i+=CAPACITY) {

            List<Gene> genes = proteinDAO.limitedGeneList(i, CAPACITY);
            if(genes.isEmpty())
                break;
            for (Gene gene : genes) {
                List<Disease> diseases = getDiseases(gene.getGene_name());
                for (Disease disease : diseases) {
                    //System.out.println(disease.getDOID()+"\t"+disease.getName());
                    //todo: update Disease_Gene table
                    //todo: update Disease table
                    // suggestion: remove description and make heart_disease a flag
                        //the flag would be manually switched by Howard
                        //the flag is by default false
                }

            }

        }
    }

    public static JSONObject getJSON(String uri){
        uri+="&apiKey="+apiKey+"&format="+format;

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

    public static List<Disease> getDiseases(String gene){
        ArrayList<Disease> diseases = new ArrayList<Disease>(0);
        if (gene.isEmpty()) {
            return diseases;
        }

        JSONObject json = getJSON(omimURL+gene);

        JSONObject omim = json.getJSONObject("omim");
        JSONObject searchResponse = omim.getJSONObject("searchResponse");
        JSONArray geneMapList = searchResponse.getJSONArray("geneMapList");
        for(int k = 0; k < geneMapList.length(); k++) {
            JSONObject geneMap = geneMapList.getJSONObject(k).getJSONObject("geneMap");
            if (geneMap.has("phenotypeMapList")) {
                JSONArray phenotypeMapList = geneMap.getJSONArray("phenotypeMapList");
                for (int i = 0; i < phenotypeMapList.length(); i++) {
                    JSONObject j = phenotypeMapList.getJSONObject(i);
                    JSONObject phenotypeMap = j.getJSONObject("phenotypeMap");


                    Disease disease = new Disease();

                    if (phenotypeMap.has("phenotypeMimNumber"))
                        disease.setDOID(phenotypeMap.getInt("phenotypeMimNumber"));
                    else
                        continue;

                    if (phenotypeMap.has("phenotype"))
                        disease.setName(phenotypeMap.getString("phenotype"));
                    else
                        continue;

                    diseases.add(disease);
                }
            }
        }

        return diseases;
    }


    public static void main(String[] args) {
        update();
    }
}
