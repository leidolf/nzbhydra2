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

package org.nzbhydra.downloading;

import org.nzbhydra.downloading.Downloader.StatusCheckType;
import org.nzbhydra.downloading.NzbHandler.NzbDownloadEvent;
import org.nzbhydra.logging.LoggingMarkers;
import org.nzbhydra.tasks.HydraTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class DownloadStatusUpdater {

    private static final long HOUR_SECONDS = 60 * 60;
    private static final long DAY_SECONDS = 24 * 60 * 60;
    private static final long TEN_SECONDS_MS = 1000 * 10;
    private static final long TEN_MINUTES_MS = 1000 * 60 * 10;
    private static final int MIN_SECONDS_SINCE_LAST_DOWNLOAD_TO_CHECK_STATUSES = 6 * 60 * 60; //No download should last longer than 6 hours

    protected Instant lastDownload = Instant.now();
    protected boolean isEnabled = false;

    protected static final Logger logger = LoggerFactory.getLogger(DownloadStatusUpdater.class);

    static {
        logger.debug(LoggingMarkers.DOWNLOAD_STATUS_UPDATE, "Will not check for download statuses if last download was more than {} minutes ago", (MIN_SECONDS_SINCE_LAST_DOWNLOAD_TO_CHECK_STATUSES / 60));
    }

    @Autowired
    protected DownloaderProvider downloaderProvider;
    @Autowired
    protected NzbDownloadRepository downloadRepository;

    @HydraTask(configId = "downloadHistoryCheck", name = "Download history check", interval = TEN_MINUTES_MS)
    @Transactional
    public void checkHistoryStatus() {
        List<NzbDownloadStatus> statusesToCheck = Arrays.asList(NzbDownloadStatus.REQUESTED, NzbDownloadStatus.NZB_ADDED, NzbDownloadStatus.NZB_DOWNLOAD_SUCCESSFUL);
        checkStatus(statusesToCheck, DAY_SECONDS, StatusCheckType.HISTORY);
    }

    @HydraTask(configId = "downloadQueueCheck", name = "Download queue check", interval = TEN_SECONDS_MS)
    @Transactional
    public void checkQueueStatus() {
        List<NzbDownloadStatus> statusesToCheck = Arrays.asList(NzbDownloadStatus.REQUESTED);
        checkStatus(statusesToCheck, HOUR_SECONDS, StatusCheckType.QUEUE);
    }


    @EventListener
    public void onNzbDownloadEvent(NzbDownloadEvent downloadEvent) {
        lastDownload = Instant.now();
        isEnabled = true;
        logger.debug(LoggingMarkers.DOWNLOAD_STATUS_UPDATE, "Received download event. Will enable status updates for the next {} minutes", (MIN_SECONDS_SINCE_LAST_DOWNLOAD_TO_CHECK_STATUSES / 60));
    }

    protected void checkStatus(List<NzbDownloadStatus> nzbDownloadStatuses, long maxAgeDownloadEntitiesInSeconds, StatusCheckType statusCheckType) {
        if (!isEnabled) {
            return;
        }
        if (lastDownload.isBefore(Instant.now().minusSeconds(MIN_SECONDS_SINCE_LAST_DOWNLOAD_TO_CHECK_STATUSES))) {
            return;
        }
        List<NzbDownloadEntity> downloadsWaitingForUpdate = downloadRepository.findByStatusInAndTimeAfterOrderByTimeDesc(nzbDownloadStatuses, Instant.now().minusSeconds(maxAgeDownloadEntitiesInSeconds));
        if (downloadsWaitingForUpdate.isEmpty()) {
            isEnabled = false;
            return;
        }
        List<NzbDownloadEntity> updatedDownloads = new ArrayList<>();
        for (Downloader downloader : downloaderProvider.getAllDownloaders()) {
            if (downloader.isEnabled()) {
                updatedDownloads.addAll(downloader.checkForStatusUpdates(downloadsWaitingForUpdate, statusCheckType));
            }
        }
        downloadRepository.save(updatedDownloads);
    }

}
