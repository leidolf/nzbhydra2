package org.nzbhydra;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.nzbhydra.config.Category;
import org.nzbhydra.config.ConfigProvider;
import org.nzbhydra.genericstorage.GenericStorage;
import org.nzbhydra.mapping.newznab.RssRoot;
import org.nzbhydra.migration.FromPythonMigration;
import org.nzbhydra.misc.BrowserOpener;
import org.nzbhydra.searching.CategoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.WebSocketAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.guava.GuavaCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Configuration
@EnableAutoConfiguration(exclude = {WebSocketAutoConfiguration.class, AopAutoConfiguration.class, org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration.class})
@ComponentScan
@RestController
@EnableCaching
@EnableScheduling
public class NzbHydra {

    private static final Logger logger = LoggerFactory.getLogger(NzbHydra.class);
    public static final String BROWSER_DISABLED = "browser.disabled";

    public static String[] originalArgs;

    private static ConfigurableApplicationContext applicationContext;


    @Autowired
    private ConfigProvider configProvider;
    private static String dataFolder = null;
    private static boolean wasRestarted = false;


    @Autowired
    private BrowserOpener browserOpener;

    @Autowired
    private CategoryProvider categoryProvider;
    @Autowired
    private GenericStorage genericStorage;

    @Autowired
    private RestTemplate restTemplate;

    private static boolean anySettingsOverwritten = false;


    public static void main(String[] args) throws Exception {
        LoggerFactory.getILoggerFactory();

        OptionParser parser = new OptionParser();
        parser.accepts("datafolder", "Define path to main data folder. Must start with ./ for relative paths").withRequiredArg().defaultsTo("./data");
        parser.accepts("host", "Run on this host").withRequiredArg();
        parser.accepts("nobrowser", "Don't open browser to Hydra");
        parser.accepts("port", "Run on this port (default: 5076)").withRequiredArg();
        parser.accepts("help", "Print help");
        parser.accepts("version", "Print version");

        OptionSet options = null;
        try {
            options = parser.parse(args);
        } catch (OptionException e) {
            logger.error("Invalid startup options detected: {}", e.getMessage());
            System.exit(1);
        }
        if (System.getProperty("fromWrapper") == null && Arrays.stream(args).noneMatch(x -> x.equals("directstart"))) {
            logger.info("NZBHydra 2 must be started using the wrapper for restart and updates to work. If for some reason you need to start it from the JAR directly provide the command line argument \"directstart\"");
        } else if (options.has("help")) {
            parser.printHelpOn(System.out);
        } else if (options.has("version")) {
            logger.info("NZBHydra 2 version: " + NzbHydra.class.getPackage().getImplementationVersion());
        } else {
            if (options.has("datafolder")) {
                dataFolder = (String) options.valueOf("datafolder");
            } else {
                dataFolder = "./data";
            }
            File dataFolderFile = new File(dataFolder);
            dataFolder = dataFolderFile.getCanonicalPath();
            //Check if we can write in the data folder. If not we can just quit now
            if (!dataFolderFile.exists() && !dataFolderFile.mkdirs()) {
                logger.error("Unable to read or write data folder {}", dataFolder);
                System.exit(1);
            }


            System.setProperty("nzbhydra.dataFolder", dataFolder);
            System.setProperty("spring.config.location", new File(dataFolder, "nzbhydra.yml").getAbsolutePath());
            useIfSet(options, "host", "server.address");
            useIfSet(options, "port", "server.port");
            useIfSet(options, "nobrowser", BROWSER_DISABLED, "true");

            SpringApplication hydraApplication = new SpringApplication(NzbHydra.class);
            NzbHydra.originalArgs = args;
            wasRestarted = Arrays.stream(args).anyMatch(x -> x.equals("restarted"));
            if (!options.has("quiet") && !options.has("nobrowser")) {
                hydraApplication.setHeadless(false);
            }

            applicationContext = hydraApplication.run(args);
        }
    }

    @PostConstruct
    private void addTrayIconIfApplicable() {
        String osName = System.getProperty("os.name");
        boolean isOsWindows = osName.toLowerCase().contains("windows");
        if (isOsWindows) {
            logger.info("Adding windows system tray icon");
            new WindowsTrayIcon();
        }
    }

    @PostConstruct
    private void warnIfSettingsOverwritten() {
        if (anySettingsOverwritten) {
            logger.warn("Overwritten settings will be displayed with their original value in the config section of the GUI");
        }
    }


    private static void useIfSet(OptionSet options, String optionKey, String propertyName) {
        useIfSet(options, optionKey, propertyName, (String) options.valueOf(optionKey));
    }

    private static void useIfSet(OptionSet options, String optionKey, String propertyName, String propertyValue) {
        if (options.has(optionKey)) {
            logger.debug("Setting property {} to value {}", propertyName, propertyValue);
            System.setProperty(propertyName, propertyValue);
            anySettingsOverwritten = true;
        }
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public static String getDataFolder() {
        return dataFolder;
    }

    @EventListener
    protected void startupDone(ApplicationReadyEvent event) {
        if (!genericStorage.get("FirstStart", LocalDateTime.class).isPresent()) {
            logger.info("First start of NZBHydra detected");
            genericStorage.save("FirstStart", LocalDateTime.now());
            try {
                configProvider.getBaseConfig().save();
            } catch (IOException e) {
                logger.error("Unable to save config", e);
            }
        }

        if (configProvider.getBaseConfig().getMain().isStartupBrowser() && !"true".equals(System.getProperty(BROWSER_DISABLED))) {
            if (wasRestarted) {
                logger.info("Not opening browser after restart");
                return;
            }
            browserOpener.openBrowser();
        } else {
            logger.info("You can access NZBHydra 2 in your browser via {}", configProvider.getBaseConfig().getBaseUriBuilder().build().toUri());
        }
    }

    @PreDestroy
    public void destroy() {
        WindowsTrayIcon.remove();
        logger.info("Shutting down");
    }


    @Bean
    public CacheManager getCacheManager() {
        GuavaCacheManager guavaCacheManager = new GuavaCacheManager("infos", "titles", "updates", "dev");
        return guavaCacheManager;
    }

    @RequestMapping(value = "/rss")
    public RssRoot get() {
        RssRoot rssRoot = restTemplate.getForObject("http://127.0.0.1:5000/api?apikey=a", RssRoot.class);

        return rssRoot;
    }

    @Autowired
    private FromPythonMigration migration;

    @RequestMapping(value = "/migrate")
    public String delete() throws Exception {
        migration.migrateFromUrl("http://127.0.0.1:5075/");

        return "Ok";
    }

    @RequestMapping(value = "/categories")
    public String getCats() {
        return categoryProvider.getCategories().stream().map(Category::getName).collect(Collectors.joining(","));

    }

    @RequestMapping("/test")
    @Transactional
    public String test() throws IOException, ExecutionException, InterruptedException {
        return "Ok";
    }


}
