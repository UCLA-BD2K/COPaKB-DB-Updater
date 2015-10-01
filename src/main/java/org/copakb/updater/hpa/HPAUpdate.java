package org.copakb.updater.hpa;

import org.copakb.server.dao.DAOObject;
import org.copakb.server.dao.ProteinDAO;
import org.copakb.server.dao.model.Antibody;
import org.copakb.server.dao.model.HPAProtein;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * Updates the database with HPA entries from a XML file
 * http://www.proteinatlas.org/download/proteinatlas.xml.gz
 * <p>
 * Based on doc/HPA_Program.cs
 * <p>
 * Created by Alan Kha on 6/22/15.
 */
public class HPAUpdate {
    private static final String HPA_XML_HEADER = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n" +
            "<proteinAtlas xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" schemaVersion=\"2.1\">";
    private static final String HPA_XML_FOOTER = "\t<copyright>Copyrighted by the Human Protein Atlas, " +
            "http://www.proteinatlas.org/about</copyright>\n" + "</proteinAtlas>";

    /**
     * Updates the HPA tables with HPA proteins
     *
     * @param filename Filename of HPA XML
     */
    public static void update(String filename) {
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(filename);
            Scanner sc = new Scanner(inputStream, "UTF-8"); // Scanner avoids loading the entire XML file into memory

            // Buffer individual proteins for parsing
            // TODO Potential improvement by buffering more proteins at once (save DOM parse overhead?)
            StringBuilder buffer = null;
            ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();
            List<String> failedIDs = new ArrayList<>();
            while (sc.hasNextLine()) {
                String line = sc.nextLine();

                if (line.contains("<entry")) {
                    // Create fresh StringBuilder for protein entry
                    buffer = new StringBuilder();
                    buffer.append(HPA_XML_HEADER + "\n");
                }

                if (buffer != null) {
                    // Append only if within protein entry
                    buffer.append(line).append("\n");

                    if (line.contains("</entry>")) {
                        // Complete, parse, and remove protein XML
                        buffer.append(HPA_XML_FOOTER + "\n");
                        try {
                            for (HPAProtein protein : parseProteinXML(buffer.toString())) {
                                if (!addHPAProtein(protein)) {
                                    failedIDs.add(protein.getEnsembl_id());
                                }
                            }
                        } catch (ParserConfigurationException | SAXException e) {
                            e.printStackTrace();
                        }
                        buffer = null;
                    }
                }
            }

            sc.close();
            inputStream.close();

            // Print failed IDs
            failedIDs.forEach(System.out::println);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean addHPAProtein(HPAProtein protein) {
        ProteinDAO proteinDAO = DAOObject.getInstance().getProteinDAO();

        try {
            String result = proteinDAO.addHPAProtein(protein);

            if (result.equals("Existed")) {
                System.out.println(protein.getEnsembl_id() + " already exists");
            } else {
                System.out.println(protein.getEnsembl_id() + " added");
            }
        } catch (Exception e) {
            System.err.println(protein.getEnsembl_id() + " failed");
            e.printStackTrace();
            return false;
        }

        return true;
    }

    public static List<HPAProtein> parseProteinXML(String xmlString)
            throws ParserConfigurationException, IOException, SAXException {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xmlString)));
        doc.getDocumentElement().normalize();

        // Iterate through protein entries
        List<HPAProtein> proteins = new ArrayList<>();
        NodeList entries = doc.getElementsByTagName("entry");
        for (int entryIndex = 0; entryIndex < entries.getLength(); entryIndex++) {
            Element proteinElement = (Element) doc.getElementsByTagName("entry").item(entryIndex);
            HPAProtein hpa = new HPAProtein();

            // Get Ensembl gene ID
            hpa.setEnsembl_id(((Element) proteinElement
                    .getElementsByTagName("identifier").item(0))
                    .getAttribute("id"));

            // Get protein name
            hpa.setProteinName(proteinElement.getElementsByTagName("name").item(0).getTextContent());

            // Get protein expression summary
            try {
                hpa.setExpressionSummary(((Element) proteinElement
                        .getElementsByTagName("tissueExpression").item(0))
                        .getElementsByTagName("summary").item(0)
                        .getTextContent());
            }
            catch (Exception e) {
                hpa.setExpressionSummary("");
            }

            // Get protein classes
            List<String> proteinClasses = new ArrayList<>();
            NodeList classNodes = ((Element) proteinElement.getElementsByTagName("proteinClasses").item(0))
                    .getElementsByTagName("proteinClass");
            for (int classIndex = 0; classIndex < classNodes.getLength(); classIndex++) {
                Element proteinClass = (Element) classNodes.item(classIndex);
                // Ignore membranes, evidence, and classes with parents
                if (proteinClass.getAttribute("parent_id").isEmpty() &&
                        !proteinClass.getAttribute("name").toLowerCase().contains("membrane") &&
                        !proteinClass.getAttribute("name").toLowerCase().contains("evidence")) {
                    proteinClasses.add(proteinClass.getAttribute("name"));
                }
            }
            hpa.setProteinClass(String.join(", ", proteinClasses));

            // Get subcellular location data
            Element subLocElement = (Element) proteinElement.getElementsByTagName("subcellularLocation").item(0);
            if (subLocElement != null) {
                // Get subcell location summary
                hpa.setSubcellSummary(subLocElement.getElementsByTagName("summary").item(0).getTextContent());

                // Get subcell location image
                hpa.setSubcellImage(((Element) subLocElement
                        .getElementsByTagName("image").item(0))
                        .getElementsByTagName("imageUrl").item(0)
                        .getTextContent());

                // Get subcell locations
                List<String> mainLocations = new ArrayList<>();
                List<String> altLocations = new ArrayList<>();
                try {
                    NodeList locations = ((Element) subLocElement.getElementsByTagName("data").item(0))
                            .getElementsByTagName("location");
                    for (int j = 0; j < locations.getLength(); j++) {
                        Element location = (Element) locations.item(j);
                        if (location.getAttribute("status").equals("main")) {
                            mainLocations.add(location.getTextContent());
                        } else {
                            altLocations.add(location.getTextContent());
                        }
                    }
                    hpa.setMainLocations(String.join(", ", mainLocations));
                    hpa.setAltLocations(String.join(", ", altLocations));
                }
                catch (Exception e) {

                }
            }

            // Add antibodies
            List<String> antibodyIDs = new ArrayList<>();
            Set<Antibody> antibodies = new HashSet<>();
            NodeList antibodyNodes = proteinElement.getElementsByTagName("antibody");
            for (int antibodyIndex = 0; antibodyIndex < antibodyNodes.getLength(); antibodyIndex++) {
                Element antibodyElement = (Element) antibodyNodes.item(antibodyIndex);
                Antibody antibody = new Antibody();

                // Get antibody ID
                antibody.setAntibody_id(antibodyElement.getAttribute("id"));
                antibodyIDs.add(antibody.getAntibody_id());

                // Get Ensembl ID
                antibody.setEnsembl_id(hpa.getEnsembl_id());

                // Get antibody immunohistochemistry (IHC) data
                Element tissueExpression = tissueExpression = (Element) antibodyElement.getElementsByTagName("tissueExpression").item(0);
                if(tissueExpression != null && tissueExpression.hasAttribute("technology")) {
                    if (tissueExpression.getAttribute("technology").equals("IH") &&
                            tissueExpression.getAttribute("assayType").equals("tissue")) {
                        antibody.setSummary((tissueExpression.getElementsByTagName("summary").item(0).getTextContent()));
                    }
                }
                else {
                    antibody.setSummary("");
                }

                // Check for antibody heart expression
                if(tissueExpression != null) {
                    NodeList tissueDataNodes = tissueExpression.getElementsByTagName("data");
                    for (int dataIndex = 0; dataIndex < tissueDataNodes.getLength(); dataIndex++) {
                        Element dataElement = (Element) tissueDataNodes.item(dataIndex);
                        if (dataElement.getElementsByTagName("tissue").item(0).getTextContent()
                                .equals("heart muscle")) {
                            Element cellElement = (Element) dataElement.getElementsByTagName("tissueCell").item(0);

                            // Verify myocyte cell types
                            if (!cellElement.getElementsByTagName("cellType").item(0).getTextContent()
                                    .equals("myocytes")) {
                                throw new RuntimeException("Antibody " + antibody.getAntibody_id() + " of protein " +
                                        antibody.getEnsembl_id() + "contains no heart muscle myocyte cells.");
                            }

                            // Get myocyte staining and intensity levels
                            NodeList levels = cellElement.getElementsByTagName("level");
                            antibody.setMyocyteStaining(levels.item(0) // First level item is staining
                                    .getTextContent());
                            antibody.setMyocyteIntensity(levels.item(1) // Second level item is staining
                                    .getTextContent());

                            // Get first patient info
                            Element patientElement = (Element) dataElement.getElementsByTagName("patient").item(0);

                            // Get sample patient sex
                            antibody.setSamplePatientSex(patientElement
                                    .getElementsByTagName("sex").item(0)
                                    .getTextContent());

                            // Get sample patient age
                            antibody.setSamplePatientAge(Integer.valueOf(patientElement
                                    .getElementsByTagName("age").item(0)
                                    .getTextContent()));

                            // Get first sample info of patient
                            Element sampleElement = (Element) patientElement.getElementsByTagName("sample").item(0);

                            // Get sample description
                            NodeList snomedParams = ((Element) sampleElement
                                    .getElementsByTagName("snomedParameters").item(0))
                                    .getElementsByTagName("snomed");
                            List<String> descs = new ArrayList<>();
                            for (int snomedIndex = 0; snomedIndex < snomedParams.getLength(); snomedIndex++) {
                                descs.add(((Element) snomedParams.item(snomedIndex)).getAttribute("tissueDescription"));
                            }
                            antibody.setSampleDesc(String.join(", ", descs));

                            // Get sample image
                            antibody.setSampleImage(((Element) ((Element) sampleElement
                                    .getElementsByTagName("assayImage").item(0))
                                    .getElementsByTagName("image").item(0))
                                    .getElementsByTagName("imageUrl").item(0)
                                    .getTextContent());
                            break;
                        }
                    }
                }

                antibodies.add(antibody);
            }
            hpa.setAntibodies(antibodies);
            hpa.setAssaySummary(String.join(", ", antibodyIDs));

            proteins.add(hpa);
        }

        return proteins;
    }
}
