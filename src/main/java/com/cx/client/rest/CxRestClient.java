package com.cx.client.rest;


import com.cx.client.CxPluginHelper;
import com.cx.client.dto.LoginRequest;
import com.cx.client.dto.OSAFile;
import com.cx.client.exception.CxClientException;
import com.cx.client.rest.dto.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.security.KeyManagementException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by: Dorg.
 * Date: 16/06/2016.
 */
public class CxRestClient {

    private final String username;
    private final String password;
    private Client client;
    private WebTarget root;

    public static final String OSA_SCAN_PROJECT_PATH = "osa/scans";
    public static final String OSA_SCAN_STATUS_PATH = "osa/scans/{scanId}";
    public static final String OSA_SCAN_SUMMARY_PATH = "osa/reports";
    public static final String OSA_SCAN_LIBRARIES_PATH = "/osa/libraries";
    public static final String OSA_SCAN_VULNERABILITIES_PATH = "/osa/vulnerabilities";
    private static final String AUTHENTICATION_PATH = "auth/login";
    private static final String ROOT_PATH = "CxRestAPI";
    public static final String CSRF_TOKEN_HEADER = "CXCSRFToken";
    public static final long MAX_ITEMS = 1000000;
    private ArrayList<Object> cookies;
    private String csrfToken;
    private ObjectMapper mapper = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(CxRestClient.class);


    private ClientResponseFilter clientResponseFilter = new ClientResponseFilter() {

        public void filter(ClientRequestContext clientRequestContext, ClientResponseContext clientResponseContext) throws IOException {
            // copy cookies
            if (clientResponseContext.getCookies() != null) {
                if (cookies == null) {
                    cookies = new ArrayList<Object>();
                }
                //cookies.clear();
                cookies.addAll(clientResponseContext.getCookies().values());

                if (clientResponseContext.getCookies().get(CSRF_TOKEN_HEADER) != null) {
                    csrfToken = clientResponseContext.getCookies().get(CSRF_TOKEN_HEADER).getValue();
                }
            }
        }

    };


    private ClientRequestFilter clientRequestFilter = new ClientRequestFilter() {
        public void filter(ClientRequestContext clientRequestContext) throws IOException {
            if (cookies != null) {
                clientRequestContext.getHeaders().put("Cookie", cookies);
            }

            if (csrfToken != null) {
                clientRequestContext.getHeaders().putSingle(CSRF_TOKEN_HEADER, csrfToken);
            }

            Object contentType = clientRequestContext.getHeaders().getFirst("Content-Type");
            String header = contentType == null ? "v=1" : contentType + ";v=1";
            clientRequestContext.getHeaders().putSingle("Content-Type", header);

        }
    };


    public CxRestClient(String hostname, String username, String password) {
        this.username = username;
        this.password = password;
        client = ClientBuilder.newBuilder().register(clientRequestFilter).register(clientResponseFilter).build();
        root = client.target(hostname).path(ROOT_PATH);
    }

    public void destroy() {
        client.close();
    }

    public void disableCertificateValidation() {

        try {
            client.getSslContext().init(null, CxPluginHelper.createFakeTrustManager(), null);
        } catch (KeyManagementException e) {
            log.warn("Failed to disable SSL/TLS certificate validation");
        }
    }


    public void login() throws CxClientException {
        cookies = null;
        csrfToken = null;
        LoginRequest credentials = new LoginRequest(username, password);
        Response response = root.path(AUTHENTICATION_PATH).request().post(Entity.entity(credentials, MediaType.APPLICATION_JSON));
        validateResponse(response, Response.Status.OK, "Failed to login");
    }

    public CreateOSAScanResponse createOSAScan(long projectId, List<OSAFile> sha1s) throws CxClientException {


        CreateOSAScanRequest requestBody = new CreateOSAScanRequest(projectId, "Maven", sha1s);

        Response response = root.path(OSA_SCAN_PROJECT_PATH).request()
                .post(Entity.entity(requestBody, MediaType.APPLICATION_JSON_TYPE));

        validateResponse(response, Response.Status.ACCEPTED, "Failed to create OSA scan");

        return convertToObject(response, CreateOSAScanResponse.class);
    }

    public OSAScanStatus getOSAScanStatus(String scanId) throws CxClientException {
        Response response = root.path(OSA_SCAN_STATUS_PATH).resolveTemplate("scanId", scanId).request().get();
        validateResponse(response, Response.Status.OK, "Failed to get OSA scan status");
        return convertToObject(response, OSAScanStatus.class);
    }

    public OSASummaryResults getOSAScanSummaryResults(String scanId) throws CxClientException {
        Response response = root.path(OSA_SCAN_SUMMARY_PATH).queryParam("scanId", scanId).request(MediaType.APPLICATION_JSON).get();
        validateResponse(response, Response.Status.OK, "Failed to get OSA scan summary results");
        return convertToObject(response, OSASummaryResults.class);
    }

    public List<Library> getOSALibraries(String scanId) throws CxClientException {
        Response response = root.path(OSA_SCAN_LIBRARIES_PATH).queryParam("scanId", scanId)
                .queryParam("itemsPerPage", MAX_ITEMS).request(MediaType.APPLICATION_JSON).get();

        validateResponse(response, Response.Status.OK, "Failed to get OSA libraries");
        return convertToObject(response, TypeFactory.defaultInstance().constructCollectionType(List.class, Library.class));
    }

    public List<CVE> getOSAVulnerabilities(String scanId) throws CxClientException {
        Response response = root.path(OSA_SCAN_VULNERABILITIES_PATH).queryParam("scanId", scanId)
                .queryParam("itemsPerPage", MAX_ITEMS).request(MediaType.APPLICATION_JSON).get();

        validateResponse(response, Response.Status.OK, "Failed to get OSA vulnerabilities");
        return convertToObject(response, TypeFactory.defaultInstance().constructCollectionType(List.class, CVE.class));
    }

    private void validateResponse(Response response, Response.Status expectedStatus, String message) throws CxClientException {
        if (response.getStatus() != expectedStatus.getStatusCode()) {
            String responseBody = response.readEntity(String.class);
            responseBody = responseBody.replace("{", "").replace("}", "").replace(System.lineSeparator(), " ").replace("  ", "");
            throw new CxClientException(message + ": " + "status code: " + response.getStatus() + ". error:" + responseBody);
        }
    }

    private <T> T convertToObject(Response response, Class<T> valueType) throws CxClientException {
        String json = response.readEntity(String.class);
        T ret = null;
        try {
            ret = mapper.readValue(json, valueType);
        } catch (IOException e) {
            log.debug("Failed to parse JSON response: [" + json + "]", e);
            throw new CxClientException("Failed to parse JSON response: " + e.getMessage());
        }
        return ret;
    }

    private <T> T convertToObject(Response response, JavaType javaType) throws CxClientException {
        String json = response.readEntity(String.class);
        T ret = null;
        try {
            ret = mapper.readValue(json, javaType);
        } catch (IOException e) {
            log.debug("Failed to parse JSON response: [" + json + "]", e);
            throw new CxClientException("Failed to parse JSON response: " + e.getMessage());
        }
        return ret;
    }
}
