package com.stephensugden.guacamole.net.hmac;

import org.glyptodon.guacamole.GuacamoleException;
import org.glyptodon.guacamole.net.auth.Credentials;
import org.glyptodon.guacamole.net.auth.UserContext;
import org.glyptodon.guacamole.net.auth.simple.SimpleAuthenticationProvider;
import org.glyptodon.guacamole.net.auth.simple.SimpleConnection;
import org.glyptodon.guacamole.net.auth.simple.SimpleConnectionDirectory;
import org.glyptodon.guacamole.properties.GuacamoleProperties;
import org.glyptodon.guacamole.properties.IntegerGuacamoleProperty;
import org.glyptodon.guacamole.properties.StringGuacamoleProperty;
import org.glyptodon.guacamole.protocol.GuacamoleConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.org.apache.xml.internal.security.utils.Base64;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

public class HmacAuthenticationProvider extends SimpleAuthenticationProvider {

    /**
     * Logger for this class.
     */
    private Logger logger = LoggerFactory.getLogger(HmacAuthenticationProvider.class);

    public static final long TEN_MINUTES = 10 * 60 * 1000;

    // Properties file params
    private static final StringGuacamoleProperty SECRET_KEY = new StringGuacamoleProperty() {
        @Override
        public String getName() { return "secret-key"; }
    };

    private static final StringGuacamoleProperty DEFAULT_PROTOCOL = new StringGuacamoleProperty() {
        @Override
        public String getName() { return "default-protocol"; }
    };

    private static final IntegerGuacamoleProperty TIMESTAMP_AGE_LIMIT = new IntegerGuacamoleProperty() {
        @Override
        public String getName() { return "timestamp-age-limit"; }
    };

    // these will be overridden by properties file if present
    private String defaultProtocol = "rdp";
    private long timestampAgeLimit = TEN_MINUTES; // 10 minutes

    // Per-request params
    public static final String SIGNATURE_PARAM = "signature";
    public static final String ID_PARAM = "id";
    public static final String TIMESTAMP_PARAM = "timestamp";
    public static final String PARAM_PREFIX = "guac.";

    private static final List<String> SIGNED_PARAMETERS = new ArrayList<String>() {{
        add("username");
        add("password");
        add("hostname");
        add("port");
    }};

    private SignatureVerifier signatureVerifier;

    private final TimeProviderInterface timeProvider;

    public HmacAuthenticationProvider(TimeProviderInterface timeProvider) {
        this.timeProvider = timeProvider;
	logger.debug("HMAC HmacAuthenticationProvider");
    }

    public HmacAuthenticationProvider() {
	logger.debug("HMAC HmacAuthenticationProvider2");
        timeProvider = new DefaultTimeProvider();
    }

    @Override
    public Map<String, GuacamoleConfiguration> getAuthorizedConfigurations(Credentials credentials) throws GuacamoleException {
	logger.debug("HMAC getAuthorizedConfigurations");
        if (signatureVerifier == null) {
	    logger.debug("HMAC load prop");
            initFromProperties();
        }

        GuacamoleConfiguration config = getGuacamoleConfiguration(credentials.getRequest());

        if (config == null) {
	    logger.debug("HMAC config null");
            return null;
        }

        Map<String, GuacamoleConfiguration> configs = new HashMap<String, GuacamoleConfiguration>();
        configs.put(config.getParameter("id"), config);
        return configs;
    }

    @Override
    public UserContext updateUserContext(UserContext context, Credentials credentials) throws GuacamoleException {
	logger.debug("HMAC config updateUserContext");
        HttpServletRequest request = credentials.getRequest();
        GuacamoleConfiguration config = getGuacamoleConfiguration(request);
        if (config == null) {
     	    logger.debug("HMAC config updateUserContext config null");
            return context;
        }
        String id = config.getParameter("id");
        SimpleConnectionDirectory connections = (SimpleConnectionDirectory) context.getRootConnectionGroup().getConnectionDirectory();
        SimpleConnection connection = new SimpleConnection(id, id, config);
        connections.putConnection(connection);
        return context;
    }

    private GuacamoleConfiguration getGuacamoleConfiguration(HttpServletRequest request) throws GuacamoleException {
	logger.debug("HMAC config getGuacamoleConfiguration");
        if (signatureVerifier == null) {
 	    logger.debug("HMAC config getGuacamoleConfiguration init prop");
            initFromProperties();
        }
        String signature = request.getParameter(SIGNATURE_PARAM);
        logger.debug("HMAC Request test id {}",request.getParameter("id"));
        logger.debug("HMAC Request test guac.port {}",request.getParameter("guac.port"));
        logger.debug("HMAC Request test getQueryString {}",request.getQueryString());
        logger.debug("HMAC Request test signature {}",request.getParameter("signature"));
        logger.debug("HMAC Request test timestamp {}",request.getParameter("timestamp"));
        if (signature == null) {
	    logger.debug("HMAC Request signature null");
            return null;
        }
        signature = signature.replace(' ', '+');

        String timestamp = request.getParameter(TIMESTAMP_PARAM);
        if (!checkTimestamp(timestamp)) {
	    logger.debug("HMAC Request wrong timestamp");
            return null;
        }

        GuacamoleConfiguration config = parseConfigParams(request);

        // Hostname is required!
        if (config.getParameter("hostname") == null) {
            logger.debug("HMAC Request wrong hostname");
            return null;
        }

        // Hostname is required!
        if (config.getProtocol() == null) {
            logger.debug("HMAC Request wrong protocol");
            return null;
        }

        StringBuilder message = new StringBuilder(timestamp)
                .append(config.getProtocol());

        for (String name : SIGNED_PARAMETERS) {
            String value = config.getParameter(name);
            if (value == null) {
                logger.debug("HMAC Request wrong: {}",name);
                continue;
            }
            message.append(name);
            message.append(value);
        }

	logger.debug("HMAC Request message {}",message);
        if (!signatureVerifier.verifySignature(signature, message.toString())) {
            logger.debug("HMAC Request wrong Signature verify");
            return null;
        }
        String id = request.getParameter(ID_PARAM);
        if (id == null) {
            id = "DEFAULT";
        } else {
        	// This should really use BasicGuacamoleTunnelServlet's IdentfierType, but it is private!
        	// Currently, the only prefixes are both 2 characters in length, but this could become invalid at some point.
        	// see: guacamole-client@a0f5ccb:guacamole/src/main/java/org/glyptodon/guacamole/net/basic/BasicGuacamoleTunnelServlet.java:244-252
        	id = id.substring(2);
        }
        // This isn't normally part of the config, but it makes it much easier to return a single object
        config.setParameter("id", id);
	logger.debug("HMAC getGuacamoleConfiguration config {}",config.getParameter("id"));
        return config;
    }

    private boolean checkTimestamp(String ts) {
	logger.debug("HMAC Request checkTimestamp:");
        if (ts == null) {
	    logger.debug("HMAC Request checkTimestamp null");
            return false;
        }
        long timestamp = Long.parseLong(ts, 10);
        long now = timeProvider.currentTimeMillis();
        return timestamp + timestampAgeLimit > now;
    }

    private GuacamoleConfiguration parseConfigParams(HttpServletRequest request) {
	logger.debug("HMAC Request parseConfigParams");
        GuacamoleConfiguration config = new GuacamoleConfiguration();

        Map<String, String[]> params = request.getParameterMap();

        for (String name : params.keySet()) {
            logger.debug("parseConfigParams name: {}", name);
            String value = request.getParameter(name);
            if (!name.startsWith(PARAM_PREFIX) || value == null || value.length() == 0) {
                continue;
            }
            else if (name.equals(PARAM_PREFIX + "protocol")) {
                config.setProtocol(request.getParameter(name));
            }
            else {
		logger.debug("parseConfigParams else name: {}", name);
		if(name.equals(PARAM_PREFIX + "password")) {
		    logger.debug("parseConfigParams base64 decrypt password");
		    try {
			
	                String value_decoded = new String(Base64.decode(request.getParameter(name).getBytes("UTF-8")),"UTF-8");
			config.setParameter(name.substring(PARAM_PREFIX.length()), value_decoded);
			logger.debug("parseConfigParams password {}",value_decoded);
                    } catch(Exception e) {}
		} else {
                    config.setParameter(name.substring(PARAM_PREFIX.length()), request.getParameter(name));
		}
            }
        }

        if (config.getProtocol() == null) config.setProtocol(defaultProtocol);

        logger.debug("HMAC Request config-proto: {}", config.getProtocol());
        return config;
    }

    private void initFromProperties() throws GuacamoleException {
        String secretKey = GuacamoleProperties.getRequiredProperty(SECRET_KEY);
        signatureVerifier = new SignatureVerifier(secretKey);
        defaultProtocol = GuacamoleProperties.getProperty(DEFAULT_PROTOCOL);
        if (defaultProtocol == null) defaultProtocol = "rdp";
        if (GuacamoleProperties.getProperty(TIMESTAMP_AGE_LIMIT) == null){
           timestampAgeLimit = TEN_MINUTES;
        }  else {
           timestampAgeLimit = GuacamoleProperties.getProperty(TIMESTAMP_AGE_LIMIT);
        }
        logger.debug("HMAC Reading config: secretKey {}", secretKey);
        logger.debug("HMAC Reading config: timestampAgeLimit {}", timestampAgeLimit);
    }
}
