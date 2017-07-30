package org.nzbhydra.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.nzbhydra.NzbHydra;
import org.nzbhydra.config.ConfigProvider;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

@Component
public class LogContentProvider {

    @Autowired
    private ConfigProvider configProvider;

    public long getLogFileSize() throws IOException {
        File logfile = getCurrentLogfile(false);
        if (logfile == null) {
            throw new IOException("Unable to determine log file");
        }
        if (!logfile.exists()) {
            throw new IOException("Determined log file does not exist");
        }
        return logfile.length();
    }

    public String getLog() throws IOException {
        File logfile = getCurrentLogfile(false);
        if (logfile == null) {
            throw new IOException("Unable to determine log file");
        }
        if (!logfile.exists()) {
            throw new IOException("Determined log file does not exist");
        }
        return new String(Files.readAllBytes(logfile.toPath()));
    }

    public List<String> getLogFileNames() {
        String[] files = new File(NzbHydra.getDataFolder(), "logs").list((dir, name) -> name.toLowerCase().endsWith("log"));
        return files == null ? Collections.emptyList() : Arrays.asList(files);
    }

    public JsonLogResponse getLogsAsJsonLines(int offset, int limit) throws IOException {
        File logfile = getCurrentLogfile(true);
        if (logfile == null) {
            throw new IOException("Unable to determine log file");
        }
        if (!logfile.exists()) {
            throw new IOException("Determined log file does not exist");
        }
        List<HashMap<String, Object>> objects = new ArrayList<>();
        int count = 0;
        ReversedLinesFileReader reversedLinesFileReader = new ReversedLinesFileReader(logfile, Charset.defaultCharset());
        String line = reversedLinesFileReader.readLine();
        while (offset > 0 && count++ < offset && line != null) {
            line = reversedLinesFileReader.readLine();
        }
        if (count > 0 && line == null) {
            return new JsonLogResponse(Collections.emptyList(), false, offset, 0);
        }
        count = 1;
        ObjectMapper objectMapper = new ObjectMapper();
        while (line != null && count++ <= limit) {
            TypeReference<HashMap<String, Object>> typeRef
                    = new TypeReference<HashMap<String, Object>>() {
            };

            HashMap<String, Object> o = objectMapper.readValue(line, typeRef);
            objects.add(o);
            line = reversedLinesFileReader.readLine();
        }

        return new JsonLogResponse(objects, line != null, offset, objects.size());
    }

    private File getCurrentLogfile(boolean getJsonFile) {
        File clientLogFile;
        FileAppender<?> fileAppender = null;
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (Logger logger : context.getLoggerList()) {
            for (Iterator<Appender<ILoggingEvent>> index = logger.iteratorForAppenders();
                 index.hasNext(); ) {
                Object enumElement = index.next();
                if (enumElement instanceof FileAppender) {
                    FileAppender<?> temp = (FileAppender<?>) enumElement;
                    if (getJsonFile) {
                        if (!temp.getEncoder().getClass().getName().equals(SensitiveDataRemovingPatternLayoutEncoder.class.getName())) {
                            fileAppender = temp;
                            break;
                        }
                    } else {
                        if (temp.getEncoder().getClass().getName().equals(SensitiveDataRemovingPatternLayoutEncoder.class.getName())) {
                            fileAppender = temp;
                            break;
                        }
                    }

                }
            }
        }

        if (fileAppender != null) {
            clientLogFile = new File(fileAppender.getFile());
        } else {
            clientLogFile = null;
        }
        return clientLogFile;
    }

    @Data
    @AllArgsConstructor
    public static class JsonLogResponse {
        private List<HashMap<String, Object>> lines;
        private boolean hasMore;
        private int offset;
        private int lineCount;
    }

}
