/*
 *  (C) Copyright 2017 TheOtherP (theotherp@gmx.de)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.nzbhydra.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Strings;
import org.nzbhydra.debuginfos.DebugInfosProvider;
import org.nzbhydra.logging.LoggingMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;

@Component
public class UrlCalculator {

    static final Logger logger = LoggerFactory.getLogger(UrlCalculator.class);
    private UriComponentsBuilder baseBuilder;

    @Value("${server.address}")
    private String serverAddress;
    @Value("${server.port}")
    private Integer serverPort;
    @Value("${server.contextPath}")
    private String serverContextPath;
    @Autowired
    private ConfigurableEnvironment environment;

    public UriComponentsBuilder getLocalBaseUriBuilder(HttpServletRequest request) {
        String scheme;
        String host;
        Integer port = null;
        String path;

        //Reverse proxies send the x-forwarded-proto header when HTTPS is enabled
        if ("https".equalsIgnoreCase(request.getHeader("x-forwarded-proto"))) {
            logger.debug(LoggingMarkers.URL_CALCULATION, "Using scheme HTTPS because header x-forwarded-proto is set");
            scheme = "https";
        } else if (Boolean.parseBoolean(environment.getProperty("server.ssl.enabled"))) {
            logger.debug(LoggingMarkers.URL_CALCULATION, "Using scheme HTTPS because header x-forwarded-proto is not set and built-in SSL is enabled");
            scheme = "https";
        } else {
            logger.debug(LoggingMarkers.URL_CALCULATION, "Using scheme HTTP because header x-forwarded-proto is not set and built-in SSL is disabled");
            scheme = "http";
        }

        String forwardedHost = request.getHeader("x-forwarded-host"); //May look like this: mydomain.com:4000 or like this: mydomain.com
        if (forwardedHost != null) {
            int colonIndex = forwardedHost.indexOf(":");
            if (colonIndex > -1) {
                host = forwardedHost.substring(0, colonIndex);
                port = Integer.valueOf(forwardedHost.substring(colonIndex + 1));
                logger.debug(LoggingMarkers.URL_CALCULATION, "Using host {} and port {} from header x-forwarded-host {}", host, port, forwardedHost);
            } else {
                host = forwardedHost;
                String forwardedPort = request.getHeader("x-forwarded-port");
                logger.debug(LoggingMarkers.URL_CALCULATION, "Using host {} from header x-forwarded-host ", host);
                if (forwardedPort != null) {
                    port = Integer.valueOf(forwardedPort);
                    logger.debug(LoggingMarkers.URL_CALCULATION, "Using port {} from header x-forwarded-port ", port);
                }
            }
        } else {
            host = request.getHeader("host");
            if (host == null) {
                host = request.getServerName();
                logger.warn("Header host not set. Using {}. Please change your reverse proxy configuration. See https://github.com/theotherp/nzbhydra2/wiki/Exposing-Hydra-to-the-internet-and-using-reverse-proxies for more information", host);
            } else {
                int colonIndex = host.indexOf(":"); //Apache includes the port in the host header
                if (colonIndex > -1) {
                    host = host.substring(0, colonIndex);
                }
                logger.debug(LoggingMarkers.URL_CALCULATION, "Using host {} from host header", host);
            }
        }

        if (port == null) { //No x-forwarded-host header found (may be 80 and not provided), use server port
            port = request.getServerPort();
            logger.debug(LoggingMarkers.URL_CALCULATION, "Neither header x-forwarded-host nor x-forwarded-port set. Using port {} from server", port);
        }

        path = request.getContextPath();
        logger.debug(LoggingMarkers.URL_CALCULATION, "Using context path {} as path", path);

        return UriComponentsBuilder.newInstance()
                .host(host)
                .scheme(scheme)
                .port(port)
                .path(path);
    }

    /**
     * Attempts to find the bext address to the current instance without having any request data to work on
     */
    @JsonIgnore
    public UriComponentsBuilder getLocalBaseUriBuilder() {
        if (baseBuilder == null) {
            String host = serverAddress;
            logger.debug(LoggingMarkers.URL_CALCULATION, "Found configured host {}", host);
            if (host.equals("0.0.0.0")) {
                try {
                    logger.debug(LoggingMarkers.URL_CALCULATION, "Configured host 0.0.0.0 binds to all addresses. Attempting to find network address");
                    host = getLocalHostLANAddress().getHostAddress();
                    logger.debug(LoggingMarkers.URL_CALCULATION, "Found network address {}", host);
                } catch (UnknownHostException e) {
                    logger.warn("Unable to automatically determine host address. Error: {}", e.getMessage());
                }
            }
            if (Strings.isNullOrEmpty(host)) {
                logger.warn("Unable to determine host, will use 127.0.0.1");
                host = "127.0.0.1";
            }

            int port = serverPort;
            logger.debug(LoggingMarkers.URL_CALCULATION, "Using configured port", port);

            boolean isSsl = environment.getProperty("server.ssl.enabled", Boolean.class);
            baseBuilder = UriComponentsBuilder.newInstance()
                    .host(host)
                    .scheme(isSsl ? "https" : "http")
                    .port(port);
            logger.debug(LoggingMarkers.URL_CALCULATION, "Using scheme {}", isSsl ? "https" : "http");
            String urlBase = serverContextPath;

            if (urlBase != null) {
                baseBuilder.path(urlBase);
                logger.debug(LoggingMarkers.URL_CALCULATION, "Using URL path {}", urlBase);
            }
            if (host.equals("::")) {
                logger.debug(LoggingMarkers.URL_CALCULATION, "Found configured host [::]. Using [::1] as host");
                baseBuilder = baseBuilder.host("[::1]");
            }
            if (DebugInfosProvider.isRunInDocker()) {
                logger.warn("The logged address is probably not correct as you're running docker. Use the host's IP and attached port");
            }
        }
        return baseBuilder.cloneBuilder();
    }

    protected static InetAddress getLocalHostLANAddress() throws UnknownHostException {
        try {
            InetAddress candidateAddress = null;
            // Iterate all NICs (network interface cards)...
            for (Enumeration ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements(); ) {
                NetworkInterface iface = (NetworkInterface) ifaces.nextElement();
                // Iterate all IP addresses assigned to each card...
                if (iface.getDisplayName() != null && iface.getDisplayName().contains("VirtualBox")) {
                    continue;
                }
                for (Enumeration inetAddrs = iface.getInetAddresses(); inetAddrs.hasMoreElements(); ) {
                    InetAddress inetAddr = (InetAddress) inetAddrs.nextElement();
                    if (!inetAddr.isLoopbackAddress()) {

                        if (inetAddr.isSiteLocalAddress()) {
                            // Found non-loopback site-local address. Return it immediately...
                            return inetAddr;
                        } else if (candidateAddress == null) {
                            // Found non-loopback address, but not necessarily site-local.
                            // Store it as a candidate to be returned if site-local address is not subsequently found...
                            candidateAddress = inetAddr;
                            // Note that we don't repeatedly assign non-loopback non-site-local addresses as candidates,
                            // only the first. For subsequent iterations, candidate will be non-null.
                        }
                    }
                }
            }
            if (candidateAddress != null) {
                // We did not find a site-local address, but we found some other non-loopback address.
                // Server might have a non-site-local address assigned to its NIC (or it might be running
                // IPv6 which deprecates the "site-local" concept).
                // Return this non-loopback candidate address...
                return candidateAddress;
            }
            // At this point, we did not find a non-loopback address.
            // Fall back to returning whatever InetAddress.getLocalHost() returns...
            InetAddress jdkSuppliedAddress = InetAddress.getLocalHost();
            if (jdkSuppliedAddress == null) {
                throw new UnknownHostException("The JDK InetAddress.getLocalHost() method unexpectedly returned null.");
            }
            return jdkSuppliedAddress;
        } catch (Exception e) {
            UnknownHostException unknownHostException = new UnknownHostException("Failed to determine LAN address: " + e);
            unknownHostException.initCause(e);
            throw unknownHostException;
        }
    }

}
