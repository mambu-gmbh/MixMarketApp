package com.mambu.xbrl.server;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.servlet.http.HttpServletRequest;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import com.mambu.accounting.shared.model.GLAccount;
import com.mambu.apisdk.MambuAPIFactory;
import com.mambu.apisdk.exception.MambuApiException;
import com.mambu.intelligence.shared.model.Intelligence.Indicator;
import com.mambu.xbrl.client.XBRLProcessService;
import com.mambu.xbrl.server.util.DateUtils;
import com.mambu.xbrl.server.util.PMF;
import com.mambu.xbrl.shared.Duration;
import com.mambu.xbrl.shared.IndicatorElementMap;
import com.mambu.xbrl.shared.PeriodType;
import com.mambu.xbrl.shared.XBRLElement;
import com.mambu.xbrl.shared.TenantSettings;

/**
 * The server side implementation of the RPC service.
 */
@SuppressWarnings("serial")
public class XBRLProcessServiceImpl extends RemoteServiceServlet implements XBRLProcessService {

	private static final Logger log = Logger.getLogger(XBRLProcessServiceImpl.class.getName());

	private static final ScriptEngine SCRIPT_ENGINE = new ScriptEngineManager().getEngineByName("JavaScript");

	/**
	 * Returns the tenant ID for this session
	 * 
	 * @return
	 */
	public String getTenantID() {
		HttpServletRequest request = this.getThreadLocalRequest();
		String tenantID = (String) request.getSession().getAttribute("TENANT_ID");
		return tenantID;
	}

	/**
	 * Generates the xml for a given connection with the specified inputs
	 */
	@Override
	public String generateXML() {

		// get the params
		TenantSettings params = getParams();

		try {
			MambuAPIFactory.setUp(params.getDomain(), params.getUsername(), params.getUsername());

		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e.getMessage());
		}

		String generatedXML = "";

		XBRLGenerator xBRLGenerator = new XBRLGenerator();
		xBRLGenerator.addLink();
		xBRLGenerator.addContext(params.getDurations());
		xBRLGenerator.addNumberUnit();
		try {
			
			xBRLGenerator.addCurrencyUnit(MambuAPIFactory.getOrganizationService().getCurrency().getCode());
		} catch (MambuApiException e) {
			e.printStackTrace();
		}

		// now process the xbrl financial inputs
		processXBRLFinancials(xBRLGenerator, params);

		// and process
		try {
			processXBRLIndicators(xBRLGenerator);
		} catch (MambuApiException e) {
			e.printStackTrace();
		}

		generatedXML = xBRLGenerator.generate();

		return generatedXML;
	}

	/**
	 * Processses a single xbrml request
	 */

	@Override
	public String processRequest(XBRLElement element, String input) throws IllegalArgumentException {

		TenantSettings params = getParams();

		try {
			MambuAPIFactory.setUp(params.getDomain(), params.getUsername(), params.getUsername());
		} catch (Exception e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e.getMessage());
		}

		Duration duration = null;
		if (element.getPeriod() == PeriodType.DURATION && params.getDurations().size() > 0) {
			duration = params.getDurations().get(0);
		}

		BigDecimal processInputstring = processInputString(input, duration);

		return processInputstring.stripTrailingZeros().toPlainString();
	}

	/**
	 * Processes an individual input string
	 * @param input
	 * 
	 * @return
	 */
	private BigDecimal processInputString(String input, Duration duration) {
		String oriString = new String(input);

		// parse input
		ArrayList<String> glCodes = getGLCodes(input);

		for (String glCode : glCodes) {
			// get the balance
			BigDecimal accountBalance;
			try {
				accountBalance = getAccountBalance(glCode, duration);
			} catch (MambuApiException e) {
				log.severe(e.getErrorMessage());
				throw new IllegalArgumentException(e.getErrorMessage());
			}

			// replace it
			oriString = replace(glCode, accountBalance, oriString);

		}

		// evaluate the expression
		Float eval = 0f;
		try {
			Number value = (Number) SCRIPT_ENGINE.eval(oriString);
			eval = value.floatValue();
		} catch (ScriptException e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e.getMessage());
		}

		return new BigDecimal(eval);
	}

	/**
	 * Gets the balance of a givent account
	 * @param glCode
	 * @param duration
	 * 
	 * @return
	 * @throws MambuApiException
	 */
	private BigDecimal getAccountBalance(String glCode, Duration duration)
			throws MambuApiException {

		// get the account balance, with an optional date range
		GLAccount glAccount;
		if (duration == null) {
			glAccount = MambuAPIFactory.getAccountingService().getGLAccount(glCode);
		} else {
			glAccount = MambuAPIFactory.getAccountingService().getGLAccount(glCode, DateUtils.format(duration.from), DateUtils.format(duration.to));
		}

		return glAccount.getBalance();

	}

	/**
	 * Extracts gl account placeholders from a template
	 * 
	 * @param template
	 * @return
	 */
	private ArrayList<String> getGLCodes(String template) {

		ArrayList<String> placeholders = new ArrayList<String>();

		if (template != null) {

			Pattern p = Pattern.compile("\\{(.*?)\\}");
			Matcher m = p.matcher(template);

			while (m.find()) { // find next match
				String match = m.group();
				String code = match.substring(1, match.length() - 1);
				placeholders.add(code);
			}

		}
		return placeholders;
	}

	/**
	 * Replaces a gl code in a template with a result and a number of
	 * 
	 * @param glCode
	 *            the code we're replacing, eg: 41
	 * @param result
	 *            the result (value of the gl code evaluation)
	 * @param originalString
	 *            the original template eg: {1} + {41}
	 * @return
	 */
	private String replace(String glCode, BigDecimal result, String originalString) {
		return originalString.replaceAll("\\{" + glCode + "\\}", result.toString());
	}

	/**
	 * Process the XBRL financial elements specified as parameters
	 * @param xBRLGenerator
	 * @param params
	 */
	private void processXBRLFinancials(XBRLGenerator xBRLGenerator, TenantSettings params) {
		for (Entry<XBRLElement, String> entryValues : params.getValues().entrySet()) {

			XBRLElement key = entryValues.getKey();
			// skip empties
			if (entryValues.getValue() == null || entryValues.getValue().isEmpty()) {
				continue;
			}

			// if it's not a number
			if (key.getPeriod() == null) {
				xBRLGenerator.addElement(key, entryValues.getValue());
				continue;
			}

			// process the key based on it's period type
			switch (key.getPeriod()) {
			case DURATION:
				// go through all durations
				for (Duration durr : params.getDurations()) {

					BigDecimal processInputstring = processInputString(entryValues.getValue(), durr);

					// add to the xml
					xBRLGenerator.addElement(key, processInputstring, durr);
				}

				break;

			// for instances processes it for just right now
			case INSTANT:
				BigDecimal processInputstring = processInputString(entryValues.getValue(), null);

				// add to the xml
				xBRLGenerator.addElement(key, processInputstring);
				break;

			}

		}
	}

	/**
	 * Processes the indicators
	 * @param xBRLGenerator
	 * 
	 * @throws MambuApiException
	 */
	private void processXBRLIndicators(XBRLGenerator xBRLGenerator) throws MambuApiException {

		// go through the map
		for (Entry<Indicator, XBRLElement> entries : IndicatorElementMap.getMap().entrySet()) {

			BigDecimal indicatorValue = MambuAPIFactory.getIntelligenceService().getIndicator(entries.getKey());

			xBRLGenerator.addElement(entries.getValue(), indicatorValue);
		}
	}


	/**
	 * Stores parameters
	 */
	@Override
	public TenantSettings storeParams(TenantSettings params) {

		PersistenceManager pm = PMF.get().getPersistenceManager();

		try {
			params = pm.makePersistent(params);
			params = pm.detachCopy(params);

		} finally {
			pm.close();
		}

		return params;

	}

	/**
	 * Retrieves parameters
	 */
	@Override
	public TenantSettings getParams() {

		String tenantID = getTenantID();
		log.info("got tenant: " + tenantID);

		PersistenceManager pm = PMF.get().getPersistenceManager();

		try {

			Query query = pm.newQuery(TenantSettings.class, "tenantID == tenantIDParam");
			query.declareParameters("String tenantIDParam");
			query.setUnique(true);
			TenantSettings settings = (TenantSettings) query.execute(tenantID);
			if (settings != null) {
				settings = pm.detachCopy(settings);
			}

			log.info("Retreived params for " + tenantID);

			return settings;

		} finally {
			pm.close();

		}

	}
}
