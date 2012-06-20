package com.mambu.xbrl.server;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.jdo.PersistenceManager;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.mambu.xbrl.server.util.MambuRequestParser;
import com.mambu.xbrl.shared.TenantSettings;

public class InstallServlet extends HttpServlet {

    private static final Logger log = Logger.getLogger(InstallServlet.class.getName());
    
	private static final long serialVersionUID = 4166785295861425857L;

	@SuppressWarnings("unchecked")
	@Override
	public void doPost(HttpServletRequest req, HttpServletResponse resp) {

		MambuRequestParser parser = new MambuRequestParser(req);
		
		//check if is valid
		log.info("Is valid request: " + parser.isValidRequest(Constants.SECRET_KEY));
		
		//TODO: don't do anything if not valid
		
		//now get the params
		HashMap<String, String> payloadMap = parser.getPayloadMap();
		
		Map<String, String[]> params = req.getParameterMap();
		String tenantID = payloadMap.get("TENANT_ID");
		String userName = payloadMap.get("USERNAME");
		String pword = payloadMap.get("PASSWORD");
		String hostname = payloadMap.get("HOSTNAME");
		
		hostname = hostname == null ? req.getRemoteHost() : hostname; // hostname
		
		//do some valiation
		if (tenantID == null ||  userName == null || pword == null) {
			log.severe("Undeclared params" + params.toString());
			return;
		}

		// create tenant
		
		PersistenceManager pm = PMF.get().getPersistenceManager();
		
		try {
			TenantSettings tenant = new TenantSettings();
			tenant.setTenantID(tenantID);
			tenant.setUsername(userName);
			tenant.setPassword(pword);
			tenant.setDomain(hostname);
			
			pm.makePersistent(tenant);	
			
			log.info("Stored data");
			
			
		} finally {
			pm.close();
		}

	}
}
