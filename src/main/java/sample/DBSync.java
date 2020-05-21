/*
 * Copyright (c) 2020.  amrishraje@gmail.com
 */

package sample;

import com.couchbase.lite.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

public class DBSync {
    private final Log logger = LogFactory.getLog(DBSync.class);
    static Properties properties;

    public DBSync() {
        try {
            properties = new Properties();
            properties.loadFromXML(new FileInputStream("config.xml"));
        } catch (IOException e) {
            logger.error("Error in static block", e);
        }
    }

    private  String SYNC_GATEWAY_URL = properties.getProperty("sgURL");
    private  final String DB_NAME = properties.getProperty("liteDBName", "syncdb");
    public  final String DB_PATH = properties.getProperty("cblite-loc");
    private  String DB_PASS = properties.getProperty("dbpass", "password");

    public  Database getDatabase() {
        return database;
    }

    private  Database database;
    private  Replicator replicator;
    private  String replErrorMsg;
    public  boolean isReplStarted = false;
    public  boolean isReplError = false;
    public  long docsReplicated = 0;
    public  long totalDocsToReplicate = 0;


    public void createLocalCBLiteFile() throws CouchbaseLiteException {
        // Initialize Couchbase Lite
        CouchbaseLite.init();
        // Get the database (and create it if it doesn't exist).
        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setDirectory(DB_PATH);
        database = new Database(DB_NAME, config);
        logger.info("CbLite file has been created and database has been initialized");
    }

    public void startReplicator(String user, String pwd, boolean isContinuous) throws URISyntaxException, CouchbaseLiteException {
        logger.info("calling startReplicator");
        if (database == null) createLocalCBLiteFile();
        loadProperties();
        SYNC_GATEWAY_URL = properties.getProperty("sgURL");
        Endpoint targetEndpoint = new URLEndpoint(new URI(SYNC_GATEWAY_URL));
        ReplicatorConfiguration replConfig = new ReplicatorConfiguration(database, targetEndpoint);
        replConfig.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PULL);
//        Do not replicate deleted docs!
        replConfig.setPullFilter((document, flags) -> !flags.contains(DocumentFlag.DocumentFlagsDeleted));

//    Amrish - Cert pinning
        if (!properties.getProperty("sgCert", "none").equals("none")) {
            InputStream is = null;
            try {
                is = new FileInputStream(properties.getProperty("sgCert"));
            } catch (FileNotFoundException ex) {
                logger.error("Sync Gateway Cert not found", ex);
            }
            byte[] cert = null;
            try {
                cert = IOUtils.toByteArray(is);
            } catch (IOException ex) {
                logger.error("Sync Gateway cert error", ex);
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignore) {
                }
            }
            replConfig.setPinnedServerCertificate(cert);
//            TODO set continuous mode here
            if (isContinuous) replConfig.setContinuous(true);
            else replConfig.setContinuous(false);
        }
//end cert pinning
        // Add authentication.
        replConfig.setAuthenticator(new BasicAuthenticator(user, pwd));

        // Create replicator (be sure to hold a reference somewhere that will prevent the Replicator from being GCed)
        replicator = new Replicator(replConfig);

        // Listen to replicator change events.
        replicator.addChangeListener(change -> {
            if (change.getStatus().getError() != null) {
                logger.error("Error replicating from Sync GW, error:  " + change.getStatus().getError().getMessage()
                        + " " + change.getStatus().getError().getCode());
            }
            logger.debug("Replication Status: " + change.getStatus());
            logger.info("I'm here - status " + change.getStatus());
        });

        // Start replication.
        replicator.start();
        isReplStarted = true;
//        Todo in continuous mode, this cannot be blocking
//        while (replicator.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) {
//            try {
//                Thread.sleep(1000);
//                totalDocsToReplicate = replicator.getStatus().getProgress().getTotal();
//                docsReplicated = replicator.getStatus().getProgress().getCompleted();
//            } catch (InterruptedException ex) {
//                logger.error("replicator getStatus failed", ex);
//            }
//        }
    }

    public String getSyncStatus(){
        return replicator.getStatus().getActivityLevel().toString();
    }

    public void onDemandSync() {
        logger.info(" starting onDemandSync");
        replicator.start();
        while (replicator.getStatus().getActivityLevel() != Replicator.ActivityLevel.STOPPED) {
            try {
                Thread.sleep(1000);
                docsReplicated = replicator.getStatus().getProgress().getCompleted();
            } catch (InterruptedException ex) {
                logger.error("pull replication sleep error", ex);
            }
        }
    }

    public void stopReplication() {
        logger.info("Stopping replication");
        if (replicator != null) {
            replicator.stop();
            isReplStarted = false;
        }
    }

    public void stopAndDeleteDB() {
        logger.info("- Deleting the local cblite DB");
        if (database != null) {
            try {
                stopReplication();
                Thread.sleep(1000);
                database.delete();
                database.close();
                database = null;
                isReplStarted = false;
                logger.info("cblite db file has been deleted...");
            } catch (CouchbaseLiteException | InterruptedException ex) {
                logger.error("stopAndDeleteDB", ex);
            }
        }
    }

    public HashMap getCBLiteData() throws CouchbaseLiteException {
        HashMap<String, String> cbData = new HashMap<>(10);
        Query queryAll = QueryBuilder.select(SelectResult.all(),
                SelectResult.expression(Meta.id))
                .from(DataSource.database(database));
        ResultSet resultFull = queryAll.execute();
        List<Result> results = resultFull.allResults();
        for (Iterator iterator = results.iterator(); iterator.hasNext(); ) {
            Result ts = (Result) iterator.next();

            cbData.put(ts.getString("id"), ts.toList().get(0).toString());
//        cbData.put(ts.getDictionary(0).getString("Id"), ts.toList().toString());
        }

        logger.info("total number of records are :" + cbData.size());
        return cbData;
    }


    //    public static void main(String[] args) throws CouchbaseLiteException, InterruptedException, URISyntaxException {
//    }
    private void loadProperties() {
        try {
//            properties = new Properties();
            properties.loadFromXML(new FileInputStream("config.xml"));
        } catch (IOException e) {
            logger.error("Error in static block", e);
        }
    }
}
