package org.nzbhydra.downloader.sabnzbd;

import com.google.common.base.Strings;
import org.nzbhydra.GenericResponse;
import org.nzbhydra.downloader.Downloader;
import org.nzbhydra.downloader.exceptions.DownloaderException;
import org.nzbhydra.downloader.exceptions.DownloaderUnreachableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Component
public class Sabnzbd extends Downloader {

    private static final Logger logger = LoggerFactory.getLogger(Sabnzbd.class);

    @Autowired
    private RestTemplate restTemplate;

    private UriComponentsBuilder getBaseUrl() {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(downloaderConfig.getUrl()).pathSegment("api");
        if (!Strings.isNullOrEmpty(downloaderConfig.getApiKey())) {
            builder.queryParam("apikey", downloaderConfig.getApiKey());
        }
        builder.queryParam("output", "json");
        return builder;
    }

    @Override
    public void addLink(String url, String title, String category) throws DownloaderException {
        logger.info("Sending link for NZB {} to sabnzbd", title);
        if (!title.toLowerCase().endsWith(".nzbd")) {
            title += ".nzb";
        }
        UriComponentsBuilder urlBuilder = getBaseUrl();
        urlBuilder.queryParam("mode", "addurl").queryParam("name", url).queryParam("nzbname", title);
        if (!Strings.isNullOrEmpty(category)) {
            urlBuilder.queryParam("cat", category);
        }
        sendAddNzbCommand(urlBuilder, null, HttpMethod.POST);
        logger.info("Successfully added link {} for NZB \"{}\" to sabnzbd queue", url, title);
    }

    @Override
    public void addNzb(String fileContent, String title, String category) throws DownloaderException {
        logger.info("Uploading NZB {} to sabnzbd", title);
        UriComponentsBuilder urlBuilder = getBaseUrl();
        urlBuilder.queryParam("mode", "addfile").queryParam("nzbname", title);
        if (!Strings.isNullOrEmpty(category)) {
            urlBuilder.queryParam("cat", category);
        }
        MultiValueMap<String, String> postData = new LinkedMultiValueMap<>();
        postData.add("name", fileContent);
        sendAddNzbCommand(urlBuilder, new HttpEntity<>(postData), HttpMethod.POST);
        logger.info("Successfully added NZB \"{}\" to sabnzbd queue", title);
    }

    private void sendAddNzbCommand(UriComponentsBuilder urlBuilder, HttpEntity httpEntity, HttpMethod httpMethod) throws DownloaderException {
        try {
            ResponseEntity<AddNzbResponse> response = restTemplate.exchange(urlBuilder.build().toUri(), httpMethod, httpEntity, AddNzbResponse.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new DownloaderException("Downloader returned status code " + response.getStatusCode());
            }
            if (!response.getBody().isStatus()) {
                throw new DownloaderException("Downloader says NZB was not added successfully.");
            }
        } catch (RestClientException e) {
            throw new DownloaderUnreachableException("Error while adding NZB(s)", e);
        }
    }


    @Override
    public GenericResponse checkConnection() {
        UriComponentsBuilder baseUrl = getBaseUrl();
        try {
            restTemplate.exchange(baseUrl.queryParam("mode", "get_cats").toUriString(), HttpMethod.GET, null, CategoriesResponse.class);
            logger.info("Connection check with sabnzbd using URL {} successful", baseUrl.toUriString());
            return new GenericResponse(true, null);
        } catch (RestClientException e) {
            logger.info("Connection check with sabnzbd using URL {} failed: {}", baseUrl.toUriString(), e.getMessage());
            return new GenericResponse(false, e.getMessage());
        }
    }

    @Override
    public List<String> getCategories() {
        UriComponentsBuilder uriBuilder = getBaseUrl().queryParam("mode", "get_cats");
        return restTemplate.getForObject(uriBuilder.build().toUri(), CategoriesResponse.class).getCategories();
    }

}