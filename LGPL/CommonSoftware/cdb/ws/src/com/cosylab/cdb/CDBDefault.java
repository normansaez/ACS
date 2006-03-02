package com.cosylab.cdb;

/*******************************************************************************
 *    ALMA - Atacama Large Millimiter Array
 *    (c) European Southern Observatory, 2006
 *    Copyright by ESO (in the framework of the ALMA collaboration)
 *    and Cosylab 2002, All rights reserved
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation; either
 *    version 2.1 of the License, or (at your option) any later version.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public
 *    License along with this library; if not, write to the Free Software
 *    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
 *
 * @author cparedes
 */
import java.io.StringReader;
import java.net.InetAddress;
import java.util.Iterator;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.omg.CORBA.ORB;
import org.xml.sax.InputSource;

import com.cosylab.CDB.WDAL;
import com.cosylab.CDB.WDALHelper;
import com.cosylab.CDB.WDAO;
import com.cosylab.CDB.XMLerror;
import com.cosylab.cdb.jdal.XMLHandler;
import com.cosylab.cdb.jdal.XMLTreeNode;

import alma.acs.util.ACSPorts;


/**
 * todo: small description what this class does
 * 
 * todo: This class currently only works with CDB component deployment data inside the Components.xml file.
 * It probably does not work when a component's deployment data is inside a separate XML file in a subdirectory.
 * Thus we need to extend this class to also deal with this alternative component description.
 * First try one way, and if it fails, try the other. 
 * 
 * @author cparedes
 */
public class CDBDefault {
	static	int indent = 0;

	public static void main(String args[]) {
		try {
			if (args.length != 2) {
				System.out.println("Usage: cmd idl_type instance_name ");
				return;
			}
			String in_type = args[0];
			String in_name = args[1];
			String curl = "MACI/Components"; 

			String strIOR = "corbaloc::" + InetAddress.getLocalHost().getHostName() + ":" + ACSPorts.getCDBPort() + "/CDB";

			// create and initialize the ORB
			ORB orb = ORB.init(new String[0], null);

			//DAL dal = DALHelper.narrow(orb.string_to_object(strIOR));
			WDAL wdal = WDALHelper.narrow(orb.string_to_object(strIOR));

			String xml = wdal.get_DAO(curl);
//			System.out.println(xml);
			SAXParserFactory factory = SAXParserFactory.newInstance();
			SAXParser saxParser = factory.newSAXParser();
			XMLHandler xmlSolver = new XMLHandler(false);
			saxParser.parse(new InputSource(new StringReader(xml)), xmlSolver);
			
			if (xmlSolver.m_errorString != null) {
				String info = "XML parser error: " + xmlSolver.m_errorString;
				XMLerror xmlErr = new XMLerror(info);
//				System.err.println(info);
				throw xmlErr;
			}
			
			XMLTreeNode node_root = xmlSolver.m_rootNode;

			//see if there is a in_name and in_type child before to do this....
			//
			//
			Iterator nodesIter = node_root.getNodesMap().keySet().iterator();
			int i = 0;
			while (nodesIter.hasNext()) {
				String key = (String) nodesIter.next();
				XMLTreeNode node = (XMLTreeNode) node_root.getNodesMap().get(key);
			
				String name = (String)node.getFieldMap().get("Name");
				String type = (String)node.getFieldMap().get("Type");
				String isDefault = (String)node.getFieldMap().get("Default");
//				System.out.println(name + "\t" + type + "\t" + isDefault);
				String strTrue = "true";
				if(in_type.equals(type)){
					if(strTrue.equals(isDefault)){
						if(in_name.equals(name)) return;
						else{
							//write Default = false
							WDAO wdao = wdal.get_WDAO_Servant(curl);
							wdao.set_string(name +"/Default", "false");
						}
					}else if(in_name.equals(name)){
//						System.out.println("yes!!!");
						//write Default = true
						WDAO wdao = wdal.get_WDAO_Servant(curl);
						wdao.set_string(in_name + "/Default", "true");
					}
				}
			}
		
			//xml = wdal.get_DAO(curl);
			
			//System.out.println("Curl data:\n" + xml);
		}
		catch (XMLerror e) {
			System.err.println("XMLerror : " + e.msg );
			e.printStackTrace();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
}
