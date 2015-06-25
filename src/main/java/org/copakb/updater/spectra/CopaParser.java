package org.copakb.updater.spectra;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

/**
 * Created by vincekyi on 6/11/15.
 */
public class CopaParser {
    private HashMap fields;
    private String spectraInfo;
    private String file;
    private BufferedReader reader;

    public CopaParser(String filename){
        file = filename;
        fields = new HashMap();
        spectraInfo = "";
        initializeFile();
    }
    // this function initializes the parser and returns a string stating whether it was a success or not
    private String initializeFile(){
        reader = null;
        try{
            //String file =  "C:\\Users\\COPaKB\\Desktop\\com.database.copa.updater.CopaUpdater\\src\\HumanProteasome.copa";
            reader = new BufferedReader(new FileReader(file));

            //checks extension: .copa
            if(!file.substring(file.length() - 4).equals("copa")){
                System.out.println("File type must be .copa!");
                return "File type must be .copa!";
            }
        }catch(FileNotFoundException ex){
            System.out.println("Not a valid file!");
            return "Not a valid file!";
        }
        return "Success";
    }
    //returns the map that holds the current entry's info information
    public HashMap getCurrentEntry(){
        return fields;
    }

    public String getSpectraInfo() { return spectraInfo; }

    //parses through the header and splits it into fields
    private void processHeader(String header){
        //System.out.println(header);
        //isolate each field
        String tokens[] = header.split("\\|\\|\\|");
        for(int i = 1; i<tokens.length; i++){
            String field[] = tokens[i].split(":::");
            //System.out.println(tokens[i]);
            if(field.length == 1)
                fields.put(field[0], "");
            else
                fields.put(field[0], field[1]);
            //System.out.println(field[0]+": "+fields.get(field[0]));
        }
    }

    public void closeBuffer(){
        try{
            if(reader!=null)
                reader.close();
        }
        catch(IOException ex){

        }
    }

    //processes each entry of data in the .copa file
    //returns 1 if processed entry correctly AND there is another entry after it
    //returns 0 if processed entry correctly AND there are no more entries after it
    //returns -1 if there is no entry to process
    public int processEntry(){
        try{
            // read in the first line: contains header
            String line = reader.readLine();
            if(line!=null) {
                if(line.charAt(0) == 'H')
                    fields.put("header", line);
                else
                    fields.put("header", "H" + line);
                processHeader(line);

                // store the string of spectra points
                StringBuilder spectra = new StringBuilder();

                // observe first character of line
                // if it's part of the entry append it to spectra
                int firstChar;
                while((firstChar = reader.read())!= -1 &&
                        (char)firstChar != 'H'){
                    line = reader.readLine();
                    spectra.append((char)firstChar);
                    spectra.append(line+"\n");
                }
                fields.put("spectrum", spectra.toString());

                if((char)firstChar == 'H'){
                    return 1;      // processed entry, but there's more entries
                }
                else
                    return 0;      // processed entry, and there's no more after
            }
        } catch(IOException ex){
            System.out.println("IO Exception!");
            return -1;
        }
        return -1;   //did not process due to no more entries
    }

    public static void main(String[] args) {
        CopaParser cp = new CopaParser("./src/main/resources/test.copa");
        if(cp.processEntry()>0) {
            for(Object s : cp.getCurrentEntry().values())
                System.out.println(s.toString());
        }
        cp.closeBuffer();
    }
}
