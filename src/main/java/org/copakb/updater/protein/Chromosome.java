package org.copakb.updater.protein;

/**
 * Created by vincekyi on 6/4/15.
 */
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;

// Connects to HTTP and grabs a JSON structure that contains the chromosome of the specified ensembl id
public class Chromosome {
    public static String getChromosome(String id){
        if(id == "")    // return the empty string if there's no id
            return "";

        // Connect to HTTP and read in result from query
        String server = "http://beta.rest.ensembl.org";
        String ext = "/map/cdna/"+id+"/100..300?";
        String output;

        try{
            URL url = new URL(server + ext);
            URLConnection connection = url.openConnection();
            HttpURLConnection httpConnection = (HttpURLConnection)connection;
            connection.setRequestProperty("Content-Type", "application/json");
            InputStream response = connection.getInputStream();
            int responseCode = httpConnection.getResponseCode();
            if(responseCode != 200) {
                throw new RuntimeException("Response code was not 200. Detected response was "+responseCode);
            }

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
            }catch (IOException logOrIgnore) {
                logOrIgnore.getMessage();
                return "";
            }finally{
                // clean up
                if (reader != null){
                    reader.close();
                }
            }
        }catch(MalformedURLException ex){
            System.out.println(ex.getMessage());
            return "";
        }
        catch (IOException ex) {
            ex.getMessage();
            return "";
        }
        catch(RuntimeException ex){
            System.out.println(ex.getMessage());
            return "";
        }
        //extract the chromosome information
        int index = output.indexOf("seq_region_name");
        int start = output.indexOf('\"', index+17);
        int end = output.indexOf('\"', start+1);

        return output.substring(start+1, end);
    }

}
