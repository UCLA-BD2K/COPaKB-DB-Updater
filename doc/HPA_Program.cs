using System;
using System.Xml;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Collections;

namespace HPA_Program
{

    //to run HPA program, call update1 on the xml, then update2 on xml
    class HPA_Parser
    {

        static public int update1(string filename, Hashtable hashtable)
        {
            // set up xml reader
            XmlReader reader = XmlReader.Create(filename);
            XmlDocument xmlDoc = new XmlDocument(); // Create an XML document object

            DBInterface.ConnectDB();
            int num = 0;
            //int count = 20;
            // process each entry
            bool gotSubLoc = false, gotAnti = false;
            while (reader.ReadToFollowing("identifier"))// && count > 0)
            {
                HPA_Container hpa = new HPA_Container();
                gotSubLoc = false;
                gotAnti = false;

                //count--;
                // extract ipi
                reader.MoveToFirstAttribute();
                string ensg_id = reader.Value;
                hpa.ensg_id = ensg_id;
                //Console.WriteLine("ensg_id: " + ensg_id);

                //if (!hashtable.Contains(ensg_id))
                //    continue;
                num++;
                // extract main subcellular and subcellular image

                while (reader.Read())
                {

                    if (reader.Name == "subcellularLocation" && !gotSubLoc)
                    {
                        reader.ReadToFollowing("image");
                        reader.ReadToFollowing("imageUrl");
                        hpa.sub_img = reader.ReadElementContentAsString();
                        //Console.WriteLine("sub_img: "+hpa.sub_img);
                        reader.ReadToFollowing("location");
                        hpa.main_sub = reader.ReadElementContentAsString();
                        //Console.WriteLine("Found: " + hpa.ensg_id);
                        gotSubLoc = true;
                    }
                    else if (reader.Name == "antibody" && !gotAnti)
                    {
                        reader.MoveToFirstAttribute();
                        string antibody = reader.Value;
                        hpa.hpa_anti_id = antibody;
                        //Console.WriteLine("antibody: " + antibody);
                        gotAnti = true;
                    }



                    if (gotSubLoc && gotAnti)
                    {
                        DBInterface.InsertHPA(hpa);
                        DBInterface.UpdateHPA(hpa);
                        break;
                    }
                    if (reader.Name == "entry")
                    {
                        //if(!gotSubLoc || !gotAnti)
                          //  Console.WriteLine("ESCAPE: "+hpa.ensg_id);
                        break;
                    }
                }



                //Console.WriteLine("final anti: "+hpa.hpa_anti_id);


                // extract antibody id




                //Console.WriteLine("*****************************\n");

                //DBInterface.UpdateHPA(hpa);
                //DBInterface.InsertHPA(hpa);

            }
            DBInterface.CloseDB();
            Console.WriteLine("Total Matching: " + num);

            return 1;

        }
		// Update HPA (ensg_id, subcellularLocation img and FIRST main location and FIRST antibody name)

		// Update HPA (ensg_id, antibodies w/ heart tissue data)
        static public int update2(string filename, Hashtable hashtable)
        {
            // set up xml reader
            XmlReader reader = XmlReader.Create(filename);
            XmlDocument xmlDoc = new XmlDocument(); // Create an XML document object

            DBInterface.ConnectDB();
            int num = 0;
            //int count = 20;
            // process each entry
            bool gotSumm = false, gotData = false;
            while (reader.ReadToFollowing("identifier"))// && count > 0)
            {
                HPA_Container hpa = new HPA_Container();
                gotSumm = false;
                gotData = false;
                //count--;
                // extract ipi
                reader.MoveToFirstAttribute();
                string ensg_id = reader.Value;
                hpa.ensg_id = ensg_id;
                //Console.WriteLine("ensg_id: " + ensg_id);

                //if (!hashtable.Contains(ensg_id))
                //    continue;
                num++;
                // extract main subcellular and subcellular image

                while (reader.Read())
                {

                    if (reader.Name == "tissueExpression" && !gotSumm)
                    {
                        if (reader.HasAttributes && (reader.GetAttribute("technology").Equals("IH") && reader.GetAttribute("assayType").Equals("tissue")))
                        {
                            reader.ReadToFollowing("summary");
                            hpa.ihc_summary = reader.ReadElementContentAsString();
                            //Console.WriteLine("ihc summ: " + hpa.ihc_summary);
                            gotSumm = true;
                        }
                    }
                    else if (!gotData && reader.Name == "data")
                    {
                        reader.ReadToFollowing("tissue");
                        if (reader.ReadElementContentAsString().Contains("heart"))
                        {
                            reader.ReadToFollowing("quantity");

                            hpa.ihc_heart_exp = reader.ReadElementContentAsString();

                            reader.ReadToFollowing("patient");
                            reader.ReadToFollowing("sample");
                            reader.ReadToFollowing("imageUrl");

                            hpa.ihc_img = reader.ReadElementContentAsString();
                            //Console.WriteLine("ihc img: " + hpa.ihc_img);
                            gotData = true;

                        }
                    }


                    if (gotSumm && gotData)
                    {
                        DBInterface.UpdateHPA2(hpa);
                        break;
                    }
                    if (reader.Name == "entry")
                    {
                        //Console.WriteLine("ESCAPE");
                        break;
                    }
                }



                //Console.WriteLine("final anti: "+hpa.hpa_anti_id);


                // extract antibody id




                //Console.WriteLine("*****************************\n");

                DBInterface.UpdateHPA2(hpa);
                //DBInterface.InsertHPA(hpa);

            }
            DBInterface.CloseDB();
            Console.WriteLine("Total Matching: " + num);

            return 1;

        }




    }
}
